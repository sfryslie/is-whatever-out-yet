package com.iswhateveroutyet.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Direct port of the CSS custom properties in index.html — dark `:root` and `[data-theme="light"]`. */
data class Palette(
    val bg: Color,
    val surface: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val noAnswer: Color,
    val yes: Color,
    val yesBg: Color,
    val yesBorder: Color,
    val soon: Color,
    val soonBg: Color,
    val soonBorder: Color,
    val other: Color,
    val gone: Color,
    val goneBg: Color,
    val goneBorder: Color,
)

val DarkPalette = Palette(
    bg = Color(0xFF0D0F12),
    surface = Color(0xFF14171C),
    border = Color(0xFF232831),
    text = Color(0xFFE6E9EE),
    muted = Color(0xFF707985),
    noAnswer = Color(0xFF39414C),
    yes = Color(0xFF5FCF90),
    yesBg = Color(0xFF5FCF90).copy(alpha = 0.07f),
    yesBorder = Color(0xFF5FCF90).copy(alpha = 0.28f),
    soon = Color(0xFFD8B24A),
    soonBg = Color(0xFFD8B24A).copy(alpha = 0.08f),
    soonBorder = Color(0xFFD8B24A).copy(alpha = 0.3f),
    other = Color(0xFF8F8CF0),
    gone = Color(0xFF7C8694),
    goneBg = Color(0xFF7C8694).copy(alpha = 0.07f),
    goneBorder = Color(0xFF7C8694).copy(alpha = 0.26f),
)

val LightPalette = Palette(
    bg = Color(0xFFF5F6F8),
    surface = Color(0xFFFFFFFF),
    border = Color(0xFFE4E6EA),
    text = Color(0xFF1A1D22),
    muted = Color(0xFF767D88),
    noAnswer = Color(0xFFB9BEC6),
    yes = Color(0xFF1F9D5B),
    yesBg = Color(0xFF1F9D5B).copy(alpha = 0.08f),
    yesBorder = Color(0xFF1F9D5B).copy(alpha = 0.32f),
    soon = Color(0xFFB9821A),
    soonBg = Color(0xFFB9821A).copy(alpha = 0.09f),
    soonBorder = Color(0xFFB9821A).copy(alpha = 0.32f),
    other = Color(0xFF5B57D6),
    gone = Color(0xFF5B6472),
    goneBg = Color(0xFF5B6472).copy(alpha = 0.07f),
    goneBorder = Color(0xFF5B6472).copy(alpha = 0.3f),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }
