package com.zigae.argonauts.engine

import android.content.Context
import android.content.Intent
import android.util.Log
import com.zigae.argonauts.core.CrewMeta
import com.zigae.argonauts.core.DestRequest
import com.zigae.argonauts.core.Geo
import com.zigae.argonauts.core.LatLon
import com.zigae.argonauts.core.Member
import com.zigae.argonauts.core.MemberKind
import com.zigae.argonauts.core.MemberStatus
import com.zigae.argonauts.core.Place
import com.zigae.argonauts.core.Prompt
import com.zigae.argonauts.core.RequestStatus
import com.zigae.argonauts.demo.DemoSim
import com.zigae.argonauts.navi.NaviEvent
import com.zigae.argonauts.navi.NaviMonitor
import com.zigae.argonauts.navi.NaviState
import com.zigae.argonauts.navi.Trip
import com.zigae.argonauts.service.CrewService
import com.zigae.argonauts.sync.CrewSync
import com.zigae.argonauts.vehicle.Gear
import com.zigae.argonauts.vehicle.GearMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class Profile(val id: String, val name: String, val colorIndex: Int, val demo: Boolean)

/** 화면 하나가 그리는 데 필요한 크루 상태 전체. */
data class CrewUi(
    val code: String,
    val meta: CrewMeta?,
    val me: Member?,
    /** 나를 제외한 크루원 (가까운 순). */
    val others: List<Member>,
    val prompts: List<Prompt>,
    val request: DestRequest?,
    val driving: Boolean,
    val connected: Boolean,
    val isLeader: Boolean,
    val completed: Boolean,
) {
    val all: List<Member> get() = listOfNotNull(me) + others
    val destination: Place? get() = meta?.destination
}

/**
 * 크루 상태 기계. 내비(NaviHelper)·기어(Vehicle)·동기화(MQTT)·시연 시뮬레이션을 묶어
 * 내 상태를 2초마다 공유하고, 크루 상태로부터 한 번 탭 카드(Prompt)를 만든다.
 */
class CrewEngine(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = context.getSharedPreferences("argonauts", Context.MODE_PRIVATE)

    val navi = NaviMonitor(context, scope)
    val gear = GearMonitor(context, scope)
    private val demo = DemoSim(scope)

    private val _profile = MutableStateFlow(loadProfile())
    val profile: StateFlow<Profile> = _profile.asStateFlow()

    private var sync: CrewSync? = null
    private val _code = MutableStateFlow<String?>(null)
    val code: StateFlow<String?> = _code.asStateFlow()

    private val _ui = MutableStateFlow<CrewUi?>(null)
    val ui: StateFlow<CrewUi?> = _ui.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    private val dismissed = MutableStateFlow<Set<String>>(emptySet())

    /**
     * 시연용 '주행 화면 미리보기'. 에뮬레이터는 adb 설치 앱을 허용된 설치 출처로 보지 않아
     * 주행 중엔 시스템 차단 화면이 뜨므로(App Market 설치 시엔 DO 선언 적용), 정차 상태에서 간결 모드와 차량 이동을 보여준다.
     */
    private val _drivingPreview = MutableStateFlow(false)
    val drivingPreview: StateFlow<Boolean> = _drivingPreview.asStateFlow()

    fun setDrivingPreview(on: Boolean) { _drivingPreview.value = on; _ui.value?.let { sync?.let(::rebuildUi) } }

    /** 실제 기어가 D/R/N 이거나 주행 화면 미리보기 중. */
    fun isDriving(): Boolean = _drivingPreview.value || gear.gear.value.let { it != Gear.PARK && it != Gear.UNKNOWN }
    private var loopJob: Job? = null
    private var uiJob: Job? = null
    private var eventJob: Job? = null

    /** 내 도착 상태 (목적지가 바뀌면 해제). */
    private var arrivedAt: Long? = null
    private var arrivedFor: Place? = null
    /** 리더 내비 목적지 변경 감지용 이전 값. */
    private var lastNaviDest: Place? = null
    /** 앱이 경로를 요청한 직후: 내비가 새 목적지를 보고할 때까지 '내비에서 직접 변경' 감지를 멈춘다. */
    private var expectedNaviDest: Place? = null
    /** 참여·재접속 직후엔 내비 목적지를 아직 모르므로 첫 관측값을 기준으로만 삼는다. */
    private var naviBaseline = false
    private var expectedUntil = 0L

    private fun routeTo(place: Place): Boolean {
        expectedNaviDest = place
        expectedUntil = System.currentTimeMillis() + 20_000
        lastNaviDest = place
        return navi.requestRoute(place)
    }

    fun start() {
        navi.start()
        gear.start()
        prefs.getString(KEY_CREW, null)?.let { rejoin(it) }
    }

    // region 프로필

    private fun loadProfile(): Profile {
        val id = prefs.getString("id", null) ?: UUID.randomUUID().toString().take(8).also { prefs.edit().putString("id", it).apply() }
        return Profile(id, prefs.getString("name", "") ?: "", prefs.getInt("color", 0), prefs.getBoolean("demo", true))
    }

    fun setName(name: String) {
        prefs.edit().putString("name", name.trim()).apply()
        _profile.update { it.copy(name = name.trim()) }
    }

    fun setDemo(on: Boolean) {
        prefs.edit().putBoolean("demo", on).apply()
        _profile.update { it.copy(demo = on) }
        if (_code.value != null) restartDemo()
    }

    // endregion

    // region 크루 만들기·참여·나가기

    fun createCrew(crewName: String) {
        val code = newCode()
        val me = _profile.value
        val dest = navi.state.value.destination?.place
        val s = openSync(code)
        setColor(0)
        s.publishMeta(CrewMeta(code, crewName.ifBlank { "${me.name}의 크루" }, me.id, dest, System.currentTimeMillis()))
        lastNaviDest = dest
        naviBaseline = true
        enter(code)
    }

    /** 코드로 참여. 크루가 없으면 false. */
    suspend fun joinCrew(code: String): Boolean {
        val c = code.trim().uppercase()
        val s = openSync(c)
        withTimeoutOrNull(8_000) { s.metaLoaded.first { it } }
        val meta = s.meta.value
        if (meta == null || meta.closedAt != null) {
            s.leave(); sync = null
            return false
        }
        val used = s.members.value.values.map { it.colorIndex }.toSet()
        setColor((0 until PALETTE_SIZE).firstOrNull { it !in used } ?: (s.members.value.size % PALETTE_SIZE))
        lastNaviDest = navi.state.value.destination?.place
        naviBaseline = lastNaviDest != null
        enter(c)
        return true
    }

    private fun rejoin(code: String) {
        openSync(code)
        lastNaviDest = navi.state.value.destination?.place
        naviBaseline = lastNaviDest != null
        enter(code)
    }

    private fun openSync(code: String): CrewSync {
        sync?.leave()
        return CrewSync(_profile.value.id).also { it.join(code); sync = it }
    }

    private fun setColor(i: Int) {
        prefs.edit().putInt("color", i).apply()
        _profile.update { it.copy(colorIndex = i) }
    }

    private fun enter(code: String) {
        prefs.edit().putString(KEY_CREW, code).apply()
        _code.value = code
        arrivedAt = null; arrivedFor = null
        dismissed.value = emptySet()
        context.startForegroundService(Intent(context, CrewService::class.java))
        startLoops()
        restartDemo()
    }

    /** 나가기. 리더가 나가면 크루를 닫고 공유 데이터를 지운다. */
    fun leaveCrew() {
        val s = sync
        val me = _profile.value
        val ui = _ui.value
        if (s != null) {
            s.clearMember(me.id)
            if (ui?.isLeader == true) {
                demo.friendIds().forEach(s::clearMember)
                ui.others.forEach { s.clearMember(it.id) }
                s.clearRequest()
                s.clearMeta()
            }
            scope.launch { delay(1_500); s.leave() }
        }
        demo.stop()
        loopJob?.cancel(); uiJob?.cancel(); eventJob?.cancel()
        sync = null
        _code.value = null
        _ui.value = null
        prefs.edit().remove(KEY_CREW).apply()
        context.stopService(Intent(context, CrewService::class.java))
    }

    private fun newCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    // endregion

    // region 루프

    private fun startLoops() {
        val s = sync ?: return
        loopJob?.cancel(); uiJob?.cancel(); eventJob?.cancel()

        loopJob = scope.launch {
            while (isActive) {
                runCatching { tick(s) }.onFailure { Log.w(TAG, "tick failed", it) }
                delay(PUBLISH_MS)
            }
        }

        uiJob = scope.launch {
            combine(s.members, s.meta, s.request, s.connected, combine(dismissed, _drivingPreview, gear.gear) { _, _, _ -> Unit }) { _, _, _, _, _ -> Unit }
                .collect { rebuildUi(s) }
        }

        eventJob = scope.launch {
            navi.events.collect { e ->
                when (e) {
                    is NaviEvent.DestinationArrived -> markArrived()
                    else -> Unit
                }
            }
        }
    }

    private fun tick(s: CrewSync) {
        val me = myMember() ?: return
        s.publishMember(me)
        leaderDuties(s)
        rebuildUi(s)
    }

    /** 내 현재 상태 (시연 모드면 시뮬레이션 위치·남은 거리 사용). */
    private fun myMember(): Member? {
        val p = _profile.value
        val n = navi.state.value
        val sim = if (p.demo) demo.selfSnapshot else null
        val dest = n.destination?.place
        if (arrivedFor != null && !dest.sameOrNull(arrivedFor)) { arrivedAt = null; arrivedFor = null }
        val remainM = sim?.remainM ?: n.destination?.remainM
        val remainS = sim?.remainS ?: n.destination?.remainS
        if (dest != null && arrivedAt == null && ((sim?.arrived == true) || (sim == null && remainM != null && remainM in 1..50))) markArrived()
        val driving = isDriving()
        return Member(
            id = p.id, name = p.name.ifBlank { "나" }, colorIndex = p.colorIndex, kind = MemberKind.CAR,
            at = sim?.at ?: n.location, heading = sim?.heading ?: n.heading,
            status = when {
                arrivedAt != null -> MemberStatus.ARRIVED
                driving -> MemberStatus.DRIVING
                else -> MemberStatus.STOPPED
            },
            destination = dest,
            remainM = if (dest != null) remainM else null,
            remainS = if (dest != null) remainS else null,
            waypoint = n.waypoints.firstOrNull()?.place,
            waypointRemainS = sim?.waypointRemainS ?: n.waypoints.firstOrNull()?.remainS,
            arrivedAt = arrivedAt,
        )
    }

    private fun Place?.sameOrNull(other: Place?) = this != null && sameAs(other)

    private fun markArrived() {
        val dest = navi.state.value.destination?.place ?: _ui.value?.destination ?: return
        if (arrivedAt != null) return
        arrivedAt = System.currentTimeMillis()
        arrivedFor = dest
        _toasts.tryEmit("${dest.name} 도착 · 크루에 알렸어요")
    }

    /** 리더: 내비에서 직접 바꾼 목적지를 크루 목적지로 반영, 요청 만료 처리. */
    private fun leaderDuties(s: CrewSync) {
        val meta = s.meta.value ?: return
        val me = _profile.value
        if (meta.leaderId != me.id) return
        val naviDest = navi.state.value.destination?.place
        if (!naviBaseline) {
            if (naviDest != null) { lastNaviDest = naviDest; naviBaseline = true }
            return
        }
        expectedNaviDest?.let { exp ->
            if (naviDest.sameOrNull(exp) || System.currentTimeMillis() > expectedUntil) expectedNaviDest = null
            else return@let
        }
        if (expectedNaviDest == null && naviDest != null && !naviDest.sameAs(lastNaviDest) && !naviDest.sameAs(meta.destination)) {
            Log.i(TAG, "leader destination changed in navi -> ${naviDest.name}")
            s.publishMeta(meta.copy(destination = naviDest, updatedAt = System.currentTimeMillis()))
        }
        if (expectedNaviDest == null) lastNaviDest = naviDest ?: lastNaviDest
        val req = s.request.value
        if (req != null && req.status == RequestStatus.PENDING && System.currentTimeMillis() > req.expiresAt) {
            s.publishRequest(req.copy(status = RequestStatus.EXPIRED, decidedAt = System.currentTimeMillis()))
        }
    }

    private fun rebuildUi(s: CrewSync) {
        val code = _code.value ?: return
        val p = _profile.value
        val meta = s.meta.value
        val me = myMember()
        val now = System.currentTimeMillis()
        val others = s.members.value.values
            .filter { it.id != p.id }
            .map { if (!it.simulated && now - it.updatedAt > OFFLINE_MS && it.status != MemberStatus.ARRIVED) it.copy(status = MemberStatus.OFFLINE) else it }
            .sortedWith(compareBy<Member> { it.status == MemberStatus.OFFLINE }.thenBy { it.remainM ?: Int.MAX_VALUE }.thenBy { it.name })
        val driving = isDriving()
        val isLeader = meta?.leaderId == p.id
        val req = s.request.value
        val crewDest = meta?.destination
        val active = (listOfNotNull(me) + others).filter { it.kind == MemberKind.CAR && it.status != MemberStatus.OFFLINE }
        val completed = crewDest != null && active.size >= 2 && active.all { it.status == MemberStatus.ARRIVED && it.destination.sameOrNull(crewDest) }
        _ui.value = CrewUi(
            code = code, meta = meta, me = me, others = others,
            prompts = buildPrompts(meta, me, others, req, isLeader, navi.state.value).filter { it.key !in dismissed.value },
            request = req, driving = driving, connected = s.connected.value, isLeader = isLeader, completed = completed,
        )
    }

    private fun buildPrompts(meta: CrewMeta?, me: Member?, others: List<Member>, req: DestRequest?, isLeader: Boolean, n: NaviState): List<Prompt> {
        val out = mutableListOf<Prompt>()
        val myId = _profile.value.id
        if (req != null) {
            if (isLeader && req.status == RequestStatus.PENDING && req.fromId != myId) out += Prompt.ApproveRequest(req)
            if (req.fromId == myId) out += Prompt.MyRequest(req)
        }
        val crewDest = meta?.destination
        val myDest = n.destination?.place
        if (crewDest != null && !crewDest.sameAs(myDest) && me?.status != MemberStatus.ARRIVED) {
            out += Prompt.FollowDestination(crewDest, changed = myDest != null)
        }
        if (myDest != null) {
            others.filter { it.status != MemberStatus.OFFLINE }.forEach { m ->
                val wp = m.waypoint ?: return@forEach
                if (n.waypoints.none { it.place.sameAs(wp) }) out += Prompt.JoinWaypoint(m, wp)
            }
        }
        return out
    }

    // endregion

    // region 사용자 동작 (모두 탭 한 번)

    fun dismiss(p: Prompt) = dismissed.update { it + p.key }

    fun emitToast(msg: String) { _toasts.tryEmit(msg) }

    fun followDestination(p: Prompt.FollowDestination) {
        if (routeTo(p.place)) _toasts.tryEmit("${p.place.name}(으)로 안내를 시작해요")
        dismiss(p)
    }

    fun joinWaypoint(p: Prompt.JoinWaypoint) {
        if (navi.addWaypoint(p.place)) _toasts.tryEmit("${p.place.name}을(를) 경유지로 추가했어요")
        else _toasts.tryEmit("경유지는 최대 2곳까지예요")
        dismiss(p)
    }

    fun approve(p: Prompt.ApproveRequest) {
        val s = sync ?: return
        val meta = s.meta.value ?: return
        val now = System.currentTimeMillis()
        s.publishRequest(p.request.copy(status = RequestStatus.ACCEPTED, decidedAt = now))
        s.publishMeta(meta.copy(destination = p.request.place, updatedAt = now))
        routeTo(p.request.place)
        _toasts.tryEmit("크루 목적지를 ${p.request.place.name}(으)로 바꿨어요")
    }

    fun decline(p: Prompt.ApproveRequest) {
        val s = sync ?: return
        s.publishRequest(p.request.copy(status = RequestStatus.DECLINED, decidedAt = System.currentTimeMillis()))
    }

    /**
     * 목적지 변경 (정차 중). 리더는 바로 바꾸고, 크루원은 리더에게 요청한다.
     * @return 안내 문구
     */
    fun proposeDestination(place: Place): String {
        val s = sync ?: return "크루에 연결되어 있지 않아요"
        val meta = s.meta.value ?: return "크루 정보를 불러오는 중이에요"
        val me = _profile.value
        val now = System.currentTimeMillis()
        if (meta.leaderId == me.id) {
            s.publishMeta(meta.copy(destination = place, updatedAt = now))
            routeTo(place)
            return "크루 목적지를 ${place.name}(으)로 정했어요"
        }
        val cur = s.request.value
        if (cur != null && cur.status == RequestStatus.PENDING && now < cur.expiresAt) return "이미 대기 중인 요청이 있어요"
        s.publishRequest(DestRequest(UUID.randomUUID().toString().take(8), me.id, me.name.ifBlank { "크루원" }, place, RequestStatus.PENDING, now))
        return "리더에게 목적지 변경을 요청했어요"
    }

    /** 만나기: 크루원의 현재 위치로 안내 (정차 중). */
    fun goToMember(m: Member) {
        val at = m.at ?: return
        routeTo(Place("${m.name}의 위치", at))
        _toasts.tryEmit("${m.name}에게 가는 길을 안내해요")
    }

    suspend fun tripTo(m: Member): Trip? = m.at?.let { navi.trip(Place(m.name, it)) }

    suspend fun bookmarks() = navi.bookmarks()
    suspend fun recent() = navi.recent()

    fun myLocation(): LatLon? = _ui.value?.me?.at ?: navi.state.value.location

    // endregion

    // region 시연 모드

    private fun restartDemo() {
        val s = sync ?: return
        demo.stop()
        if (!_profile.value.demo) {
            demo.friendIds().forEach(s::clearMember)
            return
        }
        // 가상 크루원은 리더 기기에서만 움직인다 (중복 방지)
        val leaderOnly = { s.meta.value?.leaderId == _profile.value.id }
        demo.start(
            crewDestination = { s.meta.value?.destination },
            myStart = { navi.state.value.location },
            myRoute = { navi.state.value.destination?.place to navi.state.value.waypoints.map { it.place } },
            moving = { isDriving() },
            publish = { m -> if (leaderOnly()) s.publishMember(m) },
        )
    }

    fun demoJiyoungRestStop() = scope.launch {
        val p = demo.jiyoungRestStop()
        _toasts.emit(if (p != null) "[시연] 지영 → ${p.name} 경유지 추가" else "[시연] 크루 목적지를 먼저 정해 주세요")
    }

    fun demoMinsuRequest() = scope.launch {
        val s = sync ?: return@launch
        val place = demo.minsuSuggestion() ?: run { _toasts.emit("[시연] 크루 목적지를 먼저 정해 주세요"); return@launch }
        s.publishRequest(DestRequest(UUID.randomUUID().toString().take(8), demo.minsuId, demo.minsuName, place, RequestStatus.PENDING, System.currentTimeMillis()))
        _toasts.emit("[시연] 민수 → ${place.name} 목적지 변경 요청")
    }

    fun demoArriveAll() = scope.launch {
        demo.arriveAll()
        delay(1_200)
        markArrived()
    }

    // endregion

    companion object {
        private const val TAG = "Argo/Engine"
        private const val KEY_CREW = "crew"
        const val PALETTE_SIZE = 6
        private const val PUBLISH_MS = 2_000L
        private const val OFFLINE_MS = 90_000L
    }
}
