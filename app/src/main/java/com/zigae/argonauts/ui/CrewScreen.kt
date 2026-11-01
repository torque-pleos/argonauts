package com.zigae.argonauts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zigae.argonauts.core.Fmt
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

    // 주행이 시작되면 입력·상세 화면을 닫는다 (주행 중에는 단일 탭 카드만)
    LaunchedEffect(ui.driving) {
        if (ui.driving) { showPicker = false; showQr = false; detail = null; picked = null; showSettings = false; confirmLeave = false; focusId = null }
    }

    val pendingPlace = ui.request?.takeIf { it.status == RequestStatus.PENDING }?.place
    val waypoints = ui.all.mapNotNull { m -> m.waypoint?.let { it to m.colorIndex } }.distinctBy { it.first.name }

    Row(Modifier.fillMaxSize().background(c.bg)) {
        Box(Modifier.weight(1f).fillMaxHeight().padding(16.dp).clip(RoundedCornerShape(24.dp))) {
            CrewMap(
                model = MapModel(ui.all, ui.me?.id ?: "", ui.destination, waypoints, pendingPlace, picked, ui.driving, c.night, fit, focusId),
                colors = c,
                onLongPress = { at -> if (!ui.driving) picked = Place("지도에서 고른 위치", at) },
                onMarkerTap = { id ->
                    focusId = if (focusId == id) null else id
                    if (!ui.driving) detail = ui.all.firstOrNull { it.id == id && it.id != ui.me?.id }
                },
                modifier = Modifier.fillMaxSize(),
            )
            MapHeader(ui, Modifier.align(Alignment.TopStart).padding(16.dp))
            val preview by engine.drivingPreview.collectAsState()
            if (preview) {
                MapIconButton(Icons.Filled.Close, "미리보기 끝", Modifier.align(Alignment.BottomEnd).padding(bottom = 40.dp, end = 16.dp)) { engine.setDrivingPreview(false) }
            }
            if (!ui.driving) {
                Row(Modifier.align(Alignment.TopEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MapIconButton(Icons.Filled.Refresh, "전체 보기") { focusId = null; fit++ }
                }
                if (picked == null) {
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
        SidePanel(
            engine, ui,
            onQr = { showQr = true },
            onPicker = { showPicker = true },
            onSettings = { showSettings = true },
            onLeave = { confirmLeave = true },
            onMember = { m ->
                focusId = m.id
                if (!ui.driving && m.id != ui.me?.id) detail = m
            },
            modifier = Modifier.width(560.dp).fillMaxHeight().padding(top = 16.dp, end = 16.dp, bottom = 16.dp),
        )
    }

    if (showQr) QrDialog(ui.code, ui.meta?.name ?: "크루") { showQr = false }
    if (showPicker) DestinationPicker(engine, ui.isLeader, onClose = { showPicker = false })
    if (showSettings) SettingsDialog(engine, inCrew = true) { showSettings = false }
    detail?.let { m -> MemberDialog(engine, m, ui) { detail = null } }
    if (confirmLeave) LeaveDialog(ui.isLeader, onConfirm = { confirmLeave = false; engine.leaveCrew() }, onCancel = { confirmLeave = false })
    if (ui.completed) SummaryDialog(ui, onLeave = { engine.leaveCrew() })
}

@Composable
private fun MapHeader(ui: CrewUi, modifier: Modifier) {
    val c = Argo.c
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(c.surface.copy(alpha = 0.95f)).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Dot(if (ui.connected) c.success else c.danger, 14.dp)
        Text(ui.meta?.name ?: "크루", style = T.head, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(IntrinsicWidthCap))
        Chip("${ui.all.size}대", c.surfaceAlt, c.text)
        if (ui.driving) Chip("주행 중 · 간결 모드", c.infoContainer, c.primary)
        if (!ui.connected) Chip("연결 중…", c.warnContainer, c.warn)
    }
}

private val IntrinsicWidthCap = 360.dp

@Composable
private fun MapIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Argo.c
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(c.surface.copy(alpha = 0.95f)).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = c.text, modifier = Modifier.size(26.dp))
        Text(label, style = T.label, color = c.text)
    }
}

@Composable
private fun PickedCard(p: Place, isLeader: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    val c = Argo.c
    Panel(modifier.width(640.dp), border = c.accent) {
        RowCentered {
            Icon(Icons.Filled.Place, null, tint = c.accent, modifier = Modifier.size(32.dp))
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
    onQr: () -> Unit,
    onPicker: () -> Unit,
    onSettings: () -> Unit,
    onLeave: () -> Unit,
    onMember: (Member) -> Unit,
    modifier: Modifier,
) {
    val c = Argo.c
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 크루 코드 + 공유
        Panel(Modifier.fillMaxWidth(), padding = 16.dp) {
            RowCentered {
                Column(Modifier.weight(1f)) {
                    Text("크루 코드", style = T.label, color = c.textSub)
                    Text(ui.code, style = T.mono, color = c.text)
                }
                if (!ui.driving) {
                    IconButton(onClick = onQr, modifier = Modifier.size(64.dp)) { Icon(Icons.Filled.Share, "초대", tint = c.primary, modifier = Modifier.size(32.dp)) }
                    IconButton(onClick = onSettings, modifier = Modifier.size(64.dp)) { Icon(Icons.Filled.Settings, "설정", tint = c.textSub, modifier = Modifier.size(32.dp)) }
                }
            }
        }

        DestinationCard(ui, onPicker)

        // 한 번 탭 카드
        ui.prompts.take(3).forEach { p -> PromptCard(engine, p) }

        // 크루 리스트
        Panel(Modifier.fillMaxWidth().weight(1f), padding = 12.dp) {
            SectionLabel("크루원", Modifier.padding(start = 8.dp, top = 4.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ui.me?.let { MemberRow(it, ui, me = true) { onMember(it) } }
                ui.others.forEach { m -> MemberRow(m, ui, me = false) { onMember(m) } }
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

@Composable
private fun DestinationCard(ui: CrewUi, onPicker: () -> Unit) {
    val c = Argo.c
    val dest = ui.destination
    val me = ui.me
    Panel(Modifier.fillMaxWidth(), color = c.primary) {
        Text("크루 목적지", style = T.label, color = c.onPrimary.copy(alpha = 0.75f))
        if (dest == null) {
            Text("아직 정하지 않았어요", style = T.title, color = c.onPrimary)
            if (!ui.driving) Text(if (ui.isLeader) "아래 버튼으로 목적지를 정하세요" else "리더에게 목적지를 제안할 수 있어요", style = T.small, color = c.onPrimary.copy(alpha = 0.8f))
            return@Panel
        }
        Text(dest.name, style = T.title, color = c.onPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        when {
            me?.status == MemberStatus.ARRIVED -> Text("도착했어요", style = T.head, color = c.onPrimary)
            me?.remainS != null && me.destination?.sameAs(dest) == true -> RowCentered(gap = 18.dp) {
                Column {
                    Text("도착 예정", style = T.small, color = c.onPrimary.copy(alpha = 0.75f))
                    Text(Fmt.clock(me.etaMs!!), style = T.display, color = c.onPrimary)
                }
                Column {
                    Text("남은 거리 · 시간", style = T.small, color = c.onPrimary.copy(alpha = 0.75f))
                    Text("${Fmt.km(me.remainM ?: 0)} · ${Fmt.duration(me.remainS)}", style = T.head, color = c.onPrimary)
                }
            }
            else -> Text("내 내비는 아직 이 목적지로 안내 중이 아니에요", style = T.body, color = c.onPrimary.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun PromptCard(engine: CrewEngine, p: Prompt) {
    val c = Argo.c
    val (bg, border, fg) = when (p) {
        is Prompt.ApproveRequest -> Triple(c.warnContainer, c.warn, c.warn)
        is Prompt.MyRequest -> when (p.request.status) {
            RequestStatus.ACCEPTED -> Triple(c.successContainer, c.success, c.success)
            RequestStatus.DECLINED, RequestStatus.EXPIRED -> Triple(c.dangerContainer, c.danger, c.danger)
            else -> Triple(c.infoContainer, c.primary, c.primary)
        }
        else -> Triple(c.infoContainer, c.accent, c.primary)
    }
    Panel(Modifier.fillMaxWidth(), padding = 16.dp, color = bg, border = border) {
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
    is Prompt.JoinWaypoint -> "${p.member.name}님이 ${p.place.name}에 들러요" to (p.member.waypointRemainS?.let { "${Fmt.duration(it)} 뒤 도착 예정" })
    is Prompt.ApproveRequest -> "${p.request.fromName}님이 목적지를 ${p.request.place.name}(으)로 바꾸자고 해요" to "${Fmt.clock(p.request.expiresAt)}까지 응답이 없으면 만료돼요"
    is Prompt.MyRequest -> when (p.request.status) {
        RequestStatus.PENDING -> "${p.request.place.name} 변경 요청을 보냈어요" to "리더의 응답을 기다리는 중"
        RequestStatus.ACCEPTED -> "리더가 ${p.request.place.name}(으)로 변경을 수락했어요" to null
        RequestStatus.DECLINED -> "리더가 ${p.request.place.name} 변경을 거절했어요" to null
        RequestStatus.EXPIRED -> "${p.request.place.name} 변경 요청이 만료됐어요" to null
    }
}

@Composable
private fun MemberRow(m: Member, ui: CrewUi, me: Boolean, onClick: () -> Unit) {
    val c = Argo.c
    val leader = ui.meta?.leaderId == m.id
    ListRow(onClick) {
        Avatar(m.initial, c.crewColor(m.colorIndex), dim = m.status == MemberStatus.OFFLINE)
        Column(Modifier.weight(1f)) {
            RowCentered(gap = 8.dp) {
                Text(if (me) "${m.name} (나)" else m.name, style = T.bodyB, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (leader) Chip("리더", c.primary, c.onPrimary)
                if (m.kind == MemberKind.WEB) Chip("휴대폰", c.surfaceAlt, c.textSub)
            }
            Text(memberLine(m, ui, me), style = T.small, color = c.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            val eta = m.etaMs
            when {
                m.status == MemberStatus.ARRIVED -> Chip("도착", c.successContainer, c.success)
                m.status == MemberStatus.OFFLINE -> Chip("연결 끊김", c.surfaceAlt, c.textSub)
                eta != null && m.destination?.sameAs(ui.destination) == true -> {
                    Text(Fmt.clock(eta), style = T.head, color = c.text)
                    Text("도착 예정", style = T.small, color = c.textSub)
                }
                else -> Chip(m.status.label, c.surfaceAlt, c.textSub)
            }
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
    if (m.status == MemberStatus.ARRIVED && m.arrivedAt != null) parts += "${Fmt.clock(m.arrivedAt)} 도착"
    return parts.joinToString(" · ")
}

// Engine에 짧은 안내를 띄우는 확장 (화면 쪽 메시지)
fun CrewEngine.toast(msg: String) = emitToast(msg)

