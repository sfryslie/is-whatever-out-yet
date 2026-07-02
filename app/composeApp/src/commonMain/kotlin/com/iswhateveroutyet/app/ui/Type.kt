package com.iswhateveroutyet.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.iswhateveroutyet.app.resources.Res
import com.iswhateveroutyet.app.resources.ibm_plex_mono_bold
import com.iswhateveroutyet.app.resources.ibm_plex_mono_medium
import com.iswhateveroutyet.app.resources.ibm_plex_mono_regular
import com.iswhateveroutyet.app.resources.ibm_plex_mono_semibold
import org.jetbrains.compose.resources.Font

/** IBM Plex Mono, same as the site (bundled; OFL-licensed). */
@Composable
fun plexMono(): FontFamily = FontFamily(
    Font(Res.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(Res.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(Res.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
    Font(Res.font.ibm_plex_mono_bold, FontWeight.Bold),
)
