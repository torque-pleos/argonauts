package com.zigae.argonauts

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * 검증용 포그라운드 서비스. NaviHelper 콜백을 전부 로그로 남기고 3초마다 조회한다.
 * 명령: adb shell am start-foreground-service -n com.zigae.argonauts/.ProbeService -a <action> [--ed lat .. --ed lon .. --es name ..]
 */
class ProbeService : Service() {
    private lateinit var navi: NaviHelper
    private val main = Handler(Looper.getMainLooper())
    private var connected = false

    private val poll = object : Runnable {
        override fun run() {
            if (connected) runCatching {
                navi.getCurrentLocationInfo()
                navi.getDestinationInfo()
                navi.getWaypointInfo()
                navi.getRouteStateInfo()
            }.onFailure { ProbeLog.add("poll failed: $it") }
            main.postDelayed(this, POLL_MS)
        }
    }

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = with(currentLocationInfo) {
            ProbeLog.add("LOC %.5f,%.5f hd=%.0f addr=%s route=%s idx=%s".format(latitude, longitude, heading, address, routeId, currentRouteIndex))
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = with(destinationInfo) {
            ProbeLog.add("DEST name=$name %.5f,%.5f remainDist=${remainDistance}m remainTime=${remainTime}s toll=$tollFare".format(latitude, longitude))
        }
        override fun onWayPointInfo(list: List<WaypointInfo>) {
            ProbeLog.add("WAYPOINTS n=${list.size} " + list.joinToString { "${it.name}(${it.remainDistance}m,${it.remainTime}s)" })
        }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = with(routeStateInfo) {
            ProbeLog.add("ROUTESTATE terms=$termsAgreement mode=$naviMode opt=$routeOption traffic=$trafficStatus")
        }
        override fun onTripInfo(tripInfo: TripInfo) = with(tripInfo) {
            ProbeLog.add("TRIP dist=$distance eta=$eta soc=$estimatedSocAtDest energy=$energyConsumption summary=$summary")
        }
        override fun onDrivingInfo(drivingInfo: DrivingInfo) = ProbeLog.add("DRIVING $drivingInfo")
        override fun onNavigationStatus(navigationStatus: NavigationStatus) = ProbeLog.add("NAVSTATUS $navigationStatus")
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = ProbeLog.add("EV routeStarted $routeStartInfo")
        override fun onRoutingCompleted() = ProbeLog.add("EV routingCompleted")
        override fun onRouteEnded() = ProbeLog.add("EV routeEnded")
        override fun onRouteCancelled() = ProbeLog.add("EV routeCancelled")
        override fun onDestinationChanged() = ProbeLog.add("EV destinationChanged")
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) =
            ProbeLog.add("EV waypointChanged idx=${waypointChangedInfo.index} cmd=${waypointChangedInfo.cmdType}")
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = ProbeLog.add("EV destinationArrived $destinationArrivedInfo")
        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) = ProbeLog.add("EV waypointArrived $waypointArrivedInfo")
        override fun onBookmarkInfo(list: List<BookmarkInfo>) = ProbeLog.add("BOOKMARKS n=${list.size} " + list.joinToString { "${it.name}/${it.category}" })
        override fun onRecentDestinationInfo(list: List<RecentDestinationInfo>) = ProbeLog.add("RECENT n=${list.size} " + list.joinToString { it.name })
        override fun onError(errorCode: NaviErrorCode) = ProbeLog.add("ERROR $errorCode")
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Crew sharing", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CH).setContentTitle("Argonauts").setContentText("크루 위치 공유 검증 중")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        navi = NaviHelper(applicationContext)
        navi.addListener(listener)
        navi.initialize(
            { connected = true; ProbeLog.add("navi connected") },
            { t -> ProbeLog.add("navi connect failed: $t") },
        )
        main.postDelayed(poll, POLL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val a = intent?.action ?: return START_STICKY
        val lat = intent.getDoubleExtra("lat", 0.0)
        val lon = intent.getDoubleExtra("lon", 0.0)
        val name = intent.getStringExtra("name") ?: "테스트"
        ProbeLog.add("CMD $a $name $lat,$lon")
        runCatching {
            when (a) {
                "route" -> navi.requestRoute(RouteInfo(latitude = lat, longitude = lon, poiName = name, poiId = "", address = "", poiSubId = ""))
                "waypoint" -> navi.addWaypoint(
                    RequestWaypointInfo(
                        latitude = lat, longitude = lon, poiName = name,
                        waypointIndex = if (intent.getIntExtra("idx", 1) == 2) WaypointIndex.SECOND else WaypointIndex.FIRST,
                        poiId = "", address = "", poiSubId = "",
                    ),
                )
                "removeWaypoints" -> navi.removeWaypoint(WaypointIndex.ALL)
                "trip" -> navi.requestTrip(TripRequest(latitude = lat, longitude = lon, poiId = "", poiName = name, poiSubId = ""))
                "places" -> { navi.getBookmarkInfo(); navi.getRecentDestinationInfo() }
                "cancel" -> navi.cancelRoute()
                "stop" -> stopSelf()
                else -> Unit
            }
        }.onFailure { ProbeLog.add("CMD $a failed: $it") }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(poll)
        runCatching { navi.removeListener(listener); navi.release() }
        ProbeLog.add("service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CH = "crew"
        const val POLL_MS = 3_000L
    }
}

/** 화면 표시용 최근 로그 + logcat(Argo/Probe). */
object ProbeLog {
    private val lines = ArrayDeque<String>()
    private var last: String? = null

    @Synchronized
    fun add(line: String) {
        // 같은 조회 결과 반복은 logcat에 시각만 남기고 화면 버퍼에는 한 번만 넣는다
        Log.i("Argo/Probe", line)
        if (line == last) return
        last = line
        lines.addLast("%tT %s".format(System.currentTimeMillis(), line))
        while (lines.size > 200) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}
