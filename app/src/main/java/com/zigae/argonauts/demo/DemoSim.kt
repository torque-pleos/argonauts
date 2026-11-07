package com.zigae.argonauts.demo

import android.util.Log
import com.zigae.argonauts.core.Geo
import com.zigae.argonauts.core.LatLon
import com.zigae.argonauts.core.Member
import com.zigae.argonauts.core.MemberKind
import com.zigae.argonauts.core.MemberStatus
import com.zigae.argonauts.core.Place
import com.zigae.argonauts.net.PlaceSearch
import com.zigae.argonauts.net.RouteLine
import com.zigae.argonauts.net.Routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 폴리라인 위를 일정 속도로 움직이는 시뮬레이션 차량. */
class SimCar(val start: LatLon) {
    var line: RouteLine? = null
        private set
    private var cumulative = DoubleArray(0)
    var progressM = 0.0
        private set
    var pausedUntil = 0L
    var position: LatLon = start
        private set
    var heading = 0.0
        private set

    /** 경유지가 있으면 경유지까지의 진행 거리(m). */
    var waypointAtM: Double? = null
    var waypointStopped = false

    val totalM get() = cumulative.lastOrNull() ?: 0.0
    val remainM get() = (totalM - progressM).coerceAtLeast(0.0)
    val arrived get() = line != null && remainM < 30

    fun setRoute(r: RouteLine, waypointIndexHint: LatLon? = null) {
        line = r
        cumulative = DoubleArray(r.points.size)
        for (i in 1 until r.points.size) cumulative[i] = cumulative[i - 1] + Geo.distanceM(r.points[i - 1], r.points[i])
        progressM = 0.0
        position = r.points.first()
        waypointAtM = waypointIndexHint?.let { wp -> r.points.indices.minByOrNull { Geo.distanceM(r.points[it], wp) }?.let { cumulative[it] } }
        waypointStopped = false
    }

    fun clearRoute() {
        line = null; cumulative = DoubleArray(0); progressM = 0.0; waypointAtM = null
    }

    /** 남은 시간(실제 도로 속도 기준, 시연 배속과 무관하게 현실적인 값). */
    fun remainS(): Int {
        val l = line ?: return 0
        if (totalM <= 0) return 0
        return (l.durationS * remainM / totalM).toInt()
    }

    fun remainToWaypointS(): Int? {
        val l = line ?: return null
        val w = waypointAtM ?: return null
        if (progressM >= w || totalM <= 0) return null
        return (l.durationS * (w - progressM) / totalM).toInt()
    }

    fun advance(meters: Double) {
        val l = line ?: return
        if (System.currentTimeMillis() < pausedUntil) return
        var target = (progressM + meters).coerceAtMost(totalM)
        val w = waypointAtM
        if (w != null && !waypointStopped && progressM < w && target >= w) {
            target = w
            waypointStopped = true
            pausedUntil = System.currentTimeMillis() + WAYPOINT_STOP_MS
        }
        progressM = target
        var i = cumulative.indexOfFirst { it >= progressM }.coerceAtLeast(1)
        if (i >= l.points.size) i = l.points.size - 1
        val a = l.points[i - 1]; val b = l.points[i]
        val seg = cumulative[i] - cumulative[i - 1]
        val t = if (seg <= 0) 1.0 else (progressM - cumulative[i - 1]) / seg
        val next = Geo.lerp(a, b, t.coerceIn(0.0, 1.0))
        if (Geo.distanceM(position, next) > 1) heading = Geo.bearing(position, next)
        position = next
    }

    fun jumpToEnd() {
        if (line == null) return
        advance(totalM)
        progressM = totalM
    }

    val stoppedAtWaypoint get() = System.currentTimeMillis() < pausedUntil

    companion object {
        const val WAYPOINT_STOP_MS = 25_000L
    }
}

/** 내 차 시뮬레이션 상태 (에뮬레이터는 현재 위치가 고정이라 시연용). */
data class SimSelf(val at: LatLon, val heading: Double, val remainM: Int, val remainS: Int, val waypointRemainS: Int?, val arrived: Boolean)

/**
 * 시연 모드: 가상 크루원 2명(민수·지영)이 크루 목적지로 이동하고, 내 차도 기어가 D일 때 경로를 따라 움직인다.
 * 시연 스크립트(지영 휴게소 들르기, 민수 목적지 변경 요청, 전원 도착)는 설정 화면 버튼으로 실행한다.
 */
class DemoSim(private val scope: CoroutineScope) {
    private val lock = Mutex()
    private var job: Job? = null

    private fun newFriends() = listOf(
        Friend("sim-minsu", "민수", 1, LatLon(37.3948, 127.1112), pace = 0.97), // 판교역 인근
        Friend("sim-jiyoung", "지영", 2, LatLon(37.5133, 127.1001), pace = 1.04), // 잠실 (다른 곳에서 출발 → 중간에 모이기)
    )
    // 새 크루마다 출발지에서 다시 시작한다 (지난 시연의 도착 상태가 남지 않게)
    private var friends = newFriends()
    private var self: SimCar? = null
    private var selfKey: String? = null
    private var crewDest: Place? = null

    @Volatile var selfSnapshot: SimSelf? = null
        private set

    private class Friend(val id: String, val name: String, val color: Int, start: LatLon, val pace: Double) {
        val car = SimCar(start)
        var dest: Place? = null
        var waypoint: Place? = null
        var arrivedAt: Long? = null
    }

    fun start(
        crewDestination: () -> Place?,
        myStart: () -> LatLon?,
        myRoute: () -> Pair<Place?, List<Place>>,
        moving: () -> Boolean,
        publish: (Member) -> Unit,
    ) {
        stop()
        job = scope.launch {
            // 이전 루프가 끝난 뒤(lock 안에서) 초기화 → 시연 스크립트와 동시에 바뀌지 않는다
            lock.withLock { friends = newFriends(); crewDest = null }
            while (isActive) {
                lock.withLock {
                    syncFriends(crewDestination())
                    syncSelf(myStart(), myRoute())
                    val go = moving()
                    movingNow = go
                    // 가상 크루원은 내 차가 달릴 때만 함께 움직인다 (시연 중 행렬 유지)
                    friends.forEach { f ->
                        if (go) f.car.advance(SPEED_MPS * f.pace)
                        if (f.car.arrived && f.arrivedAt == null) f.arrivedAt = System.currentTimeMillis()
                    }
                    self?.let { s ->
                        if (go) s.advance(SPEED_MPS)
                        selfSnapshot = SimSelf(s.position, s.heading, s.remainM.toInt(), s.remainS(), s.remainToWaypointS(), s.arrived)
                    } ?: run { selfSnapshot = null }
                    friends.forEach { publish(it.toMember()) }
                }
                delay(1_000)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        selfSnapshot = null
        self = null; selfKey = null
    }

    fun friendIds() = friends.map { it.id }

    private suspend fun syncFriends(dest: Place?) {
        if (dest == crewDest || (dest != null && dest.sameAs(crewDest, 50.0))) return
        crewDest = dest
        friends.forEach { f ->
            f.dest = dest
            f.waypoint = null
            f.arrivedAt = null
            if (dest == null) f.car.clearRoute()
            else f.car.setRoute(Routing.route(listOf(f.car.position, dest.at)))
        }
        Log.i(TAG, "friends routed to ${dest?.name}")
    }

    private suspend fun syncSelf(start: LatLon?, route: Pair<Place?, List<Place>>) {
        val (dest, wps) = route
        val key = dest?.let { d -> "${d.at.lat},${d.at.lon}|" + wps.joinToString { "${it.at.lat},${it.at.lon}" } }
        if (key == selfKey) return
        selfKey = key
        if (dest == null) { self = null; return }
        val from = self?.position ?: start ?: return
        val car = SimCar(from)
        car.setRoute(Routing.route(listOf(from) + wps.map { it.at } + dest.at), wps.firstOrNull()?.at)
        self = car
    }

    /** 스크립트: 지영이 경로 중간 휴게소를 경유지로 추가하고 들른다. */
    suspend fun jiyoungRestStop(): Place? = lock.withLock {
        val f = friends.first { it.id == "sim-jiyoung" }
        val line = f.car.line ?: return@withLock null
        val dest = f.dest ?: return@withLock null
        val idx = line.points.indices.firstOrNull { i ->
            val d = Geo.distanceM(line.points.first(), line.points[i])
            d > Geo.distanceM(line.points.first(), dest.at) * 0.5
        } ?: (line.points.size / 2)
        val mid = line.points[idx]
        // 고속도로 휴게소(OSM highway=services) 중 지영의 경로에서 가까운 곳
        val onRoute = line.points.filterIndexed { i, _ -> i % 5 == 0 }
        val found = PlaceSearch.search("휴게소", mid, osmTag = "highway:services", limit = 20)
            .map { p -> p to onRoute.minOf { Geo.distanceM(it, p.at) } }
            .filter { it.second < 8_000 }
            .minByOrNull { it.second + Geo.distanceM(it.first.at, mid) * 0.2 }?.first
        val stop = found ?: Place("졸음쉼터", mid)
        f.waypoint = stop
        f.car.setRoute(Routing.route(listOf(f.car.position, stop.at, dest.at)), stop.at)
        Log.i(TAG, "jiyoung waypoint ${stop.name}")
        stop
    }

    /** 시연: 가상 크루원도 모임 휴게소를 경유지로 넣는다 ('같이 들르기'를 누른 것처럼). */
    suspend fun friendsJoin(stop: Place) = lock.withLock {
        friends.forEach { f ->
            val dest = f.dest ?: return@forEach
            if (f.arrivedAt != null || f.waypoint?.sameAs(stop) == true) return@forEach
            f.waypoint = stop
            f.car.setRoute(Routing.route(listOf(f.car.position, stop.at, dest.at)), stop.at)
        }
        Log.i(TAG, "friends join ${stop.name}")
    }

    /** 스크립트: 민수가 목적지 근처 다른 장소로 변경을 제안할 장소를 찾는다. */
    suspend fun minsuSuggestion(): Place? {
        val dest = crewDest ?: return null
        val cands = PlaceSearch.search("카페", dest.at).filter { Geo.distanceM(it.at, dest.at) in 300.0..15_000.0 }
        return cands.minByOrNull { Geo.distanceM(it.at, dest.at) }
            ?: Place("${dest.name} 근처 카페", LatLon(dest.at.lat + 0.01, dest.at.lon + 0.01))
    }

    val minsuId = "sim-minsu"
    val minsuName = "민수"

    /** 스크립트: 전원 도착 (발표 시간 단축). */
    suspend fun arriveAll() = lock.withLock {
        friends.forEach { f -> f.car.pausedUntil = 0; f.car.waypointAtM = null; f.car.jumpToEnd(); if (f.arrivedAt == null) f.arrivedAt = System.currentTimeMillis() }
        self?.let { it.pausedUntil = 0; it.waypointAtM = null; it.jumpToEnd() }
    }

    @Volatile private var movingNow = false

    private fun Friend.toMember(): Member {
        val c = car
        val routing = c.line != null
        return Member(
            id = id, name = name, colorIndex = color, kind = MemberKind.CAR,
            at = c.position, heading = c.heading,
            status = when {
                arrivedAt != null -> MemberStatus.ARRIVED
                !routing || c.stoppedAtWaypoint || !movingNow -> MemberStatus.STOPPED
                else -> MemberStatus.DRIVING
            },
            destination = dest,
            remainM = if (routing) c.remainM.toInt() else null,
            remainS = if (routing) c.remainS() else null,
            waypoint = waypoint?.takeIf { c.remainToWaypointS() != null || c.stoppedAtWaypoint },
            waypointRemainS = c.remainToWaypointS(),
            arrivedAt = arrivedAt,
            simulated = true,
        )
    }

    companion object {
        private const val TAG = "Argo/Demo"
        /** 시연 배속: 실제 80km/h의 약 12배 → 80km를 6분 안에. */
        const val SPEED_MPS = 80_000.0 / 3600 * 12
    }
}
