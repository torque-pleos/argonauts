package com.zigae.argonauts.sync

import android.util.Log
import com.zigae.argonauts.BuildConfig
import com.zigae.argonauts.core.CrewMeta
import com.zigae.argonauts.core.DestRequest
import com.zigae.argonauts.core.Member
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 크루 상태 실시간 동기화 (Supabase: 테이블 `crew_kv` + Realtime).
 *
 * 크루 코드별로 키마다 마지막 값을 보관한다 (`supabase/migrations`).
 * - `meta`     크루 정보(리더·목적지)
 * - `m/{id}`   크루원 상태(위치·목적지·남은 시간·경유지)
 * - `req`      목적지 변경 요청(크루당 1건)
 * 삭제는 `v = null`로 갱신한다.
 *
 * 순서: Realtime 구독 → 구독 확인 → REST로 현재 값 불러오기 → 준비 완료(`metaLoaded`).
 * 쓰기는 REST upsert, 키별 최신값만 보내고 실패하면 다시 시도한다. 차량 앱과 휴대폰 웹이 같은 형식을 쓴다.
 */
class CrewSync(@Suppress("unused") private val clientTag: String) {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _members = MutableStateFlow<Map<String, Member>>(emptyMap())
    val members: StateFlow<Map<String, Member>> = _members.asStateFlow()

    private val _meta = MutableStateFlow<CrewMeta?>(null)
    val meta: StateFlow<CrewMeta?> = _meta.asStateFlow()

    private val _request = MutableStateFlow<DestRequest?>(null)
    val request: StateFlow<DestRequest?> = _request.asStateFlow()

    /** 현재 값을 다 불러왔는지 (크루 존재 확인용). */
    private val _metaLoaded = MutableStateFlow(false)
    val metaLoaded: StateFlow<Boolean> = _metaLoaded.asStateFlow()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var code: String? = null
    private var retryMs = 1_000L
    private var heartbeat: ScheduledFuture<*>? = null
    private var ref = 0

    /** 보낼 값: 키별 최신값만 (JSON 문자열, null = 삭제). */
    private val pending = ConcurrentHashMap<String, Opt>()
    private val draining = AtomicBoolean(false)

    private class Opt(val json: String?)

    fun join(crewCode: String) {
        if (code == crewCode && ws != null) return
        leave()
        code = crewCode
        _members.value = emptyMap(); _meta.value = null; _request.value = null; _metaLoaded.value = false
        connect(crewCode)
    }

    // ---------- Realtime ----------

    private fun connect(crewCode: String) {
        val wsUrl = BuildConfig.SUPABASE_URL.replace("https://", "wss://") +
            "/realtime/v1/websocket?apikey=${BuildConfig.SUPABASE_KEY}&vsn=1.0.0"
        ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (ws !== webSocket) return
                val join = JSONObject()
                    .put("topic", "realtime:crew:$crewCode").put("event", "phx_join").put("ref", nextRef())
                    .put("payload", JSONObject().put("config", JSONObject().put("postgres_changes", JSONArray().put(
                        JSONObject().put("event", "*").put("schema", "public").put("table", "crew_kv").put("filter", "code=eq.$crewCode"),
                    ))))
                webSocket.send(join.toString())
                heartbeat?.cancel(false)
                heartbeat = timer.scheduleWithFixedDelay({
                    webSocket.send(JSONObject().put("topic", "phoenix").put("event", "heartbeat").put("payload", JSONObject()).put("ref", nextRef()).toString())
                }, 25, 25, TimeUnit.SECONDS)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (ws !== webSocket) return
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                val payload = msg.optJSONObject("payload")
                when (msg.optString("event")) {
                    "system" -> if (payload?.optString("extension") == "postgres_changes") {
                        if (payload.optString("status") == "ok") io.execute { onSubscribed(webSocket, crewCode) }
                        else webSocket.cancel()
                    }
                    "postgres_changes" -> payload?.optJSONObject("data")?.optJSONObject("record")?.let { r ->
                        if (r.optString("code") == crewCode) onValue(r.optString("k"), r.opt("v"))
                    }
                    "phx_error", "phx_close" -> webSocket.cancel()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, crewCode, reason)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket, crewCode, t.message)
        })
    }

    /** 구독이 붙은 뒤 현재 값을 불러온다 (그 사이 바뀐 값은 Realtime으로 온다). */
    private fun onSubscribed(webSocket: WebSocket, crewCode: String) {
        val rows = runCatching {
            val req = rest("crew_kv?code=eq.$crewCode&select=k,v").get().build()
            http.newCall(req).execute().use { r ->
                check(r.isSuccessful) { "snapshot ${r.code}" }
                JSONArray(r.body!!.string())
            }
        }.onFailure { Log.w(TAG, "snapshot failed: ${it.message}") }.getOrNull()
        if (ws !== webSocket) return
        if (rows == null) { webSocket.cancel(); return }
        for (i in 0 until rows.length()) rows.getJSONObject(i).let { onValue(it.optString("k"), it.opt("v")) }
        Log.i(TAG, "connected $crewCode (${rows.length()} keys)")
        retryMs = 1_000L
        _connected.value = true
        _metaLoaded.value = true
        drain()
    }

    private fun lost(webSocket: WebSocket, crewCode: String, why: String?) {
        if (ws !== webSocket) return
        Log.w(TAG, "connection lost: $why (retry ${retryMs}ms)")
        heartbeat?.cancel(false)
        _connected.value = false
        val delay = retryMs
        retryMs = (retryMs * 2).coerceAtMost(15_000L)
        timer.schedule({ if (ws === webSocket && code == crewCode) connect(crewCode) }, delay, TimeUnit.MILLISECONDS)
    }

    fun leave() {
        val w = ws ?: return
        val c = code
        ws = null
        code = null
        heartbeat?.cancel(false)
        // 나가기 직전에 보낸 삭제(clearMember 등)는 끝까지 보낸다.
        val last = pending.toMap()
        pending.clear()
        if (c != null && last.isNotEmpty()) io.execute { last.forEach { (k, v) -> upsert(c, k, v.json) } }
        w.close(1000, "leave")
        _connected.value = false
    }

    // ---------- 쓰기 ----------

    fun publishMember(m: Member) = publish("m/${m.id}", m.toJson().toString())
    fun clearMember(id: String) = publish("m/$id", null)
    fun publishMeta(meta: CrewMeta) {
        _meta.value = meta // 리더 화면은 즉시 반영
        publish("meta", meta.toJson().toString())
    }
    fun clearMeta() = publish("meta", null)
    fun publishRequest(r: DestRequest) {
        _request.value = r
        publish("req", r.toJson().toString())
    }
    fun clearRequest() = publish("req", null)

    private fun publish(key: String, json: String?) {
        pending[key] = Opt(json)
        if (_metaLoaded.value) drain()
    }

    private fun drain() {
        if (!draining.compareAndSet(false, true)) return
        io.execute {
            var retrying = false
            try {
                while (true) {
                    val c = code ?: break
                    val (k, v) = pending.entries.firstOrNull()?.let { it.key to it.value } ?: break
                    if (!pending.remove(k, v)) continue
                    if (!upsert(c, k, v.json)) {
                        pending.putIfAbsent(k, v) // 그 사이 새 값이 있으면 그걸 보낸다
                        timer.schedule({ drain() }, 3, TimeUnit.SECONDS)
                        retrying = true
                        break
                    }
                }
            } finally {
                draining.set(false)
            }
            // 루프가 끝나는 사이 들어온 값이 있으면 이어서 보낸다.
            if (!retrying && pending.isNotEmpty() && code != null) drain()
        }
    }

    private fun upsert(crewCode: String, key: String, json: String?): Boolean = runCatching {
        val body = JSONObject().put("code", crewCode).put("k", key).put("v", json?.let(::JSONObject) ?: JSONObject.NULL)
        val req = rest("crew_kv?on_conflict=code,k")
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        http.newCall(req).execute().use { r -> check(r.isSuccessful) { "upsert $key ${r.code}" } }
        true
    }.onFailure { Log.w(TAG, "write failed: ${it.message}") }.getOrDefault(false)

    private fun rest(path: String) = Request.Builder()
        .url("${BuildConfig.SUPABASE_URL}/rest/v1/$path")
        .header("apikey", BuildConfig.SUPABASE_KEY)

    // ---------- 받기 ----------

    private fun onValue(key: String, v: Any?) {
        val json = v as? JSONObject
        when {
            key == "meta" -> _meta.value = json?.let(CrewMeta::from)
            key == "req" -> _request.value = json?.let(DestRequest::from)
            key.startsWith("m/") -> {
                val id = key.removePrefix("m/")
                val m = json?.let(Member::from)
                _members.update { if (m == null) it - id else it + (id to m) }
            }
        }
    }

    @Synchronized private fun nextRef() = (++ref).toString()

    companion object {
        private const val TAG = "Argo/Sync"
        private val JSON_TYPE = "application/json".toMediaType()
        private val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS)
            .build()
        private val timer = Executors.newSingleThreadScheduledExecutor()
        private val io = Executors.newCachedThreadPool()
    }
}
