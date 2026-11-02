package com.zigae.argonauts.core

/**
 * 크루원 예상 경로 모양. 동기화 데이터를 작게 유지하려고 단순화 + Google polyline(정밀도 5) 문자열로 보낸다.
 * 내비 SDK는 경로 좌표를 주지 않아서, 같은 출발·경유·도착으로 다시 계산한 "예상 경로"다.
 */
object RouteShape {
    const val MAX_POINTS = 180

    fun encode(points: List<LatLon>): String {
        val sb = StringBuilder()
        var pLat = 0L; var pLon = 0L
        for (p in points) {
            val lat = Math.round(p.lat * 1e5); val lon = Math.round(p.lon * 1e5)
            enc(lat - pLat, sb); enc(lon - pLon, sb)
            pLat = lat; pLon = lon
        }
        return sb.toString()
    }

    private fun enc(v: Long, sb: StringBuilder) {
        var x = if (v < 0) (v shl 1).inv() else v shl 1
        while (x >= 0x20) { sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar()); x = x shr 5 }
        sb.append((x + 63).toInt().toChar())
    }

    fun decode(s: String): List<LatLon> {
        val out = ArrayList<LatLon>()
        var i = 0; var lat = 0L; var lon = 0L
        fun next(): Long? {
            var shift = 0; var result = 0L
            while (true) {
                if (i >= s.length) return null
                val b = s[i++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
                if (b < 0x20) break
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (i < s.length) {
            lat += next() ?: break
            lon += next() ?: break
            out += LatLon(lat / 1e5, lon / 1e5)
        }
        return out
    }

    /** Douglas–Peucker. 점이 [MAX_POINTS] 이하가 될 때까지 허용 오차를 키운다. */
    fun simplify(points: List<LatLon>, startToleranceM: Double = 25.0): List<LatLon> {
        if (points.size <= 2) return points
        var tol = startToleranceM
        var res = dp(points, tol)
        while (res.size > MAX_POINTS) { tol *= 1.6; res = dp(points, tol) }
        return res
    }

    private fun dp(pts: List<LatLon>, tol: Double): List<LatLon> {
        val keep = BooleanArray(pts.size); keep[0] = true; keep[pts.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to pts.size - 1) }
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var maxD = 0.0; var idx = -1
            for (i in a + 1 until b) {
                val d = segDistM(pts[i], pts[a], pts[b])
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx >= 0 && maxD > tol) { keep[idx] = true; stack.add(a to idx); stack.add(idx to b) }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    /** 점에서 선분까지 거리(m), 짧은 구간이라 평면 근사. */
    fun segDistM(p: LatLon, a: LatLon, b: LatLon): Double {
        val k = Math.cos(Math.toRadians(p.lat))
        val ax = a.lon * k; val ay = a.lat; val bx = b.lon * k; val by = b.lat; val px = p.lon * k; val py = p.lat
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val qx = ax + t * dx; val qy = ay + t * dy
        return Math.hypot(px - qx, py - qy) * 111_320.0
    }

    /** 경로에서 [pos]와 가장 가까운 지점 이후만 (지나온 구간 제외). */
    fun remaining(points: List<LatLon>, pos: LatLon?): List<LatLon> {
        if (pos == null || points.size < 2) return points
        var best = 0; var bestD = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val d = segDistM(pos, points[i], points[i + 1])
            if (d < bestD) { bestD = d; best = i }
        }
        return listOf(pos) + points.subList(best + 1, points.size)
    }

    /** 위치가 경로에서 얼마나 벗어났는지(m). */
    fun offRouteM(points: List<LatLon>, pos: LatLon): Double =
        if (points.size < 2) Double.MAX_VALUE
        else (0 until points.size - 1).minOf { segDistM(pos, points[it], points[it + 1]) }
}
