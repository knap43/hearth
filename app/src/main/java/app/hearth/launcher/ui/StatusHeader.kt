package app.hearth.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private data class Battery(val level: Int, val charging: Boolean)

@Composable
fun StatusHeader(
    onClockClick: () -> Unit,
    weather: String?,
    onWeatherClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val now = rememberNow()
    val battery = rememberBattery()

    val locale = context.resources.configuration.locales[0]
    val timePattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    val time = now.format(DateTimeFormatter.ofPattern(timePattern, locale))
    val date = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale))
        .replaceFirstChar { it.titlecase(locale) }

    Column(modifier) {
        Text(
            text = time,
            style = TextStyle(
                color = Color.White,
                fontSize = 68.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-1).sp,
                shadow = WallpaperShadow,
            ),
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClockClick,
            ),
        )
        Text(
            text = buildString {
                append(date)
                battery?.let {
                    append("  ·  ${it.level}%")
                    if (it.charging) append(" ⚡")
                }
            },
            style = TextStyle(color = Color.White.copy(alpha = 0.9f), fontSize = 16.sp, shadow = WallpaperShadow),
        )
        if (weather != null) {
            Text(
                text = weather,
                style = TextStyle(color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp, shadow = WallpaperShadow),
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onWeatherClick,
                    ),
            )
        }
    }
}

@Composable
private fun rememberNow(): LocalDateTime {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }

    // The minute tick stops while the screen sleeps; catch up whenever we're shown.
    LifecycleResumeEffect(Unit) {
        now = LocalDateTime.now()
        onPauseOrDispose { }
    }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                now = LocalDateTime.now()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return now
}

@Composable
private fun rememberBattery(): Battery? {
    val context = LocalContext.current
    var battery by remember { mutableStateOf<Battery?>(null) }

    DisposableEffect(Unit) {
        fun parse(i: Intent): Battery {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            return Battery(level * 100 / scale, charging)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i != null) battery = parse(i)
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )?.let { battery = parse(it) }
        onDispose { context.unregisterReceiver(receiver) }
    }
    return battery
}
