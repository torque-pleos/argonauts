package com.zigae.argonauts.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** 모이기 좋은 휴게소 한 곳. [etaS]는 크루원 id → 그곳까지 걸리는 시간(초). */
data class MeetSpot(val id: String, val place: Place, val etaS: Map<String, Int>, val detourS: Int) {
    /** 먼저 도착한 크루원이 기다리는 최대 시간. */
    val waitS: Int get() = if (etaS.isEmpty()) 0 else etaS.values.max() - etaS.values.min()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("place", place.toJson()); put("detourS", detourS)
        put("eta", JSONObject().apply { etaS.forEach { (k, v) -> put(k, v) } })
    }

    companion object {
        fun idOf(at: LatLon) = String.format(Locale.US, "%.4f,%.4f", at.lat, at.lon)

        fun from(o: JSONObject): MeetSpot? = runCatching {
            val eta = o.optJSONObject("eta")
            MeetSpot(
                id = o.getString("id"),
                place = Place.from(o.getJSONObject("place"))!!,
                etaS = eta?.keys()?.asSequence()?.associateWith { eta.getInt(it) }.orEmpty(),
                detourS = o.optInt("detourS"),
            )
        }.getOrNull()
    }
}

/** 리더가 계산해 공유하는 추천 목록 (좋은 순). 동기화 키 `meet`. */
data class MeetPlan(val spots: List<MeetSpot>, val updatedAt: Long = System.currentTimeMillis()) {
    fun toJson(): JSONObject = JSONObject().put("spots", JSONArray().apply { spots.forEach { put(it.toJson()) } }).put("updatedAt", updatedAt)

    companion object {
        fun from(o: JSONObject): MeetPlan? = runCatching {
            val arr = o.getJSONArray("spots")
            MeetPlan((0 until arr.length()).mapNotNull { MeetSpot.from(arr.getJSONObject(it)) }, o.optLong("updatedAt"))
        }.getOrNull()
    }
}

/**
 * 크루원 위치 → 같은 목적지로 가는 길에 함께 들르기 좋은 휴게소 고르기.
 * 1) 직선거리로 후보를 줄이고 ([candidates]) 2) 실제 소요 시간 행렬로 점수를 매긴다 ([rank]).
 */
object MeetPlanner {
    const val MAX_CANDIDATES = 10
    const val MAX_SPOTS = 3
    /** 목적지에서 이보다 가까운 곳은 '중간'이 아니다. */
    const val MIN_SPOT_TO_DEST_S = 600.0

    /** 모두에게 대략 진행 방향 위에 있는 휴게소만, 덜 돌아가는 순으로. */
    fun candidates(all: List<Place>, members: List<LatLon>, dest: LatLon, max: Int = MAX_CANDIDATES): List<Place> {
        if (members.isEmpty()) return emptyList()
        return all.asSequence()
            .filter { Geo.distanceM(it.at, dest) > 5_000 }
            .mapNotNull { p ->
                var worst = 0.0
                for (m in members) {
                    val direct = Geo.distanceM(m, dest)
                    val via = Geo.distanceM(m, p.at) + Geo.distanceM(p.at, dest)
                    if (via > direct * 1.2 + 3_000) return@mapNotNull null
                    worst = maxOf(worst, via / direct.coerceAtLeast(1.0))
                }
                p to worst
            }
            .sortedBy { it.second }
            .take(max)
            .map { it.first }
            .toList()
    }

    data class Scored(val index: Int, val etaS: List<Int>, val waitS: Int, val detourS: Int, val score: Double)

    /**
     * @param toSpot [크루원][후보] 소요 시간(초), 모르면 NaN
     * @param toDest [크루원] 목적지까지 소요 시간(초)
     * @param spotToDest [후보] 후보 → 목적지 소요 시간(초)
     * 점수 = 기다리는 시간 + 1.5 × 가장 많이 돌아가는 시간 + 여정 중간(절반 지점)에서 벗어난 정도. 작을수록 좋다.
     */
    fun rank(toSpot: List<DoubleArray>, toDest: DoubleArray, spotToDest: DoubleArray, max: Int = MAX_SPOTS): List<Scored> {
        val n = toDest.size
        if (n == 0) return emptyList()
        return spotToDest.indices.mapNotNull { j ->
            val rest = spotToDest[j]
            if (rest.isNaN() || rest < MIN_SPOT_TO_DEST_S) return@mapNotNull null
            val eta = DoubleArray(n) { toSpot[it][j] }
            if (eta.any { it.isNaN() } || toDest.any { it.isNaN() || it <= 0 }) return@mapNotNull null
            var maxDetour = 0.0
            var midDev = 0.0
            for (i in 0 until n) {
                val detour = (eta[i] + rest - toDest[i]).coerceAtLeast(0.0)
                if (detour > maxOf(600.0, 0.15 * toDest[i])) return@mapNotNull null // 크게 돌아가거나 이미 지나친 곳
                maxDetour = maxOf(maxDetour, detour)
                midDev += kotlin.math.abs(eta[i] / toDest[i] - 0.5)
            }
            val wait = eta.max() - eta.min()
            Scored(j, eta.map { it.toInt() }, wait.toInt(), maxDetour.toInt(), wait + 1.5 * maxDetour + 1_200 * midDev / n)
        }.sortedBy { it.score }.take(max)
    }

    /** 앱에 넣은 휴게소 목록(JSON `[[이름, 위도, 경도], ...]`) 읽기. */
    fun parseRestAreas(json: String): List<Place> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i -> arr.getJSONArray(i).let { Place(it.getString(0), LatLon(it.getDouble(1), it.getDouble(2))) } }
    }
}
