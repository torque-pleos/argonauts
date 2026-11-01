package com.zigae.argonauts.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    private val namiseom = Place("남이섬", LatLon(37.7905, 127.5258))

    @Test fun memberRoundTrip() {
        val m = Member(
            id = "a1", name = "건우", colorIndex = 2, kind = MemberKind.CAR, at = LatLon(37.41, 127.09), heading = 45.0,
            status = MemberStatus.DRIVING, destination = namiseom, remainM = 78_000, remainS = 4_600,
            waypoint = Place("가평 휴게소", LatLon(37.70, 127.54)), waypointRemainS = 3_000, updatedAt = 1_000L,
        )
        val back = Member.from(JSONObject(m.toJson().toString()))!!
        assertEquals(m, back)
        assertEquals(1_000L + 4_600_000L, back.etaMs)
    }

    /** 휴대폰 웹이 보내는 최소 필드(위치 없음)도 읽혀야 한다. */
    @Test fun webMemberWithoutLocation() {
        val m = Member.from(JSONObject("""{"id":"w1","name":"서연","color":3,"kind":"WEB","status":"STOPPED","updatedAt":5}"""))!!
        assertEquals(MemberKind.WEB, m.kind)
        assertNull(m.at)
        assertNull(m.remainS)
    }

    @Test fun requestRoundTripAndExpiry() {
        val r = DestRequest("r1", "w1", "서연", namiseom, RequestStatus.PENDING, createdAt = 10_000)
        val back = DestRequest.from(JSONObject(r.toJson().toString()))!!
        assertEquals(r, back)
        assertEquals(10_000 + DestRequest.TTL_MS, back.expiresAt)
    }

    @Test fun metaRoundTrip() {
        val meta = CrewMeta("P5DNK2", "가평 캠핑", "a1", namiseom, createdAt = 1, updatedAt = 2)
        assertEquals(meta, CrewMeta.from(JSONObject(meta.toJson().toString())))
    }

    @Test fun placeSameWithinTolerance() {
        assertTrue(namiseom.sameAs(Place("남이섬 선착장", LatLon(37.7915, 127.5258))))
        assertTrue(!namiseom.sameAs(Place("자라섬", LatLon(37.8186, 127.5232))))
    }
}
