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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 차량 화면 글자 크기 (주행 중 핵심 정보는 크게). */
object T {
    val display = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp)
    val title = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp)
    val head = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp)
    val body = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Normal, lineHeight = 30.sp)
    val bodyB = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
    val label = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
    val small = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp)
    val mono = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp)
}

@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 20.dp, color: Color = Argo.c.surface, border: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .clip(shape)
            .background(color)
            .let { if (border != null) it.border(2.dp, border, shape) else it }
            .padding(padding),
        content = content,
    )
}

@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, container: Color = Argo.c.primary, content: Color = Argo.c.onPrimary) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 68.dp),
        shape = RoundedCornerShape(16.dp),
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
        shape = RoundedCornerShape(16.dp),
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
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 12.dp, vertical = 5.dp)) {
        Text(text, style = T.label, color = fg, maxLines = 1)
    }
}

@Composable
fun Avatar(initial: String, color: Color, size: Dp = 52.dp, dim: Boolean = false) {
    Box(
        Modifier.size(size).clip(CircleShape).background(if (dim) Argo.c.outline else color),
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
fun ListRow(onClick: (() -> Unit)?, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}
