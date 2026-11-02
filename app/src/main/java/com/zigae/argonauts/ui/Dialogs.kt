package com.zigae.argonauts.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.zigae.argonauts.BuildConfig
import com.zigae.argonauts.core.Fmt
import com.zigae.argonauts.core.Geo
import com.zigae.argonauts.core.Member
import com.zigae.argonauts.core.MemberStatus
import com.zigae.argonauts.core.Place
import com.zigae.argonauts.engine.CrewEngine
import com.zigae.argonauts.engine.CrewUi
import com.zigae.argonauts.navi.Trip
import com.zigae.argonauts.net.PlaceSearch
import kotlinx.coroutines.launch

@Composable
fun ArgoDialog(onDismiss: () -> Unit, width: Dp = 880.dp, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.padding(16.dp).widthIn(max = width).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Argo.c.surface).padding(32.dp)) { content() }
    }
}

// region 초대 QR

fun qrBitmap(text: String, size: Int = 640): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) 0xFF0E1726.toInt() else 0xFFFFFFFF.toInt())
    return bmp
}

fun inviteUrl(code: String) = "${BuildConfig.WEB_URL}/?c=$code"

@Composable
fun QrDialog(code: String, crewName: String, onClose: () -> Unit) {
    val c = Argo.c
    val url = inviteUrl(code)
    val bmp = remember(url) { qrBitmap(url) }
    ArgoDialog(onClose) {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RoundedCornerShape(20.dp)).background(androidx.compose.ui.graphics.Color.White).padding(16.dp)) {
                Image(bmp.asImageBitmap(), "초대 QR", Modifier.size(320.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("$crewName 초대", style = T.title, color = c.text)
                Text("휴대폰 카메라로 QR을 찍으면 웹에서 바로 참여해요. 다른 Pleos 차량은 앱에서 코드를 입력하세요.", style = T.body, color = c.textSub)
                Text("크루 코드", style = T.label, color = c.textSub)
                Text(code, style = T.mono.copy(fontSize = androidx.compose.ui.unit.TextUnit(40f, androidx.compose.ui.unit.TextUnitType.Sp)), color = c.text)
                Text(url, style = T.small, color = c.textSub)
                Spacer(Modifier.height(8.dp))
                BigButton("닫기", onClose)
            }
        }
    }
}

// endregion

// region 목적지 정하기 / 변경 요청 (정차 중)

private enum class PickTab(val label: String) { SEARCH("검색"), BOOKMARK("즐겨찾기"), RECENT("최근 목적지") }

@Composable
fun DestinationPicker(engine: CrewEngine, isLeader: Boolean, onClose: () -> Unit) {
    val c = Argo.c
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(PickTab.SEARCH) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var chosen by remember { mutableStateOf<Place?>(null) }
    val near = engine.myLocation()

    fun load(t: PickTab) {
        tab = t; chosen = null
        if (t == PickTab.SEARCH) { results = emptyList(); return }
        loading = true
        scope.launch {
            results = if (t == PickTab.BOOKMARK) engine.bookmarks() else engine.recent()
            loading = false
        }
    }

    fun search() {
        loading = true; chosen = null
        scope.launch { results = PlaceSearch.search(query, near); loading = false }
    }

    ArgoDialog(onClose, width = 960.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (isLeader) "크루 목적지 정하기" else "목적지 변경 요청", style = T.title, color = c.text)
            Text(if (isLeader) "고른 곳이 크루 목적지가 되고, 크루원 차에 안내 시작 카드가 떠요." else "리더가 수락하면 크루 전체 목적지가 바뀌어요. 지도에서 길게 눌러 고를 수도 있어요.", style = T.body, color = c.textSub)
            RowCentered {
                PickTab.entries.forEach { t ->
                    val sel = t == tab
                    Text(
                        t.label, style = T.bodyB, color = if (sel) c.onPrimary else c.text,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (sel) c.primary else c.surfaceAlt).clickable { load(t) }.padding(horizontal = 22.dp, vertical = 14.dp),
                    )
                }
            }
            if (tab == PickTab.SEARCH) {
                RowCentered {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = T.body.copy(color = c.text),
                        placeholder = { Text("장소 이름 (예: 남이섬, 가평휴게소)", style = T.body, color = c.textSub) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.primary, unfocusedBorderColor = c.outline),
                    )
                    BigButton("검색", { search() }, icon = Icons.Filled.Search, enabled = query.isNotBlank())
                }
            }
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                when {
                    loading -> Text("불러오는 중…", style = T.body, color = c.textSub, modifier = Modifier.padding(12.dp))
                    results.isEmpty() && (tab != PickTab.SEARCH || query.isNotBlank()) -> Text("결과가 없어요", style = T.body, color = c.textSub, modifier = Modifier.padding(12.dp))
                }
                results.forEach { p ->
                    val sel = chosen == p
                    ListRow(onClick = { chosen = p }) {
                        Icon(if (tab == PickTab.BOOKMARK) Icons.Filled.Star else if (p.name == "집") Icons.Filled.Home else Icons.Filled.Place, null, tint = if (sel) c.primary else c.textSub, modifier = Modifier.size(30.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = T.bodyB, color = if (sel) c.primary else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            p.address?.let { Text(it, style = T.small, color = c.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        near?.let { Text(Fmt.km(Geo.distanceM(it, p.at)), style = T.label, color = c.textSub) }
                    }
                }
            }
            RowCentered {
                BigButton(
                    if (isLeader) "이곳을 크루 목적지로" else "리더에게 요청 보내기",
                    { chosen?.let { engine.toast(engine.proposeDestination(it)); onClose() } },
                    Modifier.weight(1f), enabled = chosen != null,
                )
                GhostButton("닫기", onClose)
            }
        }
    }
}

// endregion

// region 크루원 상세 (정차 중) — 만나기 모드

@Composable
fun MemberDialog(engine: CrewEngine, m: Member, ui: CrewUi, onClose: () -> Unit) {
    val c = Argo.c
    var trip by remember { mutableStateOf<Trip?>(null) }
    var tripLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(m.id) { trip = engine.tripTo(m); tripLoaded = true }
    ArgoDialog(onClose, width = 760.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            RowCentered(gap = 18.dp) {
                Avatar(m.initial, c.crewColor(m.colorIndex), size = 72.dp)
                Column(Modifier.weight(1f)) {
                    Text(m.name, style = T.title, color = c.text)
                    Text(m.status.label + (m.destination?.let { " · ${it.name}(으)로 가는 중" } ?: ""), style = T.body, color = c.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Panel(Modifier.fillMaxWidth(), color = c.surfaceAlt) {
                Text("내 차에서 ${m.name}까지", style = T.label, color = c.textSub)
                val t = trip
                when {
                    t != null -> Text("${Fmt.duration(t.durationS)} · ${Fmt.km(t.distanceM)}", style = T.title, color = c.text)
                    !tripLoaded -> Text("경로 계산 중…", style = T.body, color = c.textSub)
                    else -> {
                        val d = m.at?.let { a -> ui.me?.at?.let { Geo.distanceM(a, it) } }
                        Text(d?.let { "직선 ${Fmt.km(it)}" } ?: "위치를 알 수 없어요", style = T.title, color = c.text)
                    }
                }
                m.etaMs?.takeIf { m.status != MemberStatus.ARRIVED }?.let { Text("${m.name}의 도착 예정 ${Fmt.clock(it)}", style = T.body, color = c.textSub) }
            }
            RowCentered {
                BigButton("${m.name}에게 가기", { engine.goToMember(m); onClose() }, Modifier.weight(1f), enabled = m.at != null, icon = Icons.Filled.Place)
                GhostButton("닫기", onClose)
            }
        }
    }
}

// endregion

// region 설정 · 시연

@Composable
fun SettingsDialog(engine: CrewEngine, inCrew: Boolean, onClose: () -> Unit) {
    val c = Argo.c
    val profile by engine.profile.collectAsState()
    var name by remember { mutableStateOf(profile.name) }
    ArgoDialog(onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("설정", style = T.title, color = c.text)
            SectionLabel("내 이름 (크루에 보이는 이름)")
            RowCentered {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(10) }, singleLine = true, modifier = Modifier.weight(1f),
                    textStyle = T.body.copy(color = c.text),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.primary, unfocusedBorderColor = c.outline),
                )
                BigButton("저장", { engine.setName(name); engine.toast("이름을 저장했어요") }, enabled = name.isNotBlank())
            }
            Panel(Modifier.fillMaxWidth(), color = c.surfaceAlt) {
                RowCentered {
                    Column(Modifier.weight(1f)) {
                        Text("시연 모드", style = T.head, color = c.text)
                        Text("가상 크루원 2명(민수·지영)이 함께 달리고, 기어가 D일 때 내 차도 경로를 따라 움직여요. (에뮬레이터는 현재 위치가 고정이라 시연용)", style = T.small, color = c.textSub)
                    }
                    Switch(profile.demo, { engine.setDemo(it) }, colors = SwitchDefaults.colors(checkedTrackColor = c.primary))
                }
                if (profile.demo && inCrew) {
                    Spacer(Modifier.height(12.dp))
                    SectionLabel("시연 스크립트 (리더 기기)")
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton("① 지영이 휴게소를 경유지로 추가", { engine.demoJiyoungRestStop(); onClose() }, Modifier.fillMaxWidth())
                        GhostButton("② 민수가 목적지 변경 요청", { engine.demoMinsuRequest(); onClose() }, Modifier.fillMaxWidth())
                        GhostButton("③ 전원 도착 (시간 단축)", { engine.demoArriveAll(); onClose() }, Modifier.fillMaxWidth())
                        GhostButton("주행 화면 미리보기 켜기 (지도 위 '미리보기 끝'으로 종료)", { engine.setDrivingPreview(true); onClose() }, Modifier.fillMaxWidth())
                    }
                }
            }
            Text("크루 위치는 크루 코드를 아는 사람에게만 보이며, 크루를 나가면 공유가 즉시 멈추고 기록이 지워져요.", style = T.small, color = c.textSub)
            BigButton("닫기", onClose, Modifier.fillMaxWidth())
        }
    }
}

// endregion

@Composable
fun LeaveDialog(isLeader: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val c = Argo.c
    ArgoDialog(onCancel, width = 720.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (isLeader) "크루를 종료할까요?" else "크루에서 나갈까요?", style = T.title, color = c.text)
            Text(if (isLeader) "모든 크루원의 위치 공유가 멈추고 크루 기록이 지워져요." else "내 위치 공유가 즉시 멈춰요.", style = T.body, color = c.textSub)
            RowCentered {
                BigButton(if (isLeader) "크루 종료" else "나가기", onConfirm, Modifier.weight(1f), container = c.danger, content = androidx.compose.ui.graphics.Color.White)
                GhostButton("취소", onCancel, Modifier.weight(1f))
            }
        }
    }
}

/** 전원 도착 → 원정 기록 카드. */
@Composable
fun SummaryDialog(ui: CrewUi, onLeave: () -> Unit) {
    val c = Argo.c
    val arrived = ui.all.filter { it.arrivedAt != null }.sortedBy { it.arrivedAt }
    val started = ui.meta?.createdAt ?: arrived.firstOrNull()?.arrivedAt ?: System.currentTimeMillis()
    val last = arrived.lastOrNull()?.arrivedAt ?: System.currentTimeMillis()
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.padding(16.dp).widthIn(max = 820.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surface)) {
            Column(Modifier.fillMaxWidth().background(c.primary).padding(32.dp)) {
                Text("원정 완료", style = T.label, color = c.onPrimary.copy(alpha = 0.8f))
                Text("${ui.meta?.name ?: "크루"} · ${ui.destination?.name ?: ""}", style = T.title, color = c.onPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${arrived.size}대 모두 도착 · 총 ${Fmt.duration(((last - started) / 1000).toInt().coerceAtLeast(60))}", style = T.body, color = c.onPrimary.copy(alpha = 0.85f))
            }
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("도착 순서")
                arrived.forEachIndexed { i, m ->
                    RowCentered {
                        Text("${i + 1}", style = T.title, color = c.textSub, modifier = Modifier.width(40.dp))
                        Avatar(m.initial, c.crewColor(m.colorIndex), size = 48.dp)
                        Text(m.name + if (m.id == ui.me?.id) " (나)" else "", style = T.bodyB, color = c.text, modifier = Modifier.weight(1f))
                        Text(Fmt.clock(m.arrivedAt!!), style = T.head, color = c.text)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(if (ui.isLeader) "크루를 해산하면 모두의 위치 공유가 멈추고 기록이 지워져요." else "리더가 크루를 해산하거나 직접 나갈 수 있어요.", style = T.small, color = c.textSub)
                Spacer(Modifier.height(8.dp))
                if (!ui.driving) BigButton(if (ui.isLeader) "크루 해산" else "크루 나가기", onLeave, Modifier.fillMaxWidth())
                else Text("정차하면 크루를 해산할 수 있어요", style = T.body, color = c.textSub)
            }
        }
    }
}
