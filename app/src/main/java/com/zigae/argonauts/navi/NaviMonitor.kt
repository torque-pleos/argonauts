package com.zigae.argonauts.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import com.zigae.argonauts.core.LatLon
import com.zigae.argonauts.core.Place
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class RoutePoint(val place: Place, val remainM: Int, val remainS: Int)

data class NaviState(
    val connected: Boolean = false,
    val location: LatLon? = null,
    val heading: Double = 0.0,
    val address: String? = null,
    /** 경로 안내 중인 목적지(남은 거리·시간 포함). 안내 없으면 null. */
    val destination: RoutePoint? = null,
    val waypoints: List<RoutePoint> = emptyList(),
    /** 내비에 걸린 경유지 수 (좌표를 몰라 [waypoints]에서 빠진 것 포함). */
    val waypointCount: Int = 0,
    val lastError: String? = null,
)

sealed interface NaviEvent {
    data class RouteStarted(val name: String?) : NaviEvent
    data class DestinationArrived(val name: String?) : NaviEvent
    data class WaypointArrived(val name: String?) : NaviEvent
    data object WaypointChanged : NaviEvent
    data object RouteCleared : NaviEvent
}

data class Trip(val distanceM: Int, val durationS: Int)

/**
 * NaviHelper 래퍼. 결과가 모두 콜백으로 오기 때문에 3초마다 조회하고 최신값을 StateFlow로 낸다.
 * 에뮬레이터 검증(README 7-1): 포그라운드 서비스에서 내비가 앞에 있어도 수신된다.
 */
class NaviMonitor(context: Context, private val scope: CoroutineScope) {
    private val navi = NaviHelper(context.applicationContext)
    private val _state = MutableStateFlow(NaviState())
    val state: StateFlow<NaviState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<NaviEvent> = _events.asSharedFlow()

    private var job: Job? = null
    private var pendingTrip: CompletableDeferred<Trip?>? = null
    private var pendingBookmarks: CompletableDeferred<List<Place>>? = null
    private var pendingRecent: CompletableDeferred<List<Place>>? = null

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = with(currentLocationInfo) {
            if (latitude == 0.0 && longitude == 0.0) return
            _state.update { it.copy(location = LatLon(latitude, longitude), heading = heading, address = address) }
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) = with(destinationInfo) {
            val dest = if (name.isNullOrBlank() && latitude == 0.0) null
            else RoutePoint(Place(name.ifBlank { "목적지" }, LatLon(latitude, longitude), address), remainDistance, remainTime)
            if (dest?.place?.name != _state.value.destination?.place?.name) Log.i(TAG, "destination: name='$name' $latitude,$longitude remain=${remainDistance}m/${remainTime}s")
            _state.update { it.copy(destination = dest) }
        }

        override fun onWayPointInfo(list: List<WaypointInfo>) {
            // addWaypoint로 넣은 경유지는 좌표가 0으로 오기도 한다 → 요청한 장소(이름 일치)의 좌표로 채운다.
            val wps = list.mapNotNull {
                val at = if (it.latitude != 0.0 || it.longitude != 0.0) LatLon(it.latitude, it.longitude)
                else requestedWaypoints[it.name]?.at ?: return@mapNotNull null
                RoutePoint(Place(it.name.ifBlank { "경유지" }, at, it.address), it.remainDistance, it.remainTime)
            }
            _state.update { it.copy(waypoints = wps, waypointCount = list.size) }
        }

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            Log.i(TAG, "route started: $routeStartInfo")
            _events.tryEmit(NaviEvent.RouteStarted(routeStartInfo.name))
            pollSoon()
        }

        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
            _events.tryEmit(NaviEvent.WaypointChanged)
            pollSoon()
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            Log.i(TAG, "arrived: $destinationArrivedInfo")
            _events.tryEmit(NaviEvent.DestinationArrived(destinationArrivedInfo.name))
        }

        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            _events.tryEmit(NaviEvent.WaypointArrived(waypointArrivedInfo.toString()))
        }

        // addWaypoint 재계산 중에도 한 번 오므로(검증 7-1) 즉시 비우지 않고 다음 조회로 확인한다.
        override fun onRouteCancelled() = pollSoon()
        override fun onRouteEnded() = pollSoon()

        override fun onTripInfo(tripInfo: TripInfo) {
            pendingTrip?.complete(Trip(tripInfo.distance, tripInfo.eta))
        }

        override fun onBookmarkInfo(list: List<BookmarkInfo>) {
            pendingBookmarks?.complete(list.filter { it.latitude != 0.0 }.map {
                val label = it.name.ifBlank {
                    when (it.category.name) { "HOME" -> "집"; "OFFICE" -> "회사"; else -> "즐겨찾기" }
                }
                Place(label, LatLon(it.latitude, it.longitude), it.address)
            })
        }

        override fun onRecentDestinationInfo(list: List<RecentDestinationInfo>) {
            pendingRecent?.complete(list.filter { it.latitude != 0.0 }.map { Place(it.name.ifBlank { "최근 목적지" }, LatLon(it.latitude, it.longitude), it.address) })
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "navi error $errorCode")
            _state.update { it.copy(lastError = errorCode.name) }
            if (errorCode == NaviErrorCode.CAUSE_SEARCH_TRIP) pendingTrip?.complete(null)
        }
    }

    fun start() {
        navi.addListener(listener)
        navi.initialize(
            {
                Log.i(TAG, "connected")
                _state.update { it.copy(connected = true) }
                startPolling()
            },
            { t ->
                Log.w(TAG, "connect failed", t)
                _state.update { it.copy(connected = false, lastError = "connect: ${t.message}") }
            },
        )
    }

    fun stop() {
        job?.cancel()
        runCatching { navi.removeListener(listener); navi.release() }
    }

    private fun startPolling() {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                poll()
                delay(POLL_MS)
            }
        }
    }

    private fun poll() = runCatching {
        navi.getCurrentLocationInfo()
        navi.getDestinationInfo()
        navi.getWaypointInfo()
    }.onFailure { Log.w(TAG, "poll failed: ${it.message}") }

    private fun pollSoon() {
        scope.launch { delay(800); poll() }
    }

    /** 사용자가 탭했을 때만 호출한다 (단일 경로 정책: 동의 없는 경로 변경 금지). */
    fun requestRoute(place: Place): Boolean = runCatching {
        navi.requestRoute(RouteInfo(latitude = place.at.lat, longitude = place.at.lon, poiName = place.name, poiId = "", address = place.address ?: "", poiSubId = ""))
        pollSoon()
    }.onFailure { Log.w(TAG, "requestRoute failed", it) }.isSuccess

    /** 앱이 넣은 경유지 (이름 → 장소). 내비가 좌표를 0으로 돌려줘서 앱을 다시 켜도 채울 수 있게 저장한다. */
    private val wpPrefs = context.getSharedPreferences("navi_waypoints", Context.MODE_PRIVATE)
    private val requestedWaypoints = java.util.concurrent.ConcurrentHashMap<String, Place>().apply {
        wpPrefs.all.forEach { (name, v) ->
            val ll = (v as? String)?.split(",")?.mapNotNull { it.toDoubleOrNull() }
            if (ll != null && ll.size == 2) put(name, Place(name, LatLon(ll[0], ll[1])))
        }
    }

    /** 경유지 추가. 이미 2개면 false (Pleos 경유지 최대 2). */
    fun addWaypoint(place: Place): Boolean {
        requestedWaypoints[place.name] = place
        wpPrefs.edit().putString(place.name, "${place.at.lat},${place.at.lon}").apply()
        val count = _state.value.waypointCount
        if (count >= 2) return false
        return runCatching {
            navi.addWaypoint(
                RequestWaypointInfo(
                    latitude = place.at.lat, longitude = place.at.lon, poiName = place.name,
                    waypointIndex = if (count == 0) WaypointIndex.FIRST else WaypointIndex.SECOND,
                    poiId = "", address = place.address ?: "", poiSubId = "",
                ),
            )
            pollSoon()
        }.onFailure { Log.w(TAG, "addWaypoint failed", it) }.isSuccess
    }

    /** 경로를 바꾸지 않고 해당 지점까지 거리·시간만 계산 (만나기 모드). */
    suspend fun trip(place: Place): Trip? = tripLock.withLock {
        val d = CompletableDeferred<Trip?>()
        pendingTrip = d
        runCatching { navi.requestTrip(TripRequest(latitude = place.at.lat, longitude = place.at.lon, poiId = "", poiName = place.name, poiSubId = "")) }
            .onFailure { return@withLock null }
        withTimeoutOrNull(8_000) { d.await() }
    }

    /** 응답이 콜백 하나로 오므로 한 번에 하나씩 (만나기·휴게소 계산이 겹치지 않게). */
    private val tripLock = Mutex()

    suspend fun bookmarks(): List<Place> {
        val d = CompletableDeferred<List<Place>>()
        pendingBookmarks = d
        runCatching { navi.getBookmarkInfo() }.onFailure { return emptyList() }
        return withTimeoutOrNull(5_000) { d.await() } ?: emptyList()
    }

    suspend fun recent(): List<Place> {
        val d = CompletableDeferred<List<Place>>()
        pendingRecent = d
        runCatching { navi.getRecentDestinationInfo() }.onFailure { return emptyList() }
        return withTimeoutOrNull(5_000) { d.await() } ?: emptyList()
    }

    private companion object {
        const val TAG = "Argo/Navi"
        const val POLL_MS = 3_000L
    }
}
