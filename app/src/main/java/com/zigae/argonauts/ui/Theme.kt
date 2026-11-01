package com.zigae.argonauts.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color

/**
 * Argonauts 자체 토큰 (주간/야간).
 * 방향: 남색 계열을 주간 강조, 청록 계열을 야간 강조로 두고, 크루 색은 명도 단계가 고른 6색.
 * 차량 화면 기준(1~2초 안에 파악)으로 글자·터치 영역을 크게 잡는다.
 */
@Immutable
data class ArgoColors(
    val night: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val outline: Color,
    val text: Color,
    val textSub: Color,
    val primary: Color,
    val onPrimary: Color,
    val accent: Color,
    val success: Color,
    val successContainer: Color,
    val warn: Color,
    val warnContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val infoContainer: Color,
    val crew: List<Color>,
) {
    fun crewColor(i: Int) = crew[((i % crew.size) + crew.size) % crew.size]
}

val DayColors = ArgoColors(
    night = false,
    bg = Color(0xFFF2F4F7), surface = Color(0xFFFFFFFF), surfaceAlt = Color(0xFFE8ECF2), outline = Color(0xFFD3D9E2),
    text = Color(0xFF0E1726), textSub = Color(0xFF586378),
    primary = Color(0xFF0B2D5B), onPrimary = Color.White, accent = Color(0xFF0B87A6),
    success = Color(0xFF167A33), successContainer = Color(0xFFE6F3E9),
    warn = Color(0xFF9A5B00), warnContainer = Color(0xFFFBEBD3),
    danger = Color(0xFFB72C27), dangerContainer = Color(0xFFFAE5E4),
    infoContainer = Color(0xFFE3EEFB),
    crew = listOf(Color(0xFF2563EB), Color(0xFF16A34A), Color(0xFFE2581B), Color(0xFF8B3FE0), Color(0xFFD13A35), Color(0xFFC98A00)),
)

val NightColors = ArgoColors(
    night = true,
    bg = Color(0xFF0A111C), surface = Color(0xFF121C2B), surfaceAlt = Color(0xFF1A2639), outline = Color(0xFF2A3850),
    text = Color(0xFFEEF2F8), textSub = Color(0xFF9DAABD),
    primary = Color(0xFF46BCD8), onPrimary = Color(0xFF03141D), accent = Color(0xFF46BCD8),
    success = Color(0xFF5BD07A), successContainer = Color(0xFF12301C),
    warn = Color(0xFFF2B54A), warnContainer = Color(0xFF3A2A0E),
    danger = Color(0xFFF07A74), dangerContainer = Color(0xFF3B1716),
    infoContainer = Color(0xFF15304A),
    crew = listOf(Color(0xFF5B8CFF), Color(0xFF43C46E), Color(0xFFFF8A4C), Color(0xFFB07CFF), Color(0xFFFF6B66), Color(0xFFF2B54A)),
)

val LocalArgo = staticCompositionLocalOf { DayColors }

/** 차량 화면 배율 (160dpi 대형 디스플레이에서 핵심 정보를 크게). */
const val UI_SCALE = 1.25f

object Argo {
    val c: ArgoColors @Composable get() = LocalArgo.current
}

@Composable
fun ArgoTheme(content: @Composable () -> Unit) {
    val night = isSystemInDarkTheme()
    val c = if (night) NightColors else DayColors
    val scheme = if (night) darkColorScheme(primary = c.primary, onPrimary = c.onPrimary, background = c.bg, surface = c.surface, onSurface = c.text, onBackground = c.text)
    else lightColorScheme(primary = c.primary, onPrimary = c.onPrimary, background = c.bg, surface = c.surface, onSurface = c.text, onBackground = c.text)
    val d = LocalDensity.current
    CompositionLocalProvider(LocalArgo provides c, LocalDensity provides Density(d.density * UI_SCALE, d.fontScale)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
