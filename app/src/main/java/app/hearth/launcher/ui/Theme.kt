package app.hearth.launcher.ui

import android.app.WallpaperManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

val Ember = Color(0xFFFFB067)

/** Hand-picked accents, from the hearth outward. */
val AccentPresets: List<Pair<String, Color>> = listOf(
    "Ember" to Ember,
    "Rowan" to Color(0xFFE8755F),
    "Heather" to Color(0xFFC89BD8),
    "Fjord" to Color(0xFF7FB2D9),
    "Frost" to Color(0xFFCFE3EC),
    "Moss" to Color(0xFF9CC08A),
)

const val ACCENT_WALLPAPER = "wallpaper"
const val ACCENT_SYSTEM = "system"

fun Color.toSpec(): String = "#%06X".format(toArgb() and 0xFFFFFF)

private fun parseSpec(spec: String): Color? =
    runCatching { Color(android.graphics.Color.parseColor(spec)) }.getOrNull()

/** Resolves an accent spec ("wallpaper", "system" or "#RRGGBB") to a colour, live. */
@Composable
fun rememberAccent(spec: String): Color {
    val context = LocalContext.current
    return when {
        spec == ACCENT_SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicDarkColorScheme(context).primary
        spec == ACCENT_WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 ->
            rememberWallpaperColor()?.asAccent() ?: Ember
        else -> parseSpec(spec) ?: Ember
    }
}

@RequiresApi(Build.VERSION_CODES.O_MR1)
@Composable
fun rememberWallpaperColor(): Color? {
    val context = LocalContext.current
    val wm = remember { WallpaperManager.getInstance(context) }
    fun read(): Color? = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.let { Color(it.toArgb()) }

    var color by remember { mutableStateOf(read()) }
    DisposableEffect(wm) {
        val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) {
                color = colors?.primaryColor?.let { Color(it.toArgb()) }
            }
        }
        wm.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        onDispose { wm.removeOnColorsChangedListener(listener) }
    }
    return color
}

/** Wallpapers are often muddy; lift the hue into something that reads as an accent on dark. */
private fun Color.asAccent(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[1] = hsv[1].coerceIn(0.25f, 0.65f)
    hsv[2] = hsv[2].coerceAtLeast(0.88f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** How icons are drawn; tinted icons sit as an accent glyph on a deep accent plate. */
@Immutable
data class IconStyle(val tinted: Boolean, val plate: Color, val glyph: Color) {
    /**
     * For icons without a monochrome layer: map each pixel's luminance onto the
     * plate → glyph gradient, so the original artwork survives as a duotone.
     */
    val duotone: ColorFilter by lazy {
        fun row(dark: Float, light: Float): FloatArray {
            val span = light - dark
            return floatArrayOf(span * 0.2126f, span * 0.7152f, span * 0.0722f, 0f, dark * 255f)
        }
        ColorFilter.colorMatrix(
            ColorMatrix(
                row(plate.red, glyph.red) +
                    row(plate.green, glyph.green) +
                    row(plate.blue, glyph.blue) +
                    floatArrayOf(0f, 0f, 0f, 1f, 0f),
            ),
        )
    }
}

val LocalIconStyle = staticCompositionLocalOf { IconStyle(false, Color.Black, Color.White) }

@Composable
fun HearthTheme(accent: Color, tintIcons: Boolean, content: @Composable () -> Unit) {
    val scheme = remember(accent) {
        darkColorScheme(
            primary = accent,
            onPrimary = if (accent.luminance() > 0.45f) Color(0xFF1A1410) else Color.White,
            background = Color(0xFF101013),
            surface = Color(0xFF17171B),
            surfaceContainer = Color(0xFF1F1F24),
        )
    }
    val iconStyle = remember(accent, tintIcons) {
        IconStyle(
            tinted = tintIcons,
            plate = lerp(Color(0xFF0E0E11), accent, 0.30f),
            glyph = lerp(accent, Color.White, 0.30f),
        )
    }
    CompositionLocalProvider(LocalIconStyle provides iconStyle) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Keeps white text legible over any wallpaper. */
val WallpaperShadow = Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 2f), blurRadius = 6f)

val LabelStyle = TextStyle(color = Color.White, fontSize = 12.sp, shadow = WallpaperShadow)

/** Drawer backdrop: near-opaque normally, sheer when the wallpaper is blurred behind it. */
fun drawerScrim(blurred: Boolean) = if (blurred) Color(0x8C0E0E11) else Color(0xF20E0E11)
