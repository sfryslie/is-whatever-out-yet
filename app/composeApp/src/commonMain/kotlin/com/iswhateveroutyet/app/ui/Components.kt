package com.iswhateveroutyet.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.iswhateveroutyet.app.logic.CardTone
import com.iswhateveroutyet.app.logic.Resolved
import com.iswhateveroutyet.app.logic.formatDate
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The H1. With [heroLabel] set (single search result), "whatever" gets the X-crossthrough and
 * the item name floats above it in Comic Neue — same gag as the site's ?search= deep links.
 */
@Composable
fun SiteHeader(font: FontFamily, comicFont: FontFamily, heroLabel: String? = null) {
    val palette = LocalPalette.current
    val titleStyle = TextStyle(
        color = palette.text,
        fontFamily = font,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        letterSpacing = (-0.03).em,
    )
    Box(
        Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (heroLabel == null) {
            Text("is whatever out yet?", style = titleStyle, textAlign = TextAlign.Center)
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("is ", style = titleStyle)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        heroLabel,
                        color = palette.text,
                        fontFamily = comicFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    CrossedOutWord("whatever", titleStyle.copy(color = palette.muted), palette.text)
                }
                Text(" out yet?", style = titleStyle)
            }
        }
    }
}

/** The site's .title-del: two slightly rotated bars over the word — an X-ish scribble, not a <del>. */
@Composable
private fun CrossedOutWord(word: String, style: TextStyle, barColor: Color) {
    Text(
        word,
        style = style,
        modifier = Modifier.drawWithContent {
            drawContent()
            val bar = Size(size.width * 1.12f, 3.dp.toPx())
            val topLeft = Offset(-size.width * 0.06f, size.height * 0.48f)
            val radius = CornerRadius(2.dp.toPx())
            listOf(-11f, 11f).forEach { degrees ->
                rotate(degrees) {
                    drawRoundRect(color = barColor, topLeft = topLeft, size = bar, cornerRadius = radius)
                }
            }
        },
    )
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, font: FontFamily) {
    val palette = LocalPalette.current
    Box(Modifier.fillMaxWidth().padding(bottom = 24.dp), contentAlignment = Alignment.Center) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = palette.text, fontFamily = font, fontSize = 15.sp),
            cursorBrush = SolidColor(palette.text),
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(palette.surface, RoundedCornerShape(8.dp))
                        .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                        .padding(horizontal = 15.dp, vertical = 12.dp),
                ) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(
                                "Filter by name…",
                                color = palette.muted,
                                fontFamily = font,
                                fontSize = 15.sp,
                            )
                        }
                        inner()
                    }
                    if (value.isNotEmpty()) {
                        Text(
                            "×",
                            color = palette.muted,
                            fontFamily = font,
                            fontSize = 18.sp,
                            modifier = Modifier
                                .clickable { onChange("") }
                                .padding(start = 10.dp),
                        )
                    }
                }
            },
        )
    }
}

@Composable
fun Bell(on: Boolean, description: String, onClick: () -> Unit, size: Int = 16) {
    val palette = LocalPalette.current
    Icon(
        if (on) Icons.Filled.Notifications else Icons.Outlined.Notifications,
        contentDescription = description,
        tint = if (on) palette.other else palette.muted,
        modifier = Modifier
            .size(size.dp + 12.dp)
            .clickable(onClick = onClick)
            .padding(6.dp)
            .alpha(if (on) 1f else 0.55f),
    )
}

@Composable
fun CategoryHeader(
    name: String,
    pushEnabled: Boolean,
    bellOn: Boolean,
    onBell: () -> Unit,
    font: FontFamily,
) {
    val palette = LocalPalette.current
    Column(Modifier.padding(top = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name.uppercase(),
                color = palette.muted,
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 0.12.em,
            )
            Spacer(Modifier.weight(1f))
            if (pushEnabled) Bell(bellOn, "Notify me about all $name", onBell)
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = palette.border, thickness = 1.dp)
    }
}

private data class CardColors(val bg: Color, val border: Color, val answer: Color)

@Composable
private fun cardColors(tone: CardTone): CardColors {
    val p = LocalPalette.current
    return when (tone) {
        CardTone.YES -> CardColors(p.yesBg, p.yesBorder, p.yes)
        CardTone.SOON -> CardColors(p.soonBg, p.soonBorder, p.soon)
        CardTone.GONE -> CardColors(p.goneBg, p.goneBorder, p.gone)
        CardTone.NO -> CardColors(p.surface, p.border, p.noAnswer)
        CardTone.OTHER -> CardColors(p.surface, p.border, p.other)
    }
}

@Composable
fun ItemCard(
    resolved: Resolved,
    pushEnabled: Boolean,
    bellOn: Boolean,
    onBell: () -> Unit,
    font: FontFamily,
) {
    val palette = LocalPalette.current
    val colors = cardColors(resolved.tone)
    Box(
        Modifier
            .background(colors.bg, RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 15.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                resolved.item.label,
                color = palette.muted,
                fontFamily = font,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = if (pushEnabled) 20.dp else 0.dp),
            )
            Text(
                resolved.answer,
                color = colors.answer,
                fontFamily = font,
                fontWeight = FontWeight.SemiBold,
                fontSize = 30.sp,
                letterSpacing = (-0.04).em,
                lineHeight = 34.sp,
            )
            if (resolved.countdownLabel != null) {
                Column {
                    // Always the blue accent, regardless of card class — "No, but here's when".
                    Text(
                        resolved.countdownLabel,
                        color = palette.other,
                        fontFamily = font,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                    )
                    if (resolved.countdownSub != null) {
                        Text(
                            resolved.countdownSub,
                            color = palette.muted,
                            fontFamily = font,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            if (!resolved.detail.isNullOrEmpty()) {
                Text(
                    detailToAnnotated(resolved.detail),
                    color = palette.muted,
                    fontFamily = font,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }
        if (pushEnabled) {
            Box(Modifier.align(Alignment.TopEnd)) {
                Bell(bellOn, "Notify me about ${resolved.item.label}", onBell, size = 14)
            }
        }
    }
}

@Composable
fun Hero(
    resolved: Resolved,
    pushEnabled: Boolean,
    bellOn: Boolean,
    onBell: () -> Unit,
    font: FontFamily,
) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // No item-name line here — the crossed-out title (SiteHeader) carries the name.
        Text(
            resolved.answer,
            color = palette.text,
            fontFamily = font,
            fontWeight = FontWeight.Bold,
            fontSize = 72.sp,
            letterSpacing = (-0.04).em,
            textAlign = TextAlign.Center,
            lineHeight = 76.sp,
        )
        if (resolved.countdownLabel != null) {
            Text(
                resolved.countdownLabel,
                color = palette.other,
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                textAlign = TextAlign.Center,
            )
            if (resolved.countdownSub != null) {
                Text(
                    resolved.countdownSub,
                    color = palette.muted,
                    fontFamily = font,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (!resolved.detail.isNullOrEmpty()) {
            Text(
                detailToAnnotated(resolved.detail),
                color = palette.muted,
                fontFamily = font,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp).padding(top = 4.dp),
            )
        }
        if (pushEnabled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .padding(top = 20.dp)
                    .border(
                        1.dp,
                        if (bellOn) palette.other else palette.border,
                        RoundedCornerShape(999.dp),
                    )
                    .clickable(onClick = onBell)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Icon(
                    if (bellOn) Icons.Filled.Notifications else Icons.Outlined.Notifications,
                    contentDescription = null,
                    tint = if (bellOn) palette.other else palette.muted,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "Notify me",
                    color = if (bellOn) palette.other else palette.muted,
                    fontFamily = font,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
fun NoResults(font: FontFamily) {
    val palette = LocalPalette.current
    Text(
        "I don't know.",
        color = palette.text,
        fontFamily = font,
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp,
        letterSpacing = (-0.035).em,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
    )
}

@Composable
fun ErrorText(font: FontFamily) {
    Text(
        "Could not load data — check your connection and pull to refresh.",
        color = Color(0xFFEF4444),
        fontFamily = font,
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
    )
}

@Composable
fun SkeletonCard() {
    val palette = LocalPalette.current
    val transition = rememberInfiniteTransition()
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )
    Column(
        Modifier
            .alpha(pulse)
            .background(palette.surface, RoundedCornerShape(12.dp))
            .border(1.dp, palette.border, RoundedCornerShape(12.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.height(11.dp).fillMaxWidth(0.5f).background(palette.border, RoundedCornerShape(4.dp)))
        Box(Modifier.height(40.dp).fillMaxWidth(0.35f).background(palette.border, RoundedCornerShape(4.dp)))
    }
}

/** "Jul 1, 2026, 6:20 PM" in the device's timezone. */
fun formatTimestamp(instant: Instant): String {
    val dt = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val ampm = if (dt.hour < 12) "AM" else "PM"
    val hour = ((dt.hour + 11) % 12) + 1
    val minute = dt.minute.toString().padStart(2, '0')
    return "${formatDate(dt.date)}, $hour:$minute $ampm"
}

@Composable
fun Footer(updated: String, lastChecked: Instant?, font: FontFamily) {
    val palette = LocalPalette.current
    val uriHandler = LocalUriHandler.current
    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val updatedInstant = try {
            Instant.parse(updated)
        } catch (e: Exception) {
            null
        }
        if (updatedInstant != null) {
            Text(
                "Last updated: ${formatTimestamp(updatedInstant)}",
                color = palette.muted,
                fontFamily = font,
                fontSize = 12.sp,
            )
        }
        if (lastChecked != null) {
            Text(
                "Last checked: ${formatTimestamp(lastChecked)}",
                color = palette.muted,
                fontFamily = font,
                fontSize = 12.sp,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .padding(top = 8.dp)
                .border(1.dp, palette.border, RoundedCornerShape(999.dp))
                .clickable { uriHandler.openUri("https://buymeacoffee.com/sfryslie") }
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text("☕", fontSize = 13.sp)
            Text(
                "buy me a coffee",
                color = palette.muted,
                fontFamily = font,
                fontSize = 13.sp,
            )
        }
    }
}
