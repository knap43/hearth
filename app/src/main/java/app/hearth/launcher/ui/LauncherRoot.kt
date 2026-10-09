package app.hearth.launcher.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hearth.launcher.LauncherActions
import app.hearth.launcher.LauncherViewModel
import app.hearth.launcher.LockService
import app.hearth.launcher.Slot
import app.hearth.launcher.data.AppEntry
import app.hearth.launcher.data.EmberStore
import app.hearth.launcher.data.Gesture
import app.hearth.launcher.data.GestureAction
import app.hearth.launcher.data.Prefs
import app.hearth.launcher.data.WeatherStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val MAX_BLUR_PX = 60
private val DrawerSpring = spring<Float>(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)

@Composable
fun LauncherRoot(vm: LauncherViewModel, actions: LauncherActions) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val homeKeys by vm.prefs.home.collectAsStateWithLifecycle()
    val dockKeys by vm.prefs.dock.collectAsStateWithLifecycle()
    val gestures by vm.prefs.gestures.collectAsStateWithLifecycle()
    val columns by vm.prefs.columns.collectAsStateWithLifecycle()
    val lockReady by LockService.running.collectAsStateWithLifecycle()
    val accentSpec by vm.prefs.accent.collectAsStateWithLifecycle()
    val tintIcons by vm.prefs.tintIcons.collectAsStateWithLifecycle()
    val blur by vm.prefs.blur.collectAsStateWithLifecycle()
    val embersOn by vm.prefs.embers.collectAsStateWithLifecycle()
    val coldWeeks by vm.prefs.coldWeeks.collectAsStateWithLifecycle()
    val blurSupported by vm.blurSupported.collectAsStateWithLifecycle()
    val usageAccess by vm.embers.usageAccess.collectAsStateWithLifecycle()
    val lastUsed by vm.embers.lastUsed.collectAsStateWithLifecycle()
    val frequency by vm.embers.frequency.collectAsStateWithLifecycle()
    val drawerOrder by vm.prefs.drawerOrder.collectAsStateWithLifecycle()
    val weatherOn by vm.prefs.weather.collectAsStateWithLifecycle()
    val weather by vm.weather.current.collectAsStateWithLifecycle()

    // Derived values are remembered so that unrelated state changes don't hand
    // fresh lists and lambdas to every child, forcing the whole tree to recompose.
    val byKey = remember(apps) { apps.associateBy { it.key } }
    val home = remember(homeKeys, byKey) { homeKeys.mapNotNull(byKey::get) }
    val dock = remember(dockKeys, byKey) { dockKeys.mapNotNull(byKey::get) }

    val appearance = Appearance(accentSpec, tintIcons, blur, embersOn, coldWeeks, drawerOrder, weatherOn)
    fun setAppearance(a: Appearance) {
        val prefs = vm.prefs
        if (a.accent != accentSpec) prefs.setAccent(a.accent)
        if (a.tintIcons != tintIcons) prefs.setTintIcons(a.tintIcons)
        if (a.blur != blur) prefs.setBlur(a.blur)
        if (a.embers != embersOn) prefs.setEmbers(a.embers)
        if (a.coldWeeks != coldWeeks) prefs.setColdWeeks(a.coldWeeks)
        if (a.drawerOrder != drawerOrder) prefs.setDrawerOrder(a.drawerOrder)
        if (a.weather != weatherOn) {
            prefs.setWeather(a.weather)
            vm.refreshWeather(force = true)
        }
    }

    // Every launch through Hearth rekindles the app's ember.
    val launch: (AppEntry) -> Unit = remember(vm, actions) {
        { app ->
            vm.recordLaunch(app.key)
            actions.launch(app)
        }
    }

    val warmth: ((AppEntry) -> Float)? = remember(embersOn, lastUsed, coldWeeks) {
        if (!embersOn) {
            null
        } else {
            val now = System.currentTimeMillis()
            val f: (AppEntry) -> Float = { app -> EmberStore.warmth(lastUsed[app.key], now, coldWeeks * 7) }
            f
        }
    }

    // Decay every score to the same instant so they compare fairly.
    val usageRank: ((AppEntry) -> Float)? = remember(frequency, drawerOrder) {
        if (drawerOrder != Prefs.ORDER_USAGE) {
            null
        } else {
            val at = System.currentTimeMillis()
            val f: (AppEntry) -> Float = { app -> frequency[app.key]?.valueAt(at) ?: 0f }
            f
        }
    }

    // Weather: ask for approximate location on the first tap; if refused for good,
    // the next tap leads to Hearth's app settings instead.
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    var locationGranted by remember { mutableStateOf(vm.weather.hasPermission()) }
    var locationRefused by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        locationGranted = vm.weather.hasPermission()
        onPauseOrDispose { }
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        locationGranted = granted
        locationRefused = !granted
        if (granted) vm.refreshWeather(force = true)
    }
    val weatherLine: String? = when {
        !weatherOn -> null
        !locationGranted -> if (locationRefused) "Allow location for weather" else "Tap to show the weather"
        else -> weather?.takeIf { System.currentTimeMillis() - it.fetchedAt < WeatherStore.SHOW_FOR_MS }?.line(locale)
    }
    val onWeatherClick = {
        when {
            locationGranted -> vm.refreshWeather(force = true)
            locationRefused -> actions.openAppSettings()
            else -> askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    // ── Motion ──────────────────────────────────────────────────────────────
    // Visual progress lives in Animatables and is read only inside graphicsLayer
    // blocks and snapshotFlows, so animating never recomposes anything.
    val scope = rememberCoroutineScope()
    val drawer = remember { Animatable(0f) }
    val overlay = remember { Animatable(0f) }
    var screenHeight by remember { mutableFloatStateOf(1f) }
    val flingPx = with(LocalDensity.current) { 600.dp.toPx() }

    LaunchedEffect(vm.drawerOpen) { drawer.animateTo(if (vm.drawerOpen) 1f else 0f, DrawerSpring) }
    val overlayOpen = vm.settingsOpen || vm.pickingFor != null
    LaunchedEffect(overlayOpen) { overlay.animateTo(if (overlayOpen) 1f else 0f, tween(220)) }

    val swipeUp = gestures[Gesture.SwipeUp]
    val currentSwipeUp by rememberUpdatedState(swipeUp)
    val follow: DrawerFollow? = remember(swipeUp == GestureAction.Drawer || swipeUp == GestureAction.Search) {
        if (swipeUp != GestureAction.Drawer && swipeUp != GestureAction.Search) {
            null
        } else {
            object : DrawerFollow {
                override fun drag(px: Float) {
                    scope.launch {
                        // Half a screen of travel opens it fully.
                        drawer.snapTo((drawer.value + px / (screenHeight * 0.5f)).coerceIn(0f, 1f))
                    }
                }

                override fun release(upVelocity: Float) {
                    val open = if (abs(upVelocity) > flingPx) upVelocity > 0f else drawer.value > 0.4f
                    if (open) vm.openDrawer(withKeyboard = currentSwipeUp == GestureAction.Search) else vm.closeDrawer()
                    scope.launch { drawer.animateTo(if (open) 1f else 0f, DrawerSpring) }
                }
            }
        }
    }

    // Blur tracks the same progress, pushed straight to the window.
    val blurActive = blur && blurSupported
    LaunchedEffect(blurActive) {
        if (!blurActive) {
            actions.setWindowBlur(0)
            return@LaunchedEffect
        }
        snapshotFlow { (max(drawer.value, overlay.value) * MAX_BLUR_PX).roundToInt() }
            .distinctUntilChanged()
            .collect { actions.setWindowBlur(it) }
    }
    val scrim = drawerScrim(blurred = blurActive)

    HearthTheme(accent = rememberAccent(accentSpec), tintIcons = tintIcons) {

        fun perform(action: GestureAction) {
            when (action) {
                GestureAction.None -> Unit
                GestureAction.Drawer -> vm.openDrawer(withKeyboard = false)
                GestureAction.Search -> vm.openDrawer(withKeyboard = true)
                GestureAction.Notifications -> actions.expandNotifications()
                GestureAction.LockScreen -> actions.lockScreen()
                is GestureAction.Launch -> byKey[action.key]?.let(launch)
            }
        }

        fun drawerMenu(app: AppEntry): List<MenuItem> = buildList {
            add(MenuItem(if (app.key in homeKeys) "Remove from home" else "Add to home") { vm.togglePin(app.key) })
            if (app.key in dockKeys) {
                add(MenuItem("Remove from dock") { vm.toggleDock(app.key) })
            } else if (dockKeys.size < LauncherViewModel.DOCK_SIZE) {
                add(MenuItem("Add to dock") { vm.toggleDock(app.key) })
            }
            add(MenuItem("App info") { actions.appInfo(app) })
            if (!app.isSystem) add(MenuItem("Uninstall") { actions.uninstall(app) })
        }

        fun placedMenu(app: AppEntry, slot: Slot): List<MenuItem> = buildList {
            add(MenuItem("Move left") { vm.move(slot, app.key, -1) })
            add(MenuItem("Move right") { vm.move(slot, app.key, +1) })
            when (slot) {
                Slot.Home -> add(MenuItem("Remove from home") { vm.togglePin(app.key) })
                Slot.Dock -> add(MenuItem("Remove from dock") { vm.toggleDock(app.key) })
            }
            add(MenuItem("App info") { actions.appInfo(app) })
            if (!app.isSystem) add(MenuItem("Uninstall") { actions.uninstall(app) })
        }

        val homeMenu = remember(vm, actions) {
            listOf(
                MenuItem("Wallpaper", actions::openWallpaperPicker),
                MenuItem("Settings") { vm.settingsOpen = true },
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { screenHeight = it.height.toFloat().coerceAtLeast(1f) },
        ) {
            HomeScreen(
                home = home,
                dock = dock,
                columns = columns,
                onLaunch = launch,
                menuFor = ::placedMenu,
                onGesture = { perform(gestures[it] ?: GestureAction.None) },
                homeMenu = homeMenu,
                onClockClick = actions::openAlarms,
                weather = weatherLine,
                onWeatherClick = onWeatherClick,
                follow = follow,
                // Fade the home screen out under overlays, so with blur on only the
                // blurred wallpaper shows through, never crisp icons.
                modifier = Modifier.graphicsLayer {
                    alpha = 1f - max(drawer.value, overlay.value)
                },
            )

            AppDrawer(
                apps = apps,
                columns = columns,
                open = vm.drawerOpen,
                progress = { drawer.value },
                focusSearch = vm.focusSearch,
                onAppClick = {
                    launch(it)
                    vm.closeDrawer()
                },
                menuFor = ::drawerMenu,
                onClose = vm::closeDrawer,
                onOpenSettings = { vm.settingsOpen = true },
                warmth = warmth,
                coldWeeks = coldWeeks,
                scrim = scrim,
                usageRank = usageRank,
                modifier = Modifier.graphicsLayer {
                    val p = drawer.value
                    // Settings covers the drawer completely rather than layering over it.
                    alpha = p * (1f - overlay.value)
                    // Fully hidden: park it below the screen so it takes no touches.
                    translationY = if (p <= 0f) size.height else (1f - p) * size.height * 0.12f
                },
            )

            AnimatedVisibility(
                visible = vm.settingsOpen,
                enter = fadeIn() + slideInHorizontally { it / 4 },
                exit = fadeOut() + slideOutHorizontally { it / 4 },
            ) {
                SettingsScreen(
                    gestures = gestures,
                    labelFor = { byKey[it]?.label },
                    columns = columns,
                    lockReady = lockReady,
                    onGesture = vm::setGesture,
                    onPickApp = { vm.pickingFor = it },
                    onColumns = vm::setColumns,
                    onWallpaper = actions::openWallpaperPicker,
                    onDefaultHome = actions::openHomeSettings,
                    onAccessibility = actions::openAccessibilitySettings,
                    appearance = appearance,
                    onAppearance = ::setAppearance,
                    blurSupported = blurSupported,
                    usageAccess = usageAccess,
                    onUsageAccess = actions::openUsageAccess,
                    onClose = { vm.settingsOpen = false },
                )
            }

            AnimatedVisibility(
                visible = vm.pickingFor != null,
                enter = fadeIn() + slideInVertically { it / 5 },
                exit = fadeOut() + slideOutVertically { it / 5 },
            ) {
                AppDrawer(
                    apps = apps,
                    columns = columns,
                    open = true,
                    progress = { 1f },
                    focusSearch = false,
                    onAppClick = { app ->
                        vm.pickingFor?.let { vm.setGesture(it, GestureAction.Launch(app.key)) }
                        vm.pickingFor = null
                    },
                    menuFor = null,
                    onClose = { vm.pickingFor = null },
                    title = vm.pickingFor?.let { "${it.label} opens…" },
                    // Opaque: it sits on top of Settings, which must not show through.
                    scrim = drawerScrim(blurred = false),
                )
            }
        }
    }
}
