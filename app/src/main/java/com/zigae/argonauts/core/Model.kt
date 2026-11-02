package com.zigae.argonauts.core

import org.json.JSONObject

data class LatLon(val lat: Double, val lon: Double) {
    fun toJson(): JSONObject = JSONObject().put("lat", lat).put("lon", lon)

    companion object {
        fun from(o: JSONObject?): LatLon? {
            if (o == null || !o.has("lat") || !o.has("lon")) return null
            return LatLon(o.getDouble("lat"), o.getDouble("lon"))
        }
    }
}

/** 목적지·경유지·검색 결과 등 이름이 있는 장소. */
data class Place(val name: String, val at: LatLon, val address: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("lat", at.lat).put("lon", at.lon).put("address", address ?: "")

    fun sameAs(other: Place?, toleranceM: Double = 300.0): Boolean = other != null && Geo.distanceM(at, other.at) <= toleranceM

    companion object {
        fun from(o: JSONObject?): Place? {
            val at = LatLon.from(o) ?: return null
            return Place(o!!.optString("name", "").ifBlank { "지정 위치" }, at, o.optString("address").ifBlank { null })
        }
    }
}

enum class MemberKind { CAR, WEB }

enum class MemberStatus(val label: String) {
    DRIVING("주행 중"),
    STOPPED("정차 중"),
    ARRIVED("도착"),
    OFFLINE("연결 끊김");

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.name == s } ?: STOPPED
    }
}

/** 크루원 한 명의 실시간 상태. 동기화 키 `m/{id}`에 그대로 실린다. */
data class Member(
    val id: String,
    val name: String,
    val colorIndex: Int,
    val kind: MemberKind,
    val at: LatLon?,
    val heading: Double = 0.0,
    val status: MemberStatus = MemberStatus.STOPPED,
    val destination: Place? = null,
    /** 목적지까지 남은 거리(m)·시간(초). 경로가 없으면 null. */
    val remainM: Int? = null,
    val remainS: Int? = null,
    val waypoint: Place? = null,
    val waypointRemainS: Int? = null,
    val arrivedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val simulated: Boolean = false,
    /** 예상 경로(현재 위치 → 경유지 → 목적지), 단순화한 좌표. 없으면 빈 목록. */
    val route: List<LatLon> = emptyList(),
) {
    val initial: String get() = name.trim().take(1).ifBlank { "?" }

    /** 도착 예정 시각(epoch ms). */
    val etaMs: Long? get() = remainS?.let { updatedAt + it * 1000L }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("color", colorIndex); put("kind", kind.name)
        at?.let { put("at", it.toJson()) }
        put("heading", heading); put("status", status.name)
        destination?.let { put("dest", it.toJson()) }
        remainM?.let { put("remainM", it) }
        remainS?.let { put("remainS", it) }
        waypoint?.let { put("wp", it.toJson()) }
        waypointRemainS?.let { put("wpRemainS", it) }
        arrivedAt?.let { put("arrivedAt", it) }
        put("updatedAt", updatedAt); put("sim", simulated)
        if (route.size >= 2) put("route", RouteShape.encode(route))
    }

    companion object {
        fun from(o: JSONObject): Member? = runCatching {
            Member(
                id = o.getString("id"),
                name = o.optString("name", "크루원"),
                colorIndex = o.optInt("color", 0),
                kind = if (o.optString("kind") == "WEB") MemberKind.WEB else MemberKind.CAR,
                at = LatLon.from(o.optJSONObject("at")),
                heading = o.optDouble("heading", 0.0),
                status = MemberStatus.of(o.optString("status")),
                destination = Place.from(o.optJSONObject("dest")),
                remainM = if (o.has("remainM")) o.getInt("remainM") else null,
                remainS = if (o.has("remainS")) o.getInt("remainS") else null,
                waypoint = Place.from(o.optJSONObject("wp")),
                waypointRemainS = if (o.has("wpRemainS")) o.getInt("wpRemainS") else null,
                arrivedAt = if (o.has("arrivedAt")) o.getLong("arrivedAt") else null,
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                simulated = o.optBoolean("sim", false),
                route = o.optString("route").takeIf { it.isNotBlank() }?.let(RouteShape::decode).orEmpty(),
            )
        }.getOrNull()
    }
}

/** 크루 공통 정보. 리더만 쓴다. 동기화 키 `meta`. */
data class CrewMeta(
    val code: String,
    val name: String,
    val leaderId: String,
    val destination: Place?,
    val createdAt: Long,
    val closedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("code", code); put("name", name); put("leaderId", leaderId)
        destination?.let { put("dest", it.toJson()) }
        put("createdAt", createdAt); closedAt?.let { put("closedAt", it) }; put("updatedAt", updatedAt)
    }

    companion object {
        fun from(o: JSONObject): CrewMeta? = runCatching {
            CrewMeta(
                code = o.getString("code"),
                name = o.optString("name", "크루"),
                leaderId = o.getString("leaderId"),
                destination = Place.from(o.optJSONObject("dest")),
                createdAt = o.optLong("createdAt"),
                closedAt = if (o.has("closedAt")) o.getLong("closedAt") else null,
                updatedAt = o.optLong("updatedAt"),
            )
        }.getOrNull()
    }
}

enum class RequestStatus { PENDING, ACCEPTED, DECLINED, EXPIRED }

/** 목적지 변경 요청. 크루당 동시에 1건만. 동기화 키 `req`. */
data class DestRequest(
    val id: String,
    val fromId: String,
    val fromName: String,
    val place: Place,
    val status: RequestStatus,
    val createdAt: Long,
    val decidedAt: Long? = null,
) {
    val expiresAt: Long get() = createdAt + TTL_MS

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("fromId", fromId); put("fromName", fromName); put("place", place.toJson())
        put("status", status.name); put("createdAt", createdAt); decidedAt?.let { put("decidedAt", it) }
    }

    companion object {
        const val TTL_MS = 3 * 60_000L

        fun from(o: JSONObject): DestRequest? = runCatching {
            DestRequest(
                id = o.getString("id"),
                fromId = o.getString("fromId"),
                fromName = o.optString("fromName", "크루원"),
                place = Place.from(o.getJSONObject("place"))!!,
                status = RequestStatus.valueOf(o.optString("status", "PENDING")),
                createdAt = o.getLong("createdAt"),
                decidedAt = if (o.has("decidedAt")) o.getLong("decidedAt") else null,
            )
        }.getOrNull()
    }
}

/** 화면 오른쪽에 쌓이는 한 번 탭 카드. 주행 중에도 단일 탭으로 응답 가능. */
sealed class Prompt(val key: String) {
    /** 크루 목적지와 내 내비 목적지가 다를 때. */
    data class FollowDestination(val place: Place, val changed: Boolean) : Prompt("follow:${place.name}:${place.at.lat}")

    /** 다른 크루원이 경유지를 추가했을 때. */
    data class JoinWaypoint(val member: Member, val place: Place) : Prompt("wp:${member.id}:${place.name}")

    /** (리더) 목적지 변경 요청 승인. */
    data class ApproveRequest(val request: DestRequest) : Prompt("req:${request.id}")

    /** (요청자) 내 요청의 진행 상황. */
    data class MyRequest(val request: DestRequest) : Prompt("myreq:${request.id}:${request.status}")
}
