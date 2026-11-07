package com.zigae.argonauts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class MeetPlannerTest {
    private val dest = LatLon(37.7905, 127.5258) // 남이섬
    private val pangyo = LatLon(37.41272, 127.09468)
    private val jamsil = LatLon(37.5133, 127.1001)

    @Test fun candidatesKeepOnlySpotsOnTheWay() {
        val onWay = Place("대성휴게소", LatLon(37.685, 127.3791))
        val behind = Place("기흥휴게소", LatLon(37.25, 127.10))
        val nearDest = Place("목적지 앞", LatLon(37.79, 127.52))
        val got = MeetPlanner.candidates(listOf(behind, onWay, nearDest), listOf(pangyo, jamsil), dest)
        assertEquals(listOf(onWay), got)
    }

    @Test fun rankPrefersLessWaitingAndRejectsDetours() {
        // 두 명, 후보 3곳: 0 = 기다림 12분, 1 = 기다림 2분, 2 = 크게 돌아감, 3 = 목적지 코앞 (후보 4곳)
        val toSpot = listOf(doubleArrayOf(2820.0, 1500.0, 1000.0, 4300.0), doubleArrayOf(2100.0, 1380.0, 1000.0, 3600.0))
        val toDest = doubleArrayOf(4620.0, 4500.0)
        val spotToDest = doubleArrayOf(1800.0, 3120.0, 5000.0, 300.0)
        val r = MeetPlanner.rank(toSpot, toDest, spotToDest)
        assertEquals(listOf(1, 0), r.map { it.index })
        assertEquals(120, r[0].waitS)
        assertEquals(0, r[1].detourS)
    }

    @Test fun planJsonRoundTripAndMemberEta() {
        val spot = MeetSpot(MeetSpot.idOf(LatLon(37.685, 127.3791)), Place("대성휴게소", LatLon(37.685, 127.3791)), mapOf("a" to 2820, "b" to 2100), 30)
        val back = MeetPlan.from(JSONObject(MeetPlan(listOf(spot), 1L).toJson().toString()))!!
        assertEquals(spot, back.spots.single())
        assertEquals(720, back.spots.single().waitS)
        val m = Member("a", "하늘", 0, MemberKind.CAR, pangyo, meetEta = mapOf(spot.id to 2900))
        assertEquals(mapOf(spot.id to 2900), Member.from(JSONObject(m.toJson().toString()))!!.meetEta)
    }

    @Test fun bundledRestAreasParse() {
        val list = MeetPlanner.parseRestAreas("""[["가평 휴게소 (하행)",37.7024,127.5462],["대성휴게소",37.685,127.3791]]""")
        assertEquals(2, list.size)
        assertTrue(list.all { it.name.contains("휴게소") })
    }
}
