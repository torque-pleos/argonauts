package com.zigae.argonauts.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val R = 6_371_000.0

    fun distanceM(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }

    fun bearing(a: LatLon, b: LatLon): Double {
        val y = sin(Math.toRadians(b.lon - a.lon)) * cos(Math.toRadians(b.lat))
        val x = cos(Math.toRadians(a.lat)) * sin(Math.toRadians(b.lat)) -
            sin(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * cos(Math.toRadians(b.lon - a.lon))
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun lerp(a: LatLon, b: LatLon, t: Double) = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
}

object Fmt {
    private val clock = SimpleDateFormat("HH:mm", Locale.KOREA)

    fun km(m: Number): String {
        val v = m.toDouble()
        return when {
            v < 950 -> "${(v / 10).roundToInt() * 10}m"
            v < 10_000 -> String.format(Locale.KOREA, "%.1fkm", v / 1000)
            else -> "${(v / 1000).roundToInt()}km"
        }
    }

    fun duration(sec: Int): String {
        val min = (sec + 59) / 60
        return if (min < 60) "${min}분" else "${min / 60}시간 ${min % 60}분".replace(" 0분", "")
    }

    fun clock(epochMs: Long): String = clock.format(Date(epochMs))

    /** 같은 목적지로 가는 두 사람의 앞/뒤 간격. 양수면 상대가 뒤. */
    fun gap(otherRemainM: Int, myRemainM: Int): String {
        val d = otherRemainM - myRemainM
        return when {
            abs(d) < 300 -> "바로 옆"
            d > 0 -> "${km(d)} 뒤"
            else -> "${km(-d)} 앞"
        }
    }
}
