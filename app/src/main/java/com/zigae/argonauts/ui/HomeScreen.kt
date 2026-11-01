package com.zigae.argonauts.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.zigae.argonauts.R
import com.zigae.argonauts.engine.CrewEngine
import com.zigae.argonauts.vehicle.Gear
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AppRoot(engine: CrewEngine) {
    val profile by engine.profile.collectAsState()
    val code by engine.code.collectAsState()
    val ui by engine.ui.collectAsState()
    val gear by engine.gear.gear.collectAsState()
    val preview by engine.drivingPreview.collectAsState()
    val driving = preview || (gear != Gear.PARK && gear != Gear.UNKNOWN)
    Box(Modifier.fillMaxSize().background(Argo.c.bg)) {
        when {
            profile.name.isBlank() -> NameScreen(engine, driving)
            code == null -> HomeScreen(engine, driving)
            ui == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("크루에 연결하는 중…", style = T.head, color = Argo.c.textSub) }
            else -> CrewScreen(engine, ui!!)
        }
        ToastHost(engine, Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
    }
}

@Composable
private fun Brand(modifier: Modifier = Modifier) {
    val c = Argo.c
    RowCentered(modifier, gap = 18.dp) {
        Image(painterResource(R.drawable.ic_launcher), null, Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)))
        Column {
            Text("Argonauts", style = T.display, color = c.text)
            Text("함께 달리는 크루 · 위치·목적지·도착 시간을 실시간으로", style = T.body, color = c.textSub)
        }
    }
}

@Composable
private fun ParkOnly(driving: Boolean) {
    if (driving) Panel(Modifier.fillMaxWidth(), color = Argo.c.warnContainer, border = Argo.c.warn) {
        Text("정차(P) 중에 크루를 만들거나 참여할 수 있어요", style = T.bodyB, color = Argo.c.warn)
    }
}

@Composable
private fun NameScreen(engine: CrewEngine, driving: Boolean) {
    val c = Argo.c
    var name by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.width(880.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Brand()
            Panel(Modifier.fillMaxWidth(), padding = 32.dp) {
                Text("크루에 보일 이름", style = T.title, color = c.text)
                Text("친구들이 지도와 목록에서 보는 이름이에요. 최대 10자.", style = T.body, color = c.textSub)
                Spacer(Modifier.height(20.dp))
                RowCentered {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(10) }, singleLine = true, enabled = !driving,
                        modifier = Modifier.weight(1f), textStyle = T.title.copy(color = c.text),
                        placeholder = { Text("예: 건우", style = T.title, color = c.textSub) },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.primary, unfocusedBorderColor = c.outline),
                    )
                    BigButton("시작하기", { engine.setName(name) }, enabled = name.isNotBlank() && !driving, icon = Icons.Filled.ArrowForward)
                }
            }
            ParkOnly(driving)
        }
    }
}

@Composable
private fun HomeScreen(engine: CrewEngine, driving: Boolean) {
    val c = Argo.c
    val scope = rememberCoroutineScope()
    val profile by engine.profile.collectAsState()
    val navi by engine.navi.state.collectAsState()
    var crewName by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var joining by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Brand(Modifier.weight(1f))
            Text("${profile.name}님", style = T.head, color = c.text)
            IconButton({ settings = true }, Modifier.size(72.dp), enabled = !driving) { Icon(Icons.Filled.Settings, "설정", tint = c.textSub, modifier = Modifier.size(36.dp)) }
        }
        ParkOnly(driving)
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Panel(Modifier.weight(1f).fillMaxHeight(), padding = 36.dp) {
                Chip("리더", c.primary, c.onPrimary)
                Spacer(Modifier.height(16.dp))
                Text("크루 만들기", style = T.display, color = c.text)
                Text("지금 내비 목적지가 크루 목적지가 돼요. QR로 친구를 초대하세요.", style = T.body, color = c.textSub)
                Spacer(Modifier.height(24.dp))
                Panel(Modifier.fillMaxWidth(), color = c.surfaceAlt, padding = 18.dp) {
                    Text("크루 목적지", style = T.label, color = c.textSub)
                    Text(navi.destination?.place?.name ?: "내비 목적지 없음 · 크루에서 정할 수 있어요", style = T.bodyB, color = c.text)
                }
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = crewName, onValueChange = { crewName = it.take(14) }, singleLine = true, enabled = !driving,
                    modifier = Modifier.fillMaxWidth(), textStyle = T.body.copy(color = c.text),
                    placeholder = { Text("크루 이름 (예: 가평 캠핑)", style = T.body, color = c.textSub) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.primary, unfocusedBorderColor = c.outline),
                )
                Spacer(Modifier.weight(1f))
                BigButton("크루 만들기", { engine.createCrew(crewName) }, Modifier.fillMaxWidth(), enabled = !driving, icon = Icons.Filled.Add)
            }
            Panel(Modifier.weight(1f).fillMaxHeight(), padding = 36.dp) {
                Chip("크루원", c.surfaceAlt, c.text)
                Spacer(Modifier.height(16.dp))
                Text("코드로 참여", style = T.display, color = c.text)
                Text("리더 화면의 6자리 크루 코드를 입력하세요.", style = T.body, color = c.textSub)
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    value = code, onValueChange = { code = it.uppercase().filter(Char::isLetterOrDigit).take(6); error = null }, singleLine = true, enabled = !driving && !joining,
                    modifier = Modifier.fillMaxWidth(), textStyle = T.mono.copy(fontSize = androidx.compose.ui.unit.TextUnit(44f, androidx.compose.ui.unit.TextUnitType.Sp), color = c.text),
                    placeholder = { Text("ABC123", style = T.mono.copy(fontSize = androidx.compose.ui.unit.TextUnit(44f, androidx.compose.ui.unit.TextUnitType.Sp)), color = c.outline) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.primary, unfocusedBorderColor = c.outline),
                )
                error?.let { Text(it, style = T.bodyB, color = c.danger, modifier = Modifier.padding(top = 10.dp)) }
                Spacer(Modifier.weight(1f))
                BigButton(
                    if (joining) "찾는 중…" else "참여하기",
                    {
                        joining = true
                        scope.launch {
                            if (!engine.joinCrew(code)) error = "크루를 찾을 수 없어요. 코드를 확인해 주세요."
                            joining = false
                        }
                    },
                    Modifier.fillMaxWidth(), enabled = !driving && code.length == 6 && !joining, icon = Icons.Filled.ArrowForward,
                )
            }
        }
    }
    if (settings) SettingsDialog(engine, inCrew = false) { settings = false }
}

@Composable
fun ToastHost(engine: CrewEngine, modifier: Modifier) {
    val c = Argo.c
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        engine.toasts.collect { m ->
            msg = m
            launch { delay(3_500); if (msg == m) msg = null }
        }
    }
    AnimatedVisibility(msg != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        Text(
            msg ?: "", style = T.bodyB, color = c.onPrimary,
            modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(c.primary).padding(horizontal = 28.dp, vertical = 16.dp),
        )
    }
}
