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
 * 방향: 도로 표지판. 길 위의 정보(크루 목적지 = 파란 일반도로 표지, 모이는 휴게소 = 초록 고속도로 표지)만 색을 쓰고,
 * 버튼·글자는 무채색 잉크로 둔다. 화면의 색은 "어디로 가는지"를 뜻한다.
 * 크루 색은 명도 단계가 고른 6색. 차량 화면 기준(1~2초 안에 파악)으로 글자·터치 영역을 크게 잡는다.
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
    /** 버튼 등 조작 (주간 잉크, 야간은 눈부심을 줄이려 차분한 파랑). */
    val primary: Color,
    val onPrimary: Color,
    /** 크루 목적지 표지 (파랑). */
    val sign: Color,
    /** 모이는 휴게소 표지 (초록). */
    val signGreen: Color,
    val onSign: Color,
    val success: Color,
    val successContainer: Color,
    val warn: Color,
    val warnContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val crew: List<Color>,
) {
    fun crewColor(i: Int) = crew[((i % crew.size) + crew.size) % crew.size]
}

val DayColors = ArgoColors(
    night = false,
    bg = Color(0xFFEEF0F2), surface = Color(0xFFFFFFFF), surfaceAlt = Color(0xFFE2E5E9), outline = Color(0xFFCDD2D8),
    text = Color(0xFF15191E), textSub = Color(0xFF59616B),
    primary = Color(0xFF15191E), onPrimary = Color.White,
    sign = Color(0xFF0B4EA2), signGreen = Color(0xFF0E6B3F), onSign = Color.White,
    success = Color(0xFF167A33), successContainer = Color(0xFFE3F1E7),
    warn = Color(0xFF8F5300), warnContainer = Color(0xFFFBEBD3),
    danger = Color(0xFFB72C27), dangerContainer = Color(0xFFFAE5E4),
    crew = listOf(Color(0xFF2563EB), Color(0xFF16A34A), Color(0xFFE2581B), Color(0xFF8B3FE0), Color(0xFFD13A35), Color(0xFFC98A00)),
)

val NightColors = ArgoColors(
    night = true,
    bg = Color(0xFF0F1215), surface = Color(0xFF191D22), surfaceAlt = Color(0xFF242930), outline = Color(0xFF353B44),
    text = Color(0xFFECEEF1), textSub = Color(0xFF9AA2AC),
    primary = Color(0xFF3C77D0), onPrimary = Color.White,
    sign = Color(0xFF1C4F96), signGreen = Color(0xFF145A37), onSign = Color(0xFFF2F4F6),
    success = Color(0xFF5BD07A), successContainer = Color(0xFF14301D),
    warn = Color(0xFFF2B54A), warnContainer = Color(0xFF3A2A0E),
    danger = Color(0xFFF07A74), dangerContainer = Color(0xFF3B1716),
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
