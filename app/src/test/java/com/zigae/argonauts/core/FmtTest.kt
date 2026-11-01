package com.zigae.argonauts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FmtTest {
    @Test fun km() {
        assertEquals("340m", Fmt.km(338))
        assertEquals("1.0km", Fmt.km(995))
        assertEquals("3.4km", Fmt.km(3_420))
        assertEquals("78km", Fmt.km(78_400))
    }

    @Test fun duration() {
        assertEquals("1분", Fmt.duration(30))
        assertEquals("50분", Fmt.duration(3_000))
        assertEquals("1시간 17분", Fmt.duration(4_600))
        assertEquals("2시간", Fmt.duration(7_200))
    }

    /** 같은 목적지까지 남은 거리 차이로 앞/뒤를 표시한다. */
    @Test fun gap() {
        assertEquals("1.9km 뒤", Fmt.gap(otherRemainM = 79_900, myRemainM = 78_000))
        assertEquals("3.4km 앞", Fmt.gap(otherRemainM = 74_600, myRemainM = 78_000))
        assertEquals("바로 옆", Fmt.gap(otherRemainM = 78_100, myRemainM = 78_000))
    }

    @Test fun distance() {
        val d = Geo.distanceM(LatLon(37.41272, 127.09468), LatLon(37.7905, 127.5258))
        assertTrue(d in 55_000.0..58_000.0)
    }
}
