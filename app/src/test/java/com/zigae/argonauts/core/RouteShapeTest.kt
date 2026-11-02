package com.zigae.argonauts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteShapeTest {
    @Test fun encodeKnownValue() {
        // Google polyline 문서 예시
        val pts = listOf(LatLon(38.5, -120.2), LatLon(40.7, -120.95), LatLon(43.252, -126.453))
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", RouteShape.encode(pts))
        val back = RouteShape.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, back.size)
        assertEquals(43.252, back[2].lat, 1e-5)
        assertEquals(-126.453, back[2].lon, 1e-5)
    }

    @Test fun simplifyCapsPointsAndKeepsEnds() {
        val pts = (0..2000).map { LatLon(37.4 + it * 0.0002, 127.1 + Math.sin(it / 30.0) * 0.01) }
        val s = RouteShape.simplify(pts)
        assertTrue(s.size <= RouteShape.MAX_POINTS)
        assertEquals(pts.first(), s.first())
        assertEquals(pts.last(), s.last())
    }

    @Test fun remainingDropsPassedPart() {
        val pts = listOf(LatLon(37.0, 127.0), LatLon(37.1, 127.0), LatLon(37.2, 127.0), LatLon(37.3, 127.0))
        val r = RouteShape.remaining(pts, LatLon(37.15, 127.0))
        assertEquals(3, r.size)
        assertEquals(37.15, r.first().lat, 1e-9)
        assertTrue(RouteShape.offRouteM(pts, LatLon(37.15, 127.01)) in 800.0..1000.0)
    }

    @Test fun memberJsonCarriesRoute() {
        val route = listOf(LatLon(37.41272, 127.09468), LatLon(37.5, 127.2), LatLon(37.7905, 127.5258))
        val m = Member("a", "건우", 0, MemberKind.CAR, route.first(), route = route)
        val back = Member.from(m.toJson())!!
        assertEquals(3, back.route.size)
        assertEquals(127.5258, back.route.last().lon, 1e-5)
        assertTrue(Member.from(Member("b", "x", 1, MemberKind.WEB, null).toJson())!!.route.isEmpty())
    }
}
