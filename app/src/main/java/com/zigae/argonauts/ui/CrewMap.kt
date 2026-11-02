package com.zigae.argonauts.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.zigae.argonauts.core.LatLon
import com.zigae.argonauts.core.Member
import com.zigae.argonauts.core.MemberStatus
import com.zigae.argonauts.core.Place
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/** 지도 위 표시 대상 전체. */
data class MapModel(
    val members: List<Member>,
    val myId: String,
    val destination: Place?,
    val waypoints: List<Pair<Place, Int>>, // 장소, 크루원 색
    val pendingPlace: Place?,
    val picked: Place?,
    val driving: Boolean,
    val night: Boolean,
    val fitRequest: Int,
    val focusId: String?,
    /** 모이기 좋은 휴게소 (좋은 순)와 선택한 곳. */
    val spots: List<Place> = emptyList(),
    val spotIndex: Int = 0,
)

/** OSM 표준 타일 (osmdroid 기본, 앱 식별 User-Agent 필수). 주간은 채도를 낮춰 간결하게, 야간은 반전. */
private val dayFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.45f) })
private val nightFilter = ColorMatrixColorFilter(
    ColorMatrix(floatArrayOf(
        -0.75f, 0f, 0f, 0f, 205f,
        0f, -0.75f, 0f, 0f, 210f,
        0f, 0f, -0.70f, 0f, 220f,
        0f, 0f, 0f, 1f, 0f,
    )).apply { postConcat(ColorMatrix().apply { setSaturation(0.2f) }) },
)

private class MapHolder(val view: MapView) {
    val markers = HashMap<String, Marker>()
    val iconKeys = HashMap<String, String>()
    var fitted = -1
    var night: Boolean? = null
    /** 정차 중 사용자가 지도를 움직이면 자동 맞춤을 멈춘다 ('전체 보기'로 재개). */
    var autoFit = true
    var lastPointCount = 0
}

/**
 * 크루 실시간 지도 (osmdroid + OpenStreetMap 타일).
 * - 주행 중(간결 모드): 크루 전원·목적지가 들어오게 범위를 자동 고정, 터치 조작 없음, 이동 애니메이션 없음.
 * - 정차 중: 자유 이동·확대, 길게 눌러 목적지 후보 선택.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun CrewMap(
    model: MapModel,
    colors: ArgoColors,
    onLongPress: (LatLon) -> Unit,
    onMarkerTap: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSpotTap: (Int) -> Unit = {},
) {
    val holder = remember { arrayOfNulls<MapHolder>(1) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).apply {
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                isTilesScaledToDpi = true
                minZoomLevel = 5.0
                maxZoomLevel = 18.0
                controller.setZoom(11.0)
                controller.setCenter(GeoPoint(37.41272, 127.09468))
                overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?) = false
                    override fun longPressHelper(p: GeoPoint?): Boolean {
                        p ?: return false
                        holder[0]?.view?.tag.let { if (it == "driving") return false }
                        onLongPress(LatLon(p.latitude, p.longitude)); return true
                    }
                }))
                holder[0] = MapHolder(this)
            }
        },
        update = { view ->
            val h = holder[0] ?: return@AndroidView
            render(h, model, colors, view.context, onMarkerTap, onSpotTap)
        },
    )
}

@SuppressLint("ClickableViewAccessibility")
private fun render(h: MapHolder, m: MapModel, colors: ArgoColors, ctx: Context, onMarkerTap: (String) -> Unit, onSpotTap: (Int) -> Unit) {
    val map = h.view
    if (h.night != m.night) {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.overlayManager.tilesOverlay.setColorFilter(if (m.night) nightFilter else dayFilter)
        map.overlayManager.tilesOverlay.loadingBackgroundColor = if (m.night) 0xFF1A2230.toInt() else 0xFFEDEFF2.toInt()
        h.night = m.night
        h.iconKeys.clear()
    }
    // 주행 중: 터치 조작 차단(간결 모드)
    map.tag = if (m.driving) "driving" else "parked"
    if (m.driving) map.setOnTouchListener { _, _ -> true }
    else map.setOnTouchListener { _, e -> if (e.actionMasked == android.view.MotionEvent.ACTION_MOVE) h.autoFit = false; false }

    val wanted = HashMap<String, Pair<GeoPoint, () -> Bitmap>>()
    m.members.forEach { mem ->
        val at = mem.at ?: return@forEach
        val color = if (mem.status == MemberStatus.OFFLINE) 0xFF9AA3B2.toInt() else colors.crewColor(mem.colorIndex).toArgb()
        val key = "${mem.name}|$color|${mem.status}|${mem.id == m.myId}|${m.focusId == mem.id}|${m.night}"
        wanted["m:${mem.id}"] = GeoPoint(at.lat, at.lon) to { memberIcon(ctx, mem.initial, mem.name, color, mem.id == m.myId, mem.status == MemberStatus.ARRIVED, m.focusId == mem.id, m.night) }
        h.iconKeys["m:${mem.id}#want"] = key
    }
    m.destination?.let { d ->
        wanted["dest"] = GeoPoint(d.at.lat, d.at.lon) to { pinIcon(ctx, d.name, colors.primary.toArgb(), colors.onPrimary.toArgb(), "목적지", m.night) }
        h.iconKeys["dest#want"] = "${d.name}|${m.night}"
    }
    m.waypoints.forEachIndexed { i, (p, c) ->
        wanted["wp:$i"] = GeoPoint(p.at.lat, p.at.lon) to { pinIcon(ctx, p.name, colors.crewColor(c).toArgb(), 0xFFFFFFFF.toInt(), "경유", m.night) }
        h.iconKeys["wp:$i#want"] = "${p.name}|$c|${m.night}"
    }
    m.spots.forEachIndexed { i, p ->
        if (m.waypoints.any { it.first.sameAs(p) }) return@forEachIndexed // 이미 경유지 핀으로 표시
        val sel = i == m.spotIndex
        val color = if (sel) colors.success.toArgb() else 0xFF7A8496.toInt()
        wanted["spot:$i"] = GeoPoint(p.at.lat, p.at.lon) to { pinIcon(ctx, p.name, color, 0xFFFFFFFF.toInt(), if (sel) "모이기" else "후보", m.night) }
        h.iconKeys["spot:$i#want"] = "${p.name}|$sel|${m.night}"
    }
    m.pendingPlace?.let { p ->
        wanted["req"] = GeoPoint(p.at.lat, p.at.lon) to { pinIcon(ctx, p.name, colors.warn.toArgb(), 0xFFFFFFFF.toInt(), "변경 요청", m.night) }
        h.iconKeys["req#want"] = "${p.name}|${m.night}"
    }
    m.picked?.let { p ->
        wanted["pick"] = GeoPoint(p.at.lat, p.at.lon) to { pinIcon(ctx, p.name, colors.accent.toArgb(), 0xFFFFFFFF.toInt(), "선택", m.night) }
        h.iconKeys["pick#want"] = "${p.name}|${p.at.lat}|${m.night}"
    }

    // 제거
    (h.markers.keys - wanted.keys).forEach { k ->
        map.overlays.remove(h.markers.remove(k)); h.iconKeys.remove(k)
    }
    // 추가·갱신 (위치는 즉시 이동, 애니메이션 없음)
    wanted.forEach { (k, v) ->
        val (gp, icon) = v
        val marker = h.markers.getOrPut(k) {
            Marker(map).apply {
                setAnchor(Marker.ANCHOR_CENTER, if (k.startsWith("m:")) 0.32f else 1.0f)
                setInfoWindow(null)
                when {
                    k.startsWith("m:") -> k.removePrefix("m:").let { id -> setOnMarkerClickListener { _, _ -> onMarkerTap(id); true } }
                    k.startsWith("spot:") -> k.removePrefix("spot:").toInt().let { i -> setOnMarkerClickListener { _, _ -> onSpotTap(i); true } }
                    else -> setOnMarkerClickListener { _, _ -> true }
                }
                map.overlays.add(this)
            }
        }
        marker.position = gp
        val want = h.iconKeys["$k#want"]
        if (want != h.iconKeys[k]) {
            marker.icon = BitmapDrawable(ctx.resources, icon())
            h.iconKeys[k] = want ?: ""
        }
    }
    // 선택한 휴게소 핀은 다른 핀 위에, 내 마커·크루원은 모든 핀 위에 오도록 순서 정리
    h.markers["spot:${m.spotIndex}"]?.let { map.overlays.remove(it); map.overlays.add(it) }
    h.markers.filterKeys { it.startsWith("m:") }.values.forEach { map.overlays.remove(it); map.overlays.add(it) }
    h.markers["m:${m.myId}"]?.let { map.overlays.remove(it); map.overlays.add(it) }

    // 범위 맞춤: 주행 중엔 매번, 정차 중엔 요청 시에만
    val pts = m.members.mapNotNull { it.at } + listOfNotNull(m.destination?.at)
    if (h.fitted != m.fitRequest) h.autoFit = true
    val outside = map.width > 0 && pts.any { !map.boundingBox.increaseByScale(0.85f).contains(it.lat, it.lon) }
    val needFit = m.driving || h.fitted != m.fitRequest || (h.autoFit && m.focusId == null && (outside || pts.size != h.lastPointCount))
    if (needFit && pts.isNotEmpty()) {
        h.fitted = m.fitRequest
        h.lastPointCount = pts.size
        map.post { fit(map, pts, m.focusId?.let { id -> m.members.firstOrNull { it.id == id }?.at }) }
    }
    map.invalidate()
}

private fun fit(map: MapView, pts: List<LatLon>, focus: LatLon?) {
    if (map.width == 0 || map.height == 0) return
    if (focus != null && !pts.isEmpty() && map.tag != "driving") {
        map.controller.setCenter(GeoPoint(focus.lat, focus.lon)); return
    }
    if (pts.size == 1 || pts.all { it == pts.first() }) {
        map.controller.setZoom(14.0)
        map.controller.setCenter(GeoPoint(pts.first().lat, pts.first().lon))
        return
    }
    val box = BoundingBox.fromGeoPoints(pts.map { GeoPoint(it.lat, it.lon) })
    val padLat = (box.latNorth - box.latSouth).coerceAtLeast(0.004) * 0.25
    val padLon = (box.lonEast - box.lonWest).coerceAtLeast(0.004) * 0.25
    val padded = BoundingBox(box.latNorth + padLat, box.lonEast + padLon, box.latSouth - padLat, box.lonWest - padLon)
    map.zoomToBoundingBox(padded, false)
}

// region 마커 그림

private fun dp(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density * UI_SCALE

private fun memberIcon(ctx: Context, initial: String, name: String, color: Int, me: Boolean, arrived: Boolean, focus: Boolean, night: Boolean): Bitmap {
    val r = dp(ctx, if (me) 26f else 22f)
    val ring = dp(ctx, if (focus) 6f else 4f)
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(ctx, 18f); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    val text = if (me) "$name (나)" else name
    val labelW = label.measureText(text) + dp(ctx, 20f)
    val w = maxOf((r + ring) * 2 + dp(ctx, 8f), labelW)
    val h = (r + ring) * 2 + dp(ctx, 40f)
    val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = w / 2; val cy = r + ring + dp(ctx, 2f)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    // 그림자
    p.color = 0x40000000; c.drawCircle(cx, cy + dp(ctx, 2f), r + ring, p)
    p.color = if (focus) 0xFFFFD54A.toInt() else 0xFFFFFFFF.toInt(); c.drawCircle(cx, cy, r + ring, p)
    p.color = color; c.drawCircle(cx, cy, r, p)
    if (arrived) {
        p.color = 0xFFFFFFFF.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = dp(ctx, 4f); p.strokeCap = Paint.Cap.ROUND
        val path = Path().apply { moveTo(cx - r * 0.45f, cy); lineTo(cx - r * 0.1f, cy + r * 0.35f); lineTo(cx + r * 0.5f, cy - r * 0.35f) }
        c.drawPath(path, p); p.style = Paint.Style.FILL
    } else {
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFFFFFFFF.toInt(); textSize = r * 1.05f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        c.drawText(initial, cx, cy - (t.descent() + t.ascent()) / 2, t)
    }
    // 이름표
    val ly = cy + r + ring + dp(ctx, 6f)
    val rect = RectF(cx - labelW / 2, ly, cx + labelW / 2, ly + dp(ctx, 28f))
    p.color = if (night) 0xE6121C2B.toInt() else 0xF2FFFFFF.toInt()
    c.drawRoundRect(rect, dp(ctx, 14f), dp(ctx, 14f), p)
    label.color = if (night) 0xFFEEF2F8.toInt() else 0xFF0E1726.toInt()
    c.drawText(text, cx, rect.centerY() - (label.descent() + label.ascent()) / 2, label)
    return bmp
}

private fun pinIcon(ctx: Context, name: String, color: Int, onColor: Int, tag: String, night: Boolean): Bitmap {
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(ctx, 18f); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.LEFT; this.color = onColor }
    val tagP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(ctx, 14f); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.LEFT; this.color = onColor; alpha = 210 }
    val text = name.take(16)
    val padH = dp(ctx, 14f)
    val bw = tagP.measureText(tag) + dp(ctx, 10f) + label.measureText(text) + padH * 2
    val bh = dp(ctx, 40f)
    val tail = dp(ctx, 12f)
    val bmp = Bitmap.createBitmap(bw.toInt() + 4, (bh + tail + dp(ctx, 8f)).toInt(), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    val rect = RectF(2f, 2f, bw, bh)
    c.drawRoundRect(rect, dp(ctx, 12f), dp(ctx, 12f), p)
    val path = Path().apply { moveTo(bw / 2 - tail * 0.7f, bh - 1); lineTo(bw / 2, bh + tail); lineTo(bw / 2 + tail * 0.7f, bh - 1); close() }
    c.drawPath(path, p)
    p.color = if (night) 0xFF0A111C.toInt() else 0xFFFFFFFF.toInt()
    c.drawCircle(bw / 2, bh + tail + dp(ctx, 3f), dp(ctx, 4f), p.apply { this.color = color })
    val baseY = rect.centerY() - (label.descent() + label.ascent()) / 2
    c.drawText(tag, padH, baseY, tagP)
    c.drawText(text, padH + tagP.measureText(tag) + dp(ctx, 10f), baseY, label)
    return bmp
}

// endregion
