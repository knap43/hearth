package app.hearth.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.flow.first
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hearth.launcher.data.AppEntry
import app.hearth.launcher.data.Fuzzy

/**
 * The full app list with fuzzy search. Doubles as an app picker for gesture
 * bindings (pass a [title], no [menuFor], no [onOpenSettings]).
 *
 * The drawer stays composed while hidden, so opening it is only a layer
 * animation; [open] is the logical state, [progress] the visual one, which
 * may lag behind or run ahead of it while a finger drags the drawer.
 */
@Composable
fun AppDrawer(
    apps: List<AppEntry>,
    columns: Int,
    open: Boolean,
    progress: () -> Float,
    focusSearch: Boolean,
    onAppClick: (AppEntry) -> Unit,
    menuFor: ((AppEntry) -> List<MenuItem>)?,
    onClose: () -> Unit,
    title: String? = null,
    onOpenSettings: (() -> Unit)? = null,
    /** Ember warmth per app; null turns embers off (and hides the ashes). */
    warmth: ((AppEntry) -> Float)? = null,
    coldWeeks: Int = 4,
    scrim: Color = drawerScrim(blurred = false),
    /** Usage score per app for "most used first"; null keeps the grid alphabetical. */
    usageRank: ((AppEntry) -> Float)? = null,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var ashesOnly by remember { mutableStateOf(false) }
    val ashes = remember(apps, warmth) {
        if (warmth == null) emptyList() else apps.filter { warmth(it) <= 0f }
    }
    val showingAshes = ashesOnly && ashes.isNotEmpty()
    val pool = if (showingAshes) ashes else apps

    val results = remember(pool, query, usageRank) {
        if (query.isBlank()) {
            // Stable sort: apps never launched keep their alphabetical order at the tail.
            if (usageRank == null) pool else pool.sortedByDescending(usageRank)
        }
        else pool.mapNotNull { app -> Fuzzy.score(query, app.label)?.let { app to it } }
            .sortedByDescending { it.second } // stable: ties stay alphabetical
            .map { it.first }
    }

    val close = rememberUpdatedState(onClose)
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val gridState = rememberLazyGridState()
    val pullThreshold = with(LocalDensity.current) { 96.dp.toPx() }
    val pullToClose = remember { PullToClose(pullThreshold, close) }

    val focusManager = LocalFocusManager.current
    val wantKeyboard by rememberUpdatedState(focusSearch)
    val visibleProgress by rememberUpdatedState(progress)

    BackHandler(enabled = open) { close.value() }

    LaunchedEffect(open) {
        if (open) {
            if (wantKeyboard) {
                withFrameNanos { }
                runCatching { focusRequester.requestFocus() }
                keyboard?.show()
            }
        } else {
            keyboard?.hide()
            focusManager.clearFocus()
            // Reset only once fully hidden, so the grid doesn't reshuffle mid-fade.
            snapshotFlow { visibleProgress() }.first { it <= 0f }
            query = ""
            ashesOnly = false
            gridState.scrollToItem(0)
        }
    }
    LaunchedEffect(query) { if (query.isNotEmpty()) gridState.scrollToItem(0) }
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }

    Box(
        modifier
            .fillMaxSize()
            .background(scrim)
            // Swallow stray taps so nothing reaches the home screen underneath.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                Modifier.pointerInput(Unit) {
                    var dy = 0f
                    detectVerticalDragGestures(
                        onDragStart = { dy = 0f },
                        onDragEnd = { if (dy > pullThreshold) close.value() },
                        onVerticalDrag = { _, amount -> dy += amount },
                    )
                },
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 20.sp,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                ) {
                    SearchField(
                        value = query,
                        onValueChange = { query = it },
                        onGo = { results.firstOrNull()?.let(onAppClick) },
                        focusRequester = focusRequester,
                        modifier = Modifier.weight(1f),
                    )
                    if (onOpenSettings != null) {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                        }
                    } else {
                        Spacer(Modifier.width(8.dp))
                    }
                }
                if (ashes.isNotEmpty()) {
                    AshesBar(
                        count = ashes.size,
                        coldWeeks = coldWeeks,
                        active = showingAshes,
                        onToggle = { ashesOnly = !showingAshes },
                    )
                }
            }

            if (results.isEmpty()) {
                Text(
                    text = "Nothing matches “$query”",
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(24.dp),
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                pullToClose.fingerDown = event.changes.any { it.pressed }
                            }
                        }
                    }
                    .nestedScroll(pullToClose),
            ) {
                items(results, key = { it.key }, contentType = { "app" }) { app ->
                    AppIcon(
                        app = app,
                        onClick = { onAppClick(app) },
                        menu = menuFor?.let { f -> { f(app) } },
                        warmth = warmth?.invoke(app) ?: 1f,
                    )
                }
            }
        }
    }
}

@Composable
private fun AshesBar(count: Int, coldWeeks: Int, active: Boolean, onToggle: () -> Unit) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)) {
        Text(
            text = if (active) "← All apps" else "Ashes · $count",
            color = if (active) Color.White else MaterialTheme.colorScheme.primary,
            fontSize = 14.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = if (active) 0.12f else 0.06f))
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
        if (active) {
            Text(
                text = "Untouched for $coldWeeks weeks or more. Long-press to let one go.",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onGo: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        modifier = modifier.focusRequester(focusRequester),
        decorationBox = { inner ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .padding(horizontal = 16.dp, vertical = 13.dp),
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text("Search apps", color = Color.White.copy(alpha = 0.45f), fontSize = 17.sp)
                    }
                    inner()
                }
            }
        },
    )
}

/**
 * Closes the drawer when the user keeps dragging down after the list has reached
 * its top. Only finger-driven overscroll counts, so a fling that coasts into the
 * top never closes it by accident.
 */
private class PullToClose(
    private val threshold: Float,
    private val onClose: State<() -> Unit>,
) : NestedScrollConnection {

    var fingerDown = false
        set(value) {
            field = value
            if (!value) pulled = 0f
        }

    private var pulled = 0f

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (fingerDown && available.y > 0f) {
            pulled += available.y
            if (pulled > threshold) {
                pulled = 0f
                onClose.value()
            }
        } else if (consumed.y != 0f) {
            pulled = 0f
        }
        return Offset.Zero
    }
}
