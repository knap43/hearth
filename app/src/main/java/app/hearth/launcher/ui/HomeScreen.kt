package app.hearth.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hearth.launcher.Slot
import app.hearth.launcher.data.AppEntry
import app.hearth.launcher.data.Gesture
import kotlin.math.abs
import kotlin.math.roundToInt

/** Lets a swipe up drag the drawer open under the finger instead of after release. */
interface DrawerFollow {
    /** Finger moved up by [px] (negative when moving back down). */
    fun drag(px: Float)

    /** Finger lifted; [upVelocity] in px/s, positive upward. */
    fun release(upVelocity: Float)
}

@Composable
fun HomeScreen(
    home: List<AppEntry>,
    dock: List<AppEntry>,
    columns: Int,
    onLaunch: (AppEntry) -> Unit,
    menuFor: (AppEntry, Slot) -> List<MenuItem>,
    onGesture: (Gesture) -> Unit,
    homeMenu: List<MenuItem>,
    onClockClick: () -> Unit,
    weather: String?,
    onWeatherClick: () -> Unit,
    follow: DrawerFollow?,
    modifier: Modifier = Modifier,
) {
    val gesture by rememberUpdatedState(onGesture)
    val follower by rememberUpdatedState(follow)
    val haptics = LocalHapticFeedback.current
    var menuAt by remember { mutableStateOf<Offset?>(null) }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val threshold = 56.dp.toPx()
                var travel = Offset.Zero
                var following: DrawerFollow? = null
                val velocity = VelocityTracker()
                detectDragGestures(
                    onDragStart = {
                        travel = Offset.Zero
                        following = null
                        velocity.resetTracking()
                    },
                    onDragEnd = {
                        val f = following
                        if (f != null) f.release(-velocity.calculateVelocity().y)
                        else swipeOf(travel, threshold)?.let(gesture)
                    },
                    onDragCancel = { following?.release(0f) },
                    onDrag = { change, amount ->
                        change.consume()
                        travel += amount
                        velocity.addPointerInputChange(change)
                        val f = following
                        if (f != null) {
                            f.drag(-amount.y)
                        } else if (travel.y < 0f && abs(travel.y) > abs(travel.x)) {
                            // An upward drag: hand it to the drawer, including the slop so far.
                            follower?.let {
                                following = it
                                it.drag(-travel.y)
                            }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { gesture(Gesture.DoubleTap) },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuAt = it
                    },
                )
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 12.dp),
        ) {
            StatusHeader(
                onClockClick = onClockClick,
                weather = weather,
                onWeatherClick = onWeatherClick,
                modifier = Modifier.padding(start = 12.dp, top = 28.dp),
            )
            Spacer(Modifier.weight(1f))

            if (home.isEmpty() && dock.isEmpty()) {
                Text(
                    text = "Swipe up for your apps.\nLong-press one to keep it here.",
                    style = LabelStyle.copy(fontSize = 15.sp, color = Color.White.copy(alpha = 0.8f)),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 48.dp),
                )
            }

            home.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { app ->
                        AppIcon(
                            app = app,
                            onClick = { onLaunch(app) },
                            menu = { menuFor(app, Slot.Home) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (dock.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color.White.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    dock.forEach { app ->
                        AppIcon(
                            app = app,
                            onClick = { onLaunch(app) },
                            menu = { menuFor(app, Slot.Dock) },
                            showLabel = false,
                            modifier = Modifier.width(64.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Long-press on empty space: a small menu where the finger landed.
        menuAt?.let { at ->
            Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }) {
                DropdownMenu(expanded = true, onDismissRequest = { menuAt = null }) {
                    homeMenu.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.label) },
                            onClick = {
                                menuAt = null
                                item.onClick()
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun swipeOf(travel: Offset, threshold: Float): Gesture? = when {
    abs(travel.x) < threshold && abs(travel.y) < threshold -> null
    abs(travel.x) > abs(travel.y) -> if (travel.x > 0) Gesture.SwipeRight else Gesture.SwipeLeft
    else -> if (travel.y < 0) Gesture.SwipeUp else Gesture.SwipeDown
}
