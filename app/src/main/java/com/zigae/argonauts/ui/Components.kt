package com.zigae.argonauts.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 차량 화면 글자 크기 (주행 중 핵심 정보는 크게). 시각·거리는 고정폭 숫자(tnum)로 줄을 맞춘다. */
object T {
    private const val TNUM = "tnum"
    val display = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, lineHeight = 50.sp, fontFeatureSettings = TNUM, letterSpacing = (-0.5).sp)
    val title = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp, letterSpacing = (-0.3).sp)
    val head = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp, fontFeatureSettings = TNUM)
    val body = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Normal, lineHeight = 30.sp, fontFeatureSettings = TNUM)
    val bodyB = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp, fontFeatureSettings = TNUM)
    val label = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
    val small = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp, fontFeatureSettings = TNUM)
    /** 크루 코드: 소리 내 읽어 주는 값이라 넓게, 본문 글꼴 그대로. */
    val code = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, fontFeatureSettings = TNUM)
}

/** 일반 면. 테두리·그림자 없이 배경 대비로만 구분한다. */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 20.dp, color: Color = Argo.c.surface, border: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .background(color)
            .let { if (border != null) it.border(2.dp, border, shape) else it }
            .padding(padding),
        content = content,
    )
}

/**
 * 도로 표지판 면: 단색 바탕 + 안쪽으로 들인 흰 테두리 선(실제 표지판의 테두리).
 * 크루 목적지(파랑)와 모이는 휴게소(초록)에만 쓴다.
 */
@Composable
fun SignPanel(color: Color, modifier: Modifier = Modifier, padding: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    val onSign = Argo.c.onSign
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(color).padding(5.dp)) {
        Column(
            Modifier.fillMaxWidth().border(2.dp, onSign.copy(alpha = 0.85f), RoundedCornerShape(10.dp)).padding(padding),
            content = content,
        )
    }
}

@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, container: Color = Argo.c.primary, content: Color = Argo.c.onPrimary) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 68.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content, disabledContainerColor = Argo.c.surfaceAlt, disabledContentColor = Argo.c.textSub),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(28.dp)); Box(Modifier.size(10.dp)) }
        Text(text, style = T.bodyB, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, tint: Color = Argo.c.text) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 64.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(2.dp, Argo.c.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = tint, disabledContentColor = Argo.c.textSub),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(26.dp)); Box(Modifier.size(8.dp)) }
        Text(text, style = T.label.copy(fontSize = 19.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Chip(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, style = T.label, color = fg, maxLines = 1)
    }
}

@Composable
fun Avatar(initial: String, color: Color, size: Dp = 52.dp, dim: Boolean = false, ring: Color? = null) {
    Box(
        Modifier.size(size).clip(CircleShape).let { if (ring != null) it.border(3.dp, ring, CircleShape) else it }.background(if (dim) Argo.c.outline else color),
        contentAlignment = Alignment.Center,
    ) { Text(initial, style = T.head.copy(fontSize = (size.value * 0.44f).sp), color = Color.White) }
}

@Composable
fun Dot(color: Color, size: Dp = 12.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun RowCentered(modifier: Modifier = Modifier, gap: Dp = 12.dp, content: @Composable RowScope.() -> Unit) =
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap), content = content)

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text, style = T.label, color = Argo.c.textSub, modifier = modifier.padding(bottom = 8.dp))

@Composable
fun ListRow(onClick: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}
