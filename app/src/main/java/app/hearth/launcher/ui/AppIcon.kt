package app.hearth.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hearth.launcher.data.AppEntry

data class MenuItem(val label: String, val onClick: () -> Unit)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppIcon(
    app: AppEntry,
    onClick: () -> Unit,
    menu: (() -> List<MenuItem>)?,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    /** 1 = warm, 0 = ash. Cooling apps lose colour first, then presence. */
    warmth: Float = 1f,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    Box(modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (warmth < 1f) Modifier.graphicsLayer { alpha = 0.4f + 0.6f * warmth } else Modifier)
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = menu?.let {
                        {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        }
                    },
                )
                .padding(horizontal = 4.dp, vertical = 8.dp),
        ) {
            AppGlyph(app, warmth, Modifier.size(52.dp))
            if (showLabel) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = app.label,
                    style = LabelStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (menu != null && menuOpen) {
            DropdownMenu(expanded = true, onDismissRequest = { menuOpen = false }) {
                menu().forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.label) },
                        onClick = {
                            menuOpen = false
                            item.onClick()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppGlyph(app: AppEntry, warmth: Float, modifier: Modifier) {
    val style = LocalIconStyle.current
    val mono = app.monochrome
    when {
        style.tinted && mono != null -> Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .clip(CircleShape)
                .background(style.plate),
        ) {
            Image(
                bitmap = mono,
                contentDescription = app.label,
                colorFilter = ColorFilter.tint(style.glyph),
                modifier = Modifier.fillMaxSize(),
            )
        }

        style.tinted -> Image(
            bitmap = app.icon,
            contentDescription = app.label,
            colorFilter = style.duotone,
            modifier = modifier,
        )

        else -> Image(
            bitmap = app.icon,
            contentDescription = app.label,
            colorFilter = if (warmth < 1f) {
                remember(warmth) { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(warmth) }) }
            } else {
                null
            },
            modifier = modifier,
        )
    }
}
