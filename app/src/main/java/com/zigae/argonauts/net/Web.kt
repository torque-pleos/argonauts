package com.zigae.argonauts.net

import android.util.Log
import com.zigae.argonauts.core.Geo
import com.zigae.argonauts.core.LatLon
import com.zigae.argonauts.core.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TAG = "Argo/Web"

private suspend fun getJson(url: String): JSONObject? = withContext(Dispatchers.IO) {
    runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "Argonauts/1.0 (Pleos contest app)")
        c.inputStream.bufferedReader().use { JSONObject(it.readText()) }
    }.onFailure { Log.w(TAG, "GET failed $url: ${it.message}") }.getOrNull()
}

data class RouteLine(val points: List<LatLon>, val distanceM: Double, val durationS: Double)

/** 경로·소요 시간 (OSRM 공개 서버). 시연 차량 이동, 모이기 좋은 휴게소 계산에 쓴다. */
object Routing {
    /** 소요 시간 행렬(초) [출발][도착]. 경로가 없으면 NaN, 요청 실패 시 null. */
    suspend fun table(from: List<LatLon>, to: List<LatLon>): List<DoubleArray>? {
        val coords = (from + to).joinToString(";") { "${it.lon},${it.lat}" }
        val src = from.indices.joinToString(";")
        val dst = to.indices.joinToString(";") { (it + from.size).toString() }
        val json = getJson("https://router.project-osrm.org/table/v1/driving/$coords?sources=$src&destinations=$dst&annotations=duration") ?: return null
        val rows = json.optJSONArray("durations") ?: return null
        return (0 until rows.length()).map { i ->
            val r = rows.getJSONArray(i)
            DoubleArray(r.length()) { j -> if (r.isNull(j)) Double.NaN else r.getDouble(j) }
        }
    }

    suspend fun route(stops: List<LatLon>): RouteLine {
        val coords = stops.joinToString(";") { "${it.lon},${it.lat}" }
        val json = getJson("https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=geojson")
        val r = json?.optJSONArray("routes")?.optJSONObject(0)
        if (r != null) {
            val arr = r.getJSONObject("geometry").getJSONArray("coordinates")
            val pts = (0 until arr.length()).map { arr.getJSONArray(it).let { p -> LatLon(p.getDouble(1), p.getDouble(0)) } }
            if (pts.size >= 2) return RouteLine(pts, r.getDouble("distance"), r.getDouble("duration"))
        }
        val dist = stops.zipWithNext { a, b -> Geo.distanceM(a, b) }.sum() * 1.3
        return RouteLine(stops, dist, dist / (60_000.0 / 3600))
    }
}

/** 장소 검색 (Photon, OSM 기반·키 불필요). 정차 중에만 사용. */
object PlaceSearch {
    suspend fun search(query: String, near: LatLon?, osmTag: String? = null, limit: Int = 10): List<Place> {
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query, "UTF-8")
        val bias = near?.let { "&lat=${it.lat}&lon=${it.lon}&location_bias_scale=0.5" } ?: ""
        val tag = osmTag?.let { "&osm_tag=$it" } ?: ""
        val json = getJson("https://photon.komoot.io/api/?q=$q&limit=$limit$bias$tag") ?: return emptyList()
        val features = json.optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i ->
            val f = features.getJSONObject(i)
            val p = f.getJSONObject("properties")
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            val name = p.optString("name").ifBlank { return@mapNotNull null }
            val addr = listOf("state", "city", "district", "street").mapNotNull { k -> p.optString(k).takeIf { it.isNotBlank() } }.joinToString(" ")
            Place(name, LatLon(c.getDouble(1), c.getDouble(0)), addr.ifBlank { null })
        }.distinctBy { it.name + it.address }
    }
}
