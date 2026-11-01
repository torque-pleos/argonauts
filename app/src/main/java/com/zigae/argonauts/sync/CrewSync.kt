package com.zigae.argonauts.sync

import android.util.Log
import com.zigae.argonauts.core.CrewMeta
import com.zigae.argonauts.core.DestRequest
import com.zigae.argonauts.core.Member
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 크루 상태 실시간 동기화 (MQTT, retained 메시지).
 *
 * 토픽 `argonauts/v1/{code}/`
 * - `meta`     크루 정보(리더·목적지), retained
 * - `m/{id}`   크루원 상태(위치·목적지·남은 시간·경유지), retained
 * - `req`      목적지 변경 요청(크루당 1건), retained
 *
 * 차량 앱과 친구용 휴대폰 웹이 같은 토픽을 쓴다. 공모전 시연용으로 공개 브로커(EMQX)를 사용하며,
 * 크루 코드를 아는 사람만 구독할 수 있다는 수준의 보호다(상용 시 자체 브로커 + 인증).
 */
class CrewSync(private val clientTag: String) {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _members = MutableStateFlow<Map<String, Member>>(emptyMap())
    val members: StateFlow<Map<String, Member>> = _members.asStateFlow()

    private val _meta = MutableStateFlow<CrewMeta?>(null)
    val meta: StateFlow<CrewMeta?> = _meta.asStateFlow()

    private val _request = MutableStateFlow<DestRequest?>(null)
    val request: StateFlow<DestRequest?> = _request.asStateFlow()

    /** 첫 retained 메시지를 받았는지 (크루 존재 확인용). */
    private val _metaLoaded = MutableStateFlow(false)
    val metaLoaded: StateFlow<Boolean> = _metaLoaded.asStateFlow()

    private var client: MqttAsyncClient? = null
    private var code: String? = null

    /** 연결 전·끊김 중 보낸 메시지는 토픽별 최신값만 보관했다가 연결되면 보낸다. */
    private val pending = ConcurrentHashMap<String, String>()

    private val prefix get() = "argonauts/v1/$code/"

    fun join(crewCode: String) {
        if (code == crewCode && client != null) return
        leave()
        code = crewCode
        _members.value = emptyMap(); _meta.value = null; _request.value = null; _metaLoaded.value = false
        val c = MqttAsyncClient(BROKER, "argo-$clientTag-${System.nanoTime() % 100000}", MemoryPersistence())
        c.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                Log.i(TAG, "connected (reconnect=$reconnect)")
                _connected.value = true
                runCatching { c.subscribe("${prefix}#", 1) }.onFailure { Log.w(TAG, "subscribe failed", it) }
                flush()
            }

            override fun connectionLost(cause: Throwable?) {
                Log.w(TAG, "connection lost: ${cause?.message}")
                _connected.value = false
            }

            override fun messageArrived(topic: String, message: MqttMessage) = onMessage(topic, String(message.payload))
            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
        client = c
        val opts = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            keepAliveInterval = 30
            connectionTimeout = 15
            maxInflight = 50
        }
        runCatching {
            c.connect(opts, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = Unit
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.w(TAG, "connect failed: ${exception?.message}")
                    retryLater(c, opts)
                }
            })
        }.onFailure { Log.w(TAG, "connect error", it) }
    }

    private fun retryLater(c: MqttAsyncClient, opts: MqttConnectOptions) {
        Thread {
            Thread.sleep(5_000)
            if (client === c && !c.isConnected) runCatching {
                c.connect(opts, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) = Unit
                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) = retryLater(c, opts)
                })
            }
        }.start()
    }

    fun leave() {
        val c = client ?: return
        client = null
        pending.clear()
        runCatching { if (c.isConnected) c.disconnect(1_000) }
        runCatching { c.close(true) }
        _connected.value = false
        code = null
    }

    fun publishMember(m: Member) = publish("m/${m.id}", m.toJson().toString())
    fun clearMember(id: String) = publish("m/$id", "")
    fun publishMeta(meta: CrewMeta) {
        _meta.value = meta // 리더 화면은 즉시 반영
        publish("meta", meta.toJson().toString())
    }
    fun clearMeta() = publish("meta", "")
    fun publishRequest(r: DestRequest) {
        _request.value = r
        publish("req", r.toJson().toString())
    }
    fun clearRequest() = publish("req", "")

    private fun publish(sub: String, payload: String) {
        val topic = prefix + sub
        val c = client
        if (c == null || !c.isConnected) {
            pending[topic] = payload
            return
        }
        send(c, topic, payload)
    }

    private fun send(c: MqttAsyncClient, topic: String, payload: String) {
        runCatching {
            val msg = MqttMessage(payload.toByteArray()).apply { qos = 1; isRetained = true }
            c.publish(topic, msg)
        }.onFailure {
            Log.w(TAG, "publish failed $topic: ${it.message}")
            pending[topic] = payload
        }
    }

    private fun flush() {
        val c = client ?: return
        val items = pending.entries.toList()
        pending.clear()
        items.forEach { (t, p) -> send(c, t, p) }
    }

    private fun onMessage(topic: String, payload: String) {
        if (!topic.startsWith(prefix)) return
        val sub = topic.removePrefix(prefix)
        val json = payload.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
        when {
            sub == "meta" -> {
                _meta.value = json?.let(CrewMeta::from)
                _metaLoaded.value = true
            }
            sub == "req" -> _request.value = json?.let(DestRequest::from)
            sub.startsWith("m/") -> {
                val id = sub.removePrefix("m/")
                val m = json?.let(Member::from)
                _members.update { if (m == null) it - id else it + (id to m) }
            }
        }
    }

    companion object {
        private const val TAG = "Argo/Sync"
        const val BROKER = "ssl://broker.emqx.io:8883"
    }
}
