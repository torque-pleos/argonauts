package com.zigae.argonauts.engine

import android.content.Context
import android.util.Log
import com.zigae.argonauts.core.MeetPlan
import com.zigae.argonauts.core.MeetPlanner
import com.zigae.argonauts.core.MeetSpot
import com.zigae.argonauts.core.Member
import com.zigae.argonauts.core.MemberStatus
import com.zigae.argonauts.core.Place
import com.zigae.argonauts.net.Routing

/**
 * 모이기 좋은 휴게소 계산 (리더 기기).
 * - 후보: 앱에 넣은 전국 고속도로 휴게소 목록 (`assets/rest_areas.json`, OpenStreetMap `highway=services`).
 * - 소요 시간: OSRM 행렬 1회 요청. 크루원이 자기 내비로 계산해 보낸 값(`Member.meetEta`, 교통 반영)이 있으면 그 값을 쓴다.
 */
class MeetFinder(private val context: Context) {
    private val restAreas: List<Place> by lazy {
        runCatching { MeetPlanner.parseRestAreas(context.assets.open("rest_areas.json").bufferedReader().use { it.readText() }) }
            .onFailure { Log.w(TAG, "rest areas load failed", it) }
            .getOrDefault(emptyList())
    }

    /** @return 추천 목록, 계산할 수 없으면(크루원 2명 미만·네트워크 실패) null */
    suspend fun plan(dest: Place, members: List<Member>): MeetPlan? {
        val ps = members.filter { it.at != null && it.status != MemberStatus.ARRIVED && it.status != MemberStatus.OFFLINE }
        if (ps.size < 2) return null
        val cands = MeetPlanner.candidates(restAreas, ps.map { it.at!! }, dest.at)
        if (cands.isEmpty()) return MeetPlan(emptyList())
        val t = Routing.table(ps.map { it.at!! } + cands.map { it.at }, cands.map { it.at } + dest.at) ?: return null
        val nc = cands.size
        val ids = cands.map { MeetSpot.idOf(it.at) }
        val toSpot = ps.mapIndexed { i, m ->
            DoubleArray(nc) { j -> m.meetEta[ids[j]]?.toDouble() ?: t[i][j] }
        }
        val toDest = DoubleArray(ps.size) { i ->
            val m = ps[i]
            if (m.remainS != null && m.destination?.sameAs(dest) == true) m.remainS.toDouble() else t[i][nc]
        }
        val spotToDest = DoubleArray(nc) { j -> t[ps.size + j][nc] }
        val ranked = MeetPlanner.rank(toSpot, toDest, spotToDest)
        Log.i(TAG, "meet: ${ps.size} members, ${nc} candidates -> " + ranked.joinToString { "${cands[it.index].name}(wait ${it.waitS / 60}m, detour ${it.detourS / 60}m)" })
        return MeetPlan(ranked.map { r ->
            MeetSpot(ids[r.index], cands[r.index], ps.indices.associate { i -> ps[i].id to r.etaS[i] }, r.detourS)
        })
    }

    private companion object { const val TAG = "Argo/Meet" }
}
