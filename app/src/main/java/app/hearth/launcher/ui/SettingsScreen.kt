package app.hearth.launcher.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Switch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hearth.launcher.data.Gesture
import app.hearth.launcher.data.GestureAction
import app.hearth.launcher.data.Prefs

@Composable
fun SettingsScreen(
    gestures: Map<Gesture, GestureAction>,
    labelFor: (String) -> String?,
    columns: Int,
    lockReady: Boolean,
    onGesture: (Gesture, GestureAction) -> Unit,
    onPickApp: (Gesture) -> Unit,
    onColumns: (Int) -> Unit,
    onWallpaper: () -> Unit,
    onDefaultHome: () -> Unit,
    onAccessibility: () -> Unit,
    appearance: Appearance,
    onAppearance: (Appearance) -> Unit,
    blurSupported: Boolean,
    usageAccess: Boolean,
    onUsageAccess: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = if (appearance.blur && blurSupported) 0.72f else 1f))
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 16.dp),
        ) {
            Text(
                "Settings",
                color = Color.White,
                fontSize = 30.sp,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            )

            SectionTitle("Gestures")
            Gesture.entries.forEach { g ->
                GestureRow(
                    gesture = g,
                    action = gestures[g] ?: GestureAction.None,
                    labelFor = labelFor,
                    onSelect = { onGesture(g, it) },
                    onPickApp = { onPickApp(g) },
                )
            }
            if (!lockReady && GestureAction.LockScreen in gestures.values) {
                SettingRow(
                    title = "Allow screen lock",
                    subtitle = "Turn on “Hearth screen lock” in Accessibility",
                    accent = true,
                    onClick = onAccessibility,
                )
            }

            SectionTitle("Appearance")
            AccentPicker(appearance.accent) { onAppearance(appearance.copy(accent = it)) }
            SwitchRow(
                title = "Tinted icons",
                subtitle = "Every icon redrawn in your accent",
                checked = appearance.tintIcons,
                onChange = { onAppearance(appearance.copy(tintIcons = it)) },
            )
            SwitchRow(
                title = "Blur wallpaper",
                subtitle = if (blurSupported) "Behind the drawer and settings"
                else "Not available now — needs Android 12+, and battery saver must be off",
                checked = appearance.blur,
                enabled = blurSupported,
                onChange = { onAppearance(appearance.copy(blur = it)) },
            )

            SectionTitle("Embers")
            SwitchRow(
                title = "Let unused apps cool",
                subtitle = "They fade in the drawer, then gather in Ashes",
                checked = appearance.embers,
                onChange = { onAppearance(appearance.copy(embers = it)) },
            )
            if (appearance.embers) {
                ChoiceRow(
                    title = "Turn to ash after",
                    options = listOf(2, 4, 8),
                    label = { "$it wk" },
                    selected = appearance.coldWeeks,
                    onSelect = { onAppearance(appearance.copy(coldWeeks = it)) },
                )
                if (!usageAccess) {
                    SettingRow(
                        title = "Count apps opened elsewhere",
                        subtitle = "Grant usage access so notifications and shares keep apps warm",
                        accent = true,
                        onClick = onUsageAccess,
                    )
                }
            }

            SectionTitle("Home screen")
            ChoiceRow(
                title = "Drawer order",
                options = listOf(Prefs.ORDER_USAGE, Prefs.ORDER_ALPHA),
                label = { if (it == Prefs.ORDER_USAGE) "Most used" else "A–Z" },
                selected = appearance.drawerOrder,
                onSelect = { onAppearance(appearance.copy(drawerOrder = it)) },
            )
            ChoiceRow(
                title = "Columns",
                options = listOf(4, 5),
                label = { "$it" },
                selected = columns,
                onSelect = onColumns,
            )
            SwitchRow(
                title = "Weather",
                subtitle = "Under the clock, from Open-Meteo, at your approximate location",
                checked = appearance.weather,
                onChange = { onAppearance(appearance.copy(weather = it)) },
            )
            SettingRow("Wallpaper", "Choose a new background", onClick = onWallpaper)
            SettingRow("Default home app", "Make Hearth your home screen", onClick = onDefaultHome)
        }
    }
}

/** Everything about how Hearth looks, edited as one value. */
data class Appearance(
    val accent: String,
    val tintIcons: Boolean,
    val blur: Boolean,
    val embers: Boolean,
    val coldWeeks: Int,
    val drawerOrder: String,
    val weather: Boolean,
)

@Composable
private fun AccentPicker(selected: String, onSelect: (String) -> Unit) {
    val options = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) add("Wallpaper" to ACCENT_WALLPAPER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add("System" to ACCENT_SYSTEM)
        AccentPresets.forEach { (name, color) -> add(name to color.toSpec()) }
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("Accent", color = Color.White, fontSize = 16.sp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(top = 12.dp),
        ) {
            options.forEach { (name, spec) ->
                val isSelected = spec.equals(selected, ignoreCase = true)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(spec) }
                        .padding(4.dp),
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .border(2.dp, if (isSelected) Color.White else Color.Transparent, CircleShape)
                            .padding(5.dp)
                            .clip(CircleShape)
                            .background(rememberAccent(spec)),
                    )
                    Text(
                        name,
                        color = Color.White.copy(alpha = if (isSelected) 1f else 0.6f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White.copy(alpha = if (enabled) 1f else 0.5f), fontSize = 16.sp)
            Text(subtitle, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
        Switch(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    label: (T) -> String,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val isSelected = option == selected
                Text(
                    text = label(option),
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else Color.White.copy(alpha = 0.08f),
                        )
                        .clickable { onSelect(option) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun GestureRow(
    gesture: Gesture,
    action: GestureAction,
    labelFor: (String) -> String?,
    onSelect: (GestureAction) -> Unit,
    onPickApp: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingRow(gesture.label, action.describe(labelFor)) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                GestureAction.None,
                GestureAction.Drawer,
                GestureAction.Search,
                GestureAction.Notifications,
                GestureAction.LockScreen,
            ).forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.describe(labelFor)) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Open an app…") },
                onClick = {
                    open = false
                    onPickApp()
                },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) = Text(
    text = text.uppercase(),
    color = MaterialTheme.colorScheme.primary,
    fontSize = 12.sp,
    letterSpacing = 1.2.sp,
    modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
)

@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, color = if (accent) MaterialTheme.colorScheme.primary else Color.White, fontSize = 16.sp)
        if (subtitle != null) {
            Text(subtitle, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
    }
}

private fun GestureAction.describe(labelFor: (String) -> String?): String = when (this) {
    GestureAction.None -> "Nothing"
    GestureAction.Drawer -> "Open app drawer"
    GestureAction.Search -> "Search apps"
    GestureAction.Notifications -> "Pull down notifications"
    GestureAction.LockScreen -> "Lock screen"
    is GestureAction.Launch -> labelFor(key)?.let { "Open $it" } ?: "Open an app (missing)"
}
