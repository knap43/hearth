package app.hearth.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

@Immutable
data class AppEntry(
    /** Stable identity across restarts: "package/class#userSerial". */
    val key: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val icon: ImageBitmap,
    /** The app's own single-colour glyph (Android 13+ themed icons), white on transparent. */
    val monochrome: ImageBitmap?,
    val isSystem: Boolean,
)

private class IconPair(val full: ImageBitmap, val monochrome: ImageBitmap?)

/**
 * Every launchable activity across the user's profiles (work profile included),
 * kept current through [LauncherApps.Callback].
 */
class AppRepository(private val context: Context, private val scope: CoroutineScope) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val iconSizePx = (56 * context.resources.displayMetrics.density).roundToInt()
    private val iconCache = ConcurrentHashMap<String, IconPair>()

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private var loadJob: Job? = null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = invalidate(packageName)
        override fun onPackageRemoved(packageName: String, user: UserHandle) = invalidate(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = invalidate(packageName)
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            invalidate(*packageNames)
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            invalidate(*packageNames)
    }

    fun start() {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        reload()
    }

    fun stop() = launcherApps.unregisterCallback(callback)

    private fun invalidate(vararg packages: String) {
        packages.forEach { pkg -> iconCache.keys.removeIf { it.startsWith("$pkg/") } }
        reload()
    }

    private fun reload() {
        loadJob?.cancel()
        loadJob = scope.launch(Dispatchers.Default) {
            val collator = Collator.getInstance()
            val entries = launcherApps.profiles.flatMap { user ->
                val serial = userManager.getSerialNumberForUser(user)
                val activities = runCatching { launcherApps.getActivityList(null, user) }.getOrDefault(emptyList())
                activities.mapNotNull { info ->
                    val component = info.componentName
                    if (component.packageName == context.packageName) return@mapNotNull null
                    val key = "${component.flattenToString()}#$serial"
                    val icons = iconCache.getOrPut(key) {
                        IconPair(
                            full = info.getBadgedIcon(0).toBitmap(iconSizePx, iconSizePx).asImageBitmap(),
                            monochrome = monochromeOf(info),
                        )
                    }
                    AppEntry(
                        key = key,
                        label = info.label.toString().trim(),
                        component = component,
                        user = user,
                        icon = icons.full,
                        monochrome = icons.monochrome,
                        isSystem = info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    )
                }
            }.sortedWith(compareBy(collator) { it.label })
            _apps.value = entries
        }
    }

    /**
     * Renders the adaptive icon's monochrome layer. Layers are 108dp with only the
     * central 72dp visible, so the layer is drawn at 1.5× and cropped to the icon.
     */
    private fun monochromeOf(info: LauncherActivityInfo): ImageBitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val adaptive = info.getIcon(0) as? AdaptiveIconDrawable ?: return null
        val layer = adaptive.monochrome ?: return null
        val size = iconSizePx
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val bleed = size / 4
        layer.setBounds(-bleed, -bleed, size + bleed, size + bleed)
        layer.draw(Canvas(bitmap))
        return bitmap.asImageBitmap()
    }
}
