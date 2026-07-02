package com.iswhateveroutyet.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.iswhateveroutyet.app.logic.HIDE_LEVELS
import kotlin.math.roundToInt

@Composable
private fun RowLabel(title: String, sub: String, font: FontFamily) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = palette.text, fontFamily = font, fontSize = 14.sp)
        Text(sub, color = palette.muted, fontFamily = font, fontSize = 11.sp)
    }
}

/** Touch platforms: settings in a Material bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    onDismiss: () -> Unit,
    isLight: Boolean,
    onTheme: (Boolean) -> Unit,
    hideLevel: Int,
    onHideLevel: (Int) -> Unit,
    categories: List<String>,
    hiddenCats: Set<String>,
    onToggleCat: (String) -> Unit,
    pushEnabled: Boolean,
    notifyAllOn: Boolean,
    onNotifyAll: () -> Unit,
    onRefresh: () -> Unit,
    font: FontFamily,
) {
    val palette = LocalPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        contentColor = palette.text,
    ) {
        SettingsContent(
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp),
            onDismiss = onDismiss,
            isLight = isLight, onTheme = onTheme,
            hideLevel = hideLevel, onHideLevel = onHideLevel,
            categories = categories, hiddenCats = hiddenCats, onToggleCat = onToggleCat,
            pushEnabled = pushEnabled, notifyAllOn = notifyAllOn, onNotifyAll = onNotifyAll,
            onRefresh = onRefresh,
            font = font,
        )
    }
}

/**
 * Desktop: settings in a panel anchored to the hamburger button, top-right — same idea as the
 * website's .settings-panel (a bottom sheet is a phone idiom that looks silly under a mouse).
 * Place inside the Box that contains the settings IconButton so the popup anchors to it.
 */
@Composable
fun SettingsPopup(
    onDismiss: () -> Unit,
    isLight: Boolean,
    onTheme: (Boolean) -> Unit,
    hideLevel: Int,
    onHideLevel: (Int) -> Unit,
    categories: List<String>,
    hiddenCats: Set<String>,
    onToggleCat: (String) -> Unit,
    pushEnabled: Boolean,
    notifyAllOn: Boolean,
    onNotifyAll: () -> Unit,
    onRefresh: () -> Unit,
    font: FontFamily,
) {
    val palette = LocalPalette.current
    val buttonGap = with(LocalDensity.current) { 46.dp.roundToPx() }
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, buttonGap),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        SettingsContent(
            modifier = Modifier
                .width(320.dp)
                .heightIn(max = 620.dp)
                .shadow(24.dp, RoundedCornerShape(12.dp))
                .background(palette.surface, RoundedCornerShape(12.dp))
                .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            onDismiss = onDismiss,
            isLight = isLight, onTheme = onTheme,
            hideLevel = hideLevel, onHideLevel = onHideLevel,
            categories = categories, hiddenCats = hiddenCats, onToggleCat = onToggleCat,
            pushEnabled = pushEnabled, notifyAllOn = notifyAllOn, onNotifyAll = onNotifyAll,
            onRefresh = onRefresh,
            font = font,
        )
    }
}

@Composable
private fun SettingsContent(
    modifier: Modifier,
    onDismiss: () -> Unit,
    isLight: Boolean,
    onTheme: (Boolean) -> Unit,
    hideLevel: Int,
    onHideLevel: (Int) -> Unit,
    categories: List<String>,
    hiddenCats: Set<String>,
    onToggleCat: (String) -> Unit,
    pushEnabled: Boolean,
    notifyAllOn: Boolean,
    onNotifyAll: () -> Unit,
    onRefresh: () -> Unit,
    font: FontFamily,
) {
    val palette = LocalPalette.current
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
            // Light mode
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowLabel("Light mode", "Switch the color theme", font)
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = isLight,
                    onCheckedChange = onTheme,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = palette.other,
                        uncheckedTrackColor = palette.noAnswer,
                    ),
                )
            }

            // Hide old stuff
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RowLabel("Hide old stuff", HIDE_LEVELS[hideLevel].label, font)
                Slider(
                    value = hideLevel.toFloat(),
                    onValueChange = { onHideLevel(it.roundToInt().coerceIn(HIDE_LEVELS.indices)) },
                    valueRange = 0f..(HIDE_LEVELS.lastIndex).toFloat(),
                    steps = HIDE_LEVELS.size - 2,
                    colors = SliderDefaults.colors(
                        thumbColor = palette.other,
                        activeTrackColor = palette.other,
                        inactiveTrackColor = palette.noAnswer,
                    ),
                )
            }

            // Category visibility
            if (categories.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    RowLabel("Categories", "Show or hide whole sections", font)
                    categories.forEach { cat ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleCat(cat) },
                        ) {
                            Checkbox(
                                checked = cat !in hiddenCats,
                                onCheckedChange = { onToggleCat(cat) },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = palette.other,
                                    uncheckedColor = palette.muted,
                                ),
                            )
                            Text(cat, color = palette.text, fontFamily = font, fontSize = 13.sp)
                        }
                    }
                }
            }

            // Notify me about everything
            if (pushEnabled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onNotifyAll),
                ) {
                    RowLabel("Notify me about everything", "Push when anything flips", font)
                    Spacer(Modifier.weight(1f))
                    Bell(notifyAllOn, "Notify me about everything", onNotifyAll)
                }
            }

            // Refresh (handy on desktop where there's no pull gesture)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable {
                    onRefresh()
                    onDismiss()
                },
            ) {
                RowLabel("Refresh", "Re-fetch the latest data", font)
            }

            Text(
                "MIT licensed · data straight off iswhateveroutyet.com",
                color = palette.muted,
                fontFamily = font,
                fontWeight = FontWeight.Normal,
                fontSize = 10.sp,
            )
    }
}
