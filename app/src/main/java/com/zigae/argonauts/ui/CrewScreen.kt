package com.zigae.argonauts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zigae.argonauts.core.Fmt
import com.zigae.argonauts.core.MeetSpot
import com.zigae.argonauts.core.Member
import com.zigae.argonauts.core.MemberKind
import com.zigae.argonauts.core.MemberStatus
import com.zigae.argonauts.core.Place
import com.zigae.argonauts.core.Prompt
import com.zigae.argonauts.core.RequestStatus
import com.zigae.argonauts.engine.CrewEngine
import com.zigae.argonauts.engine.CrewUi

@Composable
fun CrewScreen(engine: CrewEngine, ui: CrewUi) {
    val c = Argo.c
    var fit by remember { mutableIntStateOf(0) }
    var focusId by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<Place?>(null) }
    var detail by remember { mutableStateOf<Member?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var spotId by remember { mutableStateOf<String?>(null) }
    val spots = ui.meet?.spots.orEmpty()
    val spot = spots.firstOrNull { it.id == spotId } ?: spots.firstOrNull()

    // 주행이 시작되면 입력·상세 화면을 닫는다 (주행 중에는 단일 탭 카드만)
    LaunchedEffect(ui.driving) {
        if (ui.driving) { showPicker = false; showQr = false; detail = null; picked = null; showSettings = false; confirmLeave = false; focusId = null }
    }

    val pendingPlace = ui.request?.takeIf { it.status == RequestStatus.PENDING }?.place
    val waypoints = ui.all.mapNotNull { m -> m.waypoint?.let { it to m.colorIndex } }.distinctBy { it.first.name }

    // 내비와 나란히(화면 분할, 앱 영역 절반) 띄우면 폭이 좁다 → 위 지도 + 아래 카드·크루 목록
    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
    val compact = maxWidth < 900.dp
    LaunchedEffect(compact) { fit++ } // 지도 크기가 바뀌면 크루 전체가 보이게 다시 맞춘다
    val mapArea: @Composable (Modifier) -> Unit = { mapModifier ->
        Box(mapModifier.clip(RoundedCornerShape(16.dp))) {
            CrewMap(
                model = MapModel(ui.all, ui.me?.id ?: "", ui.destination, waypoints, pendingPlace, picked, ui.driving, c.night, fit, focusId,
                    spots = spots.map { it.place }, spotIndex = spots.indexOf(spot)),
                colors = c,
                onLongPress = { at -> if (!ui.driving) picked = Place("지도에서 고른 위치", at) },
                onMarkerTap = { id ->
                    focusId = if (focusId == id) null else id
                    if (!ui.driving) detail = ui.all.firstOrNull { it.id == id && it.id != ui.me?.id }
                },
                modifier = Modifier.fillMaxSize(),
                onSpotTap = { i -> spotId = spots.getOrNull(i)?.id },
            )
            MapHeader(ui, compact, Modifier.align(Alignment.TopStart).padding(if (compact) 10.dp else 16.dp))
            val preview by engine.drivingPreview.collectAsState()
            if (preview) {
                MapIconButton(Icons.Filled.Close, "미리보기 끝", Modifier.align(Alignment.BottomEnd).padding(bottom = 40.dp, end = 16.dp)) { engine.setDrivingPreview(false) }
            }
            if (!ui.driving) {
                Row(Modifier.align(Alignment.TopEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (compact) {
                        // 좁은 화면: 크루 코드 칸 대신 지도 위 아이콘
                        MapIconButton(Icons.Filled.Share, null) { showQr = true }
                        MapIconButton(Icons.Filled.Settings, null) { showSettings = true }
                        MapIconButton(Icons.Filled.Refresh, null) { focusId = null; fit++ }
                    } else MapIconButton(Icons.Filled.Refresh, "전체 보기") { focusId = null; fit++ }
                }
                if (picked == null && !compact) {
                    Text(
                        "지도를 길게 누르면 그 위치로 목적지를 정할 수 있어요",
                        style = T.small, color = c.textSub,
                        modifier = Modifier.align(Alignment.BottomStart).padding(16.dp).clip(RoundedCornerShape(12.dp)).background(c.surface.copy(alpha = 0.92f)).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            Text("© OpenStreetMap contributors", style = T.small.copy(fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp)), color = c.textSub,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(c.surface.copy(alpha = 0.7f)).padding(horizontal = 6.dp, vertical = 2.dp))
            picked?.let { p ->
                PickedCard(
                    p, ui.isLeader,
                    onConfirm = { engine.proposeDestination(p).let { msg -> engine.toast(msg) }; picked = null },
                    onCancel = { picked = null },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp),
                )
            }
        }
    }
    val side: @Composable (Modifier) -> Unit = { sideModifier ->
        SidePanel(
            engine, ui, spot, spots.size,
            onNextSpot = { spotId = spots[(spots.indexOf(spot) + 1) % spots.size].id },
            onQr = { showQr = true },
            onPicker = { showPicker = true },
            onSettings = { showSettings = true },
            onLeave = { confirmLeave = true },
            onMember = { m ->
                focusId = m.id
                if (!ui.driving && m.id != ui.me?.id) detail = m
            },
            modifier = sideModifier,
            compact = compact,
        )
    }
    if (compact) Column(Modifier.fillMaxSize()) {
        mapArea(Modifier.fillMaxWidth().fillMaxHeight(0.28f).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp))
        side(Modifier.fillMaxWidth().weight(1f).padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
    } else Row(Modifier.fillMaxSize()) {
        mapArea(Modifier.weight(1f).fillMaxHeight().padding(16.dp))
        side(Modifier.width(560.dp).fillMaxHeight().padding(top = 16.dp, end = 16.dp, bottom = 16.dp))
    }
    }

    if (showQr) QrDialog(ui.code, ui.meta?.name ?: "크루") { showQr = false }
    if (showPicker) DestinationPicker(engine, ui.isLeader, onClose = { showPicker = false })
    if (showSettings) SettingsDialog(engine, inCrew = true) { showSettings = false }
    detail?.let { m -> MemberDialog(engine, m, ui) { detail = null } }
    if (confirmLeave) LeaveDialog(ui.isLeader, onConfirm = { confirmLeave = false; engine.leaveCrew() }, onCancel = { confirmLeave = false })
    if (ui.completed) SummaryDialog(ui, onLeave = { engine.leaveCrew() })
}

@Composable
private fun MapHeader(ui: CrewUi, compact: Boolean, modifier: Modifier) {
    val c = Argo.c
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(c.surface.copy(alpha = 0.95f)).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Dot(if (ui.connected) c.success else c.danger, 12.dp)
        Text(ui.meta?.name ?: "크루", style = T.head, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = if (compact) 180.dp else IntrinsicWidthCap))
        if (!compact) Text("${ui.all.size}명", style = T.body, color = c.textSub)
        if (ui.driving) Chip("주행 중", c.surfaceAlt, c.text)
        if (!ui.connected) Chip("연결 중…", c.warnContainer, c.warn)
    }
}

private val IntrinsicWidthCap = 360.dp

@Composable
private fun MapIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Argo.c
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(c.surface.copy(alpha = 0.95f)).clickable(onClick = onClick).padding(horizontal = if (label == null) 14.dp else 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, label, tint = c.text, modifier = Modifier.size(26.dp))
        if (label != null) Text(label, style = T.label, color = c.text)
    }
}

@Composable
private fun PickedCard(p: Place, isLeader: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    val c = Argo.c
    Panel(modifier.widthIn(max = 640.dp)) {
        RowCentered {
            Icon(Icons.Filled.Place, null, tint = c.sign, modifier = Modifier.size(32.dp))
            Column(Modifier.weight(1f)) {
                Text("이 위치를 크루 목적지로", style = T.head, color = c.text)
                Text(String.format("%.4f, %.4f", p.at.lat, p.at.lon), style = T.small, color = c.textSub)
            }
        }
        Spacer(Modifier.height(16.dp))
        RowCentered {
            BigButton(if (isLeader) "목적지로 정하기" else "리더에게 변경 요청", onConfirm, Modifier.weight(1f))
            GhostButton("취소", onCancel)
        }
    }
}

@Composable
private fun SidePanel(
    engine: CrewEngine,
    ui: CrewUi,
    spot: MeetSpot?,
    spotCount: Int,
    onNextSpot: () -> Unit,
    onQr: () -> Unit,
    onPicker: () -> Unit,
    onSettings: () -> Unit,
    onLeave: () -> Unit,
    onMember: (Member) -> Unit,
    modifier: Modifier,
    compact: Boolean = false,
) {
    val c = Argo.c
    // 좁은 화면: 세로 공간이 모자라 패널 전체를 스크롤 (크루 목록은 카드 아래로 이어진다)
    val scroll = rememberScrollState()
    Column(if (compact) modifier.verticalScroll(scroll) else modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)) {
        // 크루 코드 + 공유 (좁은 화면에선 지도 위 아이콘으로)
        if (!compact) RowCentered(Modifier.fillMaxWidth().padding(start = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text("크루 코드", style = T.small, color = c.textSub)
                Text(ui.code, style = T.code, color = c.text)
            }
            if (!ui.driving) {
                IconButton(onClick = onQr, modifier = Modifier.size(64.dp)) { Icon(Icons.Filled.Share, "초대", tint = c.text, modifier = Modifier.size(32.dp)) }
                IconButton(onClick = onSettings, modifier = Modifier.size(64.dp)) { Icon(Icons.Filled.Settings, "설정", tint = c.textSub, modifier = Modifier.size(32.dp)) }
            }
        }

        DestinationCard(ui, onPicker)

        spot?.let { MeetCard(engine, ui, it, spotCount, onNextSpot) }

        // 한 번 탭 카드
        ui.prompts.take(if (spot != null || compact) 2 else 3).forEach { p -> PromptCard(engine, p) }

        // 크루 리스트
        // 크루 행렬: 목적지에 가까운 차부터. 아바타를 잇는 세로선이 행렬 순서를 뜻한다.
        Column(if (compact) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().weight(1f)) {
            RowCentered(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp)) {
                Text("크루원 ${ui.all.size}", style = T.label, color = c.textSub, modifier = Modifier.weight(1f))
                if (ui.destination != null) Text("도착 예정", style = T.small, color = c.textSub)
            }
            val convoy = convoyOrder(ui)
            Column(if (compact) Modifier else Modifier.verticalScroll(rememberScrollState())) {
                convoy.forEachIndexed { i, m ->
                    MemberRow(m, ui, me = m.id == ui.me?.id, first = i == 0, last = i == convoy.lastIndex) { onMember(m) }
                }
                if (ui.others.isEmpty()) {
                    Text("아직 혼자예요. 크루 코드나 QR로 친구를 초대하세요.", style = T.body, color = c.textSub, modifier = Modifier.padding(12.dp))
                }
            }
        }

        if (!ui.driving) {
            RowCentered {
                BigButton(if (ui.isLeader) "목적지 정하기" else "목적지 변경 요청", onPicker, Modifier.weight(1f), icon = Icons.Filled.LocationOn)
                GhostButton(if (ui.isLeader) "크루 종료" else "나가기", onLeave, icon = Icons.Filled.ExitToApp, tint = c.danger)
            }
        }
    }
}

/** 크루 목적지 표지판 (파랑). 목적지 이름과 내 도착 시각이 화면에서 가장 큰 정보. */
@Composable
private fun DestinationCard(ui: CrewUi, onPicker: () -> Unit) {
    val c = Argo.c
    val dest = ui.destination
    val me = ui.me
    val on = c.onSign
    if (dest == null) {
        Panel(Modifier.fillMaxWidth()) {
            Text("목적지를 아직 정하지 않았어요", style = T.head, color = c.text)
            if (!ui.driving) Text(if (ui.isLeader) "아래 [목적지 정하기]로 정하면 크루 모두에게 안내 카드가 가요" else "리더에게 목적지를 제안할 수 있어요", style = T.small, color = c.textSub)
        }
        return
    }
    SignPanel(c.sign, Modifier.fillMaxWidth(), padding = 16.dp) {
        Text(dest.name, style = T.title, color = on, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        when {
            me?.status == MemberStatus.ARRIVED -> Text("도착했어요", style = T.head, color = on)
            me?.remainS != null && me.destination?.sameAs(dest) == true -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(Fmt.clock(me.etaMs!!), style = T.display, color = on)
                Text(" 도착", style = T.head, color = on, modifier = Modifier.padding(bottom = 4.dp).weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(Fmt.km(me.remainM ?: 0), style = T.head, color = on)
                    Text("${Fmt.duration(me.remainS)} 남음", style = T.small, color = on.copy(alpha = 0.85f))
                }
            }
            else -> Text("내 내비는 아직 이 목적지로 안내 중이 아니에요", style = T.body, color = on.copy(alpha = 0.9f))
        }
    }
}

/** 모이기 좋은 휴게소 표지판 (초록): 누가 몇 분 뒤 도착하는지, 먼저 온 차가 얼마나 기다리는지. */
@Composable
private fun MeetCard(engine: CrewEngine, ui: CrewUi, spot: MeetSpot, count: Int, onNext: () -> Unit) {
    val c = Argo.c
    val on = c.onSign
    val people = ui.all.associateBy { it.id }
    val joined = ui.me?.waypoint?.sameAs(spot.place) == true
    SignPanel(c.signGreen, Modifier.fillMaxWidth(), padding = 14.dp) {
        RowCentered(gap = 10.dp) {
            Text("모이기 좋은 휴게소", style = T.label, color = on.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
            if (count > 1 && !ui.driving) Text("다른 곳", style = T.label, color = on,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onNext).border(1.5.dp, on.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 4.dp))
        }
        Text(spot.place.name, style = T.head, color = on, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        // 크루원별 도착까지 걸리는 시간 (빠른 순)
        RowCentered(gap = 18.dp) {
            spot.etaS.entries.sortedBy { it.value }.forEach { (id, sec) ->
                val m = people[id]
                RowCentered(gap = 6.dp) {
                    Avatar(m?.initial ?: "?", m?.let { c.crewColor(it.colorIndex) } ?: c.outline, size = 30.dp)
                    Text(Fmt.duration(sec), style = T.bodyB, color = on)
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            (if (spot.detourS < 120) "가는 길에 있고, " else "${Fmt.duration(spot.detourS)} 돌아가고, ") +
                (if (spot.waitS < 120) "거의 같이 도착해요" else "먼저 온 차가 최대 ${Fmt.duration(spot.waitS)} 기다려요"),
            style = T.small, color = on.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        if (joined) RowCentered(gap = 8.dp) {
            Icon(Icons.Filled.Check, null, tint = on, modifier = Modifier.size(26.dp))
            Text("내 경유지에 넣고 크루에 알렸어요", style = T.bodyB, color = on)
        }
        // 야간엔 흰 버튼이 눈부시므로 어두운 버튼 + 흰 글자
        else BigButton("여기서 모이기", { engine.meetAt(spot) }, Modifier.fillMaxWidth(), container = if (c.night) c.bg else on, content = if (c.night) on else c.signGreen)
    }
}

@Composable
private fun PromptCard(engine: CrewEngine, p: Prompt) {
    val c = Argo.c
    // 결정이 필요한 승인 요청·결과만 색으로 구분하고, 나머지 소식은 기본 면
    val bg = when (p) {
        is Prompt.ApproveRequest -> c.warnContainer
        is Prompt.MyRequest -> when (p.request.status) {
            RequestStatus.ACCEPTED -> c.successContainer
            RequestStatus.DECLINED, RequestStatus.EXPIRED -> c.dangerContainer
            else -> c.surface
        }
        else -> c.surface
    }
    Panel(Modifier.fillMaxWidth(), padding = 16.dp, color = bg) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                val (title, sub) = promptText(p)
                Text(title, style = T.head.copy(fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp)), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (sub != null) Text(sub, style = T.small, color = c.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { engine.dismiss(p) }, modifier = Modifier.size(52.dp)) { Icon(Icons.Filled.Close, "닫기", tint = c.textSub, modifier = Modifier.size(28.dp)) }
        }
        Spacer(Modifier.height(10.dp))
        when (p) {
            is Prompt.FollowDestination -> BigButton("안내 시작", { engine.followDestination(p) }, Modifier.fillMaxWidth())
            is Prompt.JoinWaypoint -> BigButton("같이 들르기", { engine.joinWaypoint(p) }, Modifier.fillMaxWidth())
            is Prompt.ApproveRequest -> RowCentered {
                BigButton("수락", { engine.approve(p) }, Modifier.weight(1f))
                GhostButton("거절", { engine.decline(p) }, Modifier.weight(1f))
            }
            is Prompt.MyRequest -> Unit
        }
    }
}

private fun promptText(p: Prompt): Pair<String, String?> = when (p) {
    is Prompt.FollowDestination ->
        (if (p.changed) "크루 목적지가 ${p.place.name}(으)로 바뀌었어요" else "크루 목적지 ${p.place.name}(으)로 안내할까요?") to p.place.address
    is Prompt.JoinWaypoint -> (if (p.meet) "${p.member.name}님이 ${p.place.name}에서 모여요" else "${p.member.name}님이 ${p.place.name}에 들러요") to (p.member.waypointRemainS?.let { "${Fmt.duration(it)} 뒤 도착 예정" })
    is Prompt.ApproveRequest -> "${p.request.fromName}님이 목적지를 ${p.request.place.name}(으)로 바꾸자고 해요" to "${Fmt.clock(p.request.expiresAt)}까지 응답이 없으면 만료돼요"
    is Prompt.MyRequest -> when (p.request.status) {
        RequestStatus.PENDING -> "${p.request.place.name} 변경 요청을 보냈어요" to "리더의 응답을 기다리는 중"
        RequestStatus.ACCEPTED -> "리더가 ${p.request.place.name}(으)로 변경을 수락했어요" to null
        RequestStatus.DECLINED -> "리더가 ${p.request.place.name} 변경을 거절했어요" to null
        RequestStatus.EXPIRED -> "${p.request.place.name} 변경 요청이 만료됐어요" to null
    }
}

/** 행렬 순서: 도착한 차 → 목적지까지 남은 거리가 짧은 차 → 거리 모름 → 연결 끊김. */
private fun convoyOrder(ui: CrewUi): List<Member> = ui.all.sortedWith(
    compareBy<Member>({ it.status == MemberStatus.OFFLINE }, { it.status != MemberStatus.ARRIVED }, { it.arrivedAt ?: 0L }, { it.remainM ?: Int.MAX_VALUE }),
)

@Composable
private fun MemberRow(m: Member, ui: CrewUi, me: Boolean, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val c = Argo.c
    val leader = ui.meta?.leaderId == m.id
    val rail = c.outline
    ListRow(onClick, Modifier.drawBehind {
        // 아바타 중심(8dp 여백 + 26dp)을 지나는 행렬 선
        val x = (8 + 26).dp.toPx()
        val w = 4.dp.toPx()
        val top = if (first) size.height / 2 else 0f
        val bottom = if (last) size.height / 2 else size.height
        if (bottom > top) drawRect(rail, topLeft = Offset(x - w / 2, top), size = Size(w, bottom - top))
    }) {
        Avatar(m.initial, c.crewColor(m.colorIndex), dim = m.status == MemberStatus.OFFLINE, ring = c.bg)
        Column(Modifier.weight(1f)) {
            RowCentered(gap = 8.dp) {
                Text(if (me) "${m.name} (나)" else m.name, style = T.bodyB, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (leader) Chip("리더", c.surfaceAlt, c.text)
                if (m.kind == MemberKind.WEB) Text("휴대폰", style = T.small, color = c.textSub)
            }
            Text(memberLine(m, ui, me), style = T.small, color = c.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val eta = m.etaMs
        when {
            m.status == MemberStatus.ARRIVED -> RowCentered(gap = 6.dp) {
                Icon(Icons.Filled.Check, "도착", tint = c.success, modifier = Modifier.size(24.dp))
                Text(m.arrivedAt?.let { Fmt.clock(it) } ?: "도착", style = T.head, color = c.success)
            }
            m.status == MemberStatus.OFFLINE -> Text("연결 끊김", style = T.small, color = c.textSub)
            eta != null && m.destination?.sameAs(ui.destination) == true -> Text(Fmt.clock(eta), style = T.head, color = c.text)
            else -> Text(m.status.label, style = T.small, color = c.textSub)
        }
    }
}

private fun memberLine(m: Member, ui: CrewUi, me: Boolean): String {
    val parts = mutableListOf<String>()
    val myRemain = ui.me?.remainM
    val dest = ui.destination
    if (!me && m.remainM != null && myRemain != null && m.destination?.sameAs(dest) == true && ui.me?.destination?.sameAs(dest) == true) {
        parts += Fmt.gap(m.remainM, myRemain)
    } else if (!me && m.at != null && ui.me?.at != null) {
        parts += "${Fmt.km(com.zigae.argonauts.core.Geo.distanceM(m.at, ui.me.at))} 떨어짐"
    }
    if (m.remainM != null && m.status != MemberStatus.ARRIVED) parts += "남은 ${Fmt.km(m.remainM)}"
    if (m.waypoint != null && m.status != MemberStatus.ARRIVED) parts += "${m.waypoint.name} 들르는 중"
    else if (m.status != MemberStatus.ARRIVED && m.status != MemberStatus.OFFLINE) parts += m.status.label
    if (m.status == MemberStatus.ARRIVED) parts += "도착했어요"
    return parts.joinToString(", ")
}

// Engine에 짧은 안내를 띄우는 확장 (화면 쪽 메시지)
fun CrewEngine.toast(msg: String) = emitToast(msg)

