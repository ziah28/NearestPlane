package com.connor.nearestplane

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

// ------------------------------------------------------------------ styling

/** The widget's own colours, light and dark, so the app and the tile match. */
data class Palette(
    val ground: Color,
    val card: Color,
    val ink: Color,
    val muted: Color,
    val rule: Color,
    val track: Color,
    val badge: Color,
    val accentFill: Color,
    val onAccent: Color,
    val accentText: Color,
    val ok: Color,
    val okFill: Color,
    val late: Color,
    val lateFill: Color,
    val warn: Color
) {
    companion object {
        val Light = Palette(
            ground = Color(0xFFF6F7F9), card = Color(0xFFFFFFFF), ink = Color(0xFF0F1B2D),
            muted = Color(0xFF5A6472), rule = Color(0xFFE4E8EE), track = Color(0xFFB8C0CC), badge = Color(0xFFEEF1F6),
            accentFill = Color(0xFF2F6FED), onAccent = Color(0xFFFFFFFF), accentText = Color(0xFF2F6FED),
            ok = Color(0xFF1B7F45), okFill = Color(0xFFE6F4EC),
            late = Color(0xFFB45309), lateFill = Color(0xFFFDF0E1), warn = Color(0xFFB4400B)
        )
        val Dark = Palette(
            ground = Color(0xFF0B0D10), card = Color(0xFF12161D), ink = Color(0xFFEEF1F5),
            muted = Color(0xFF98A1AE), rule = Color(0xFF262C36), track = Color(0xFF3A4250), badge = Color(0xFF222833),
            accentFill = Color(0xFF8EC5FF), onAccent = Color(0xFF0B0D10), accentText = Color(0xFF8EC5FF),
            ok = Color(0xFF5BD48A), okFill = Color(0xFF16301F),
            late = Color(0xFFF5A524), lateFill = Color(0xFF33240D), warn = Color(0xFFFF8A4C)
        )
    }
}

val LocalPalette = staticCompositionLocalOf { Palette.Light }

internal val SpaceGrotesk = FontFamily(Font(R.font.space_grotesk_bold, FontWeight.Bold))

internal fun display(size: androidx.compose.ui.unit.TextUnit) =
    TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = size)

internal fun body(size: androidx.compose.ui.unit.TextUnit, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontWeight = weight, fontSize = size)
