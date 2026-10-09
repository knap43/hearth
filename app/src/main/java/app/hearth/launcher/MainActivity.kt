package app.hearth.launcher

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import java.util.function.Consumer
import app.hearth.launcher.data.AppEntry
import app.hearth.launcher.ui.LauncherRoot

class MainActivity : ComponentActivity(), LauncherActions {

    private val vm: LauncherViewModel by viewModels()
    private val launcherApps by lazy { getSystemService(LauncherApps::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // A home screen has nowhere to go back to: swallow Back unless an overlay claims it.
        onBackPressedDispatcher.addCallback(this) {}
        setContent { LauncherRoot(vm, this@MainActivity) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vm.blurSupported.value = windowManager.isCrossWindowBlurEnabled
            windowManager.addCrossWindowBlurEnabledListener(blurListener)
        }
    }

    private val blurListener = Consumer<Boolean> { vm.blurSupported.value = it }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            windowManager.removeCrossWindowBlurEnabledListener(blurListener)
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Usage access may have been granted, and apps may have been used elsewhere.
        vm.refreshEmbers()
        vm.refreshWeather()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home: fold every overlay away.
        if (intent.action == Intent.ACTION_MAIN) vm.closeAll()
    }

    override fun launch(app: AppEntry) {
        runCatching { launcherApps.startMainActivity(app.component, app.user, null, null) }
            .onFailure { toast("Couldn’t open ${app.label}") }
    }

    override fun appInfo(app: AppEntry) {
        runCatching { launcherApps.startAppDetailsActivity(app.component, app.user, null, null) }
            .onFailure { toast("App info unavailable") }
    }

    override fun uninstall(app: AppEntry) = start(
        Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.component.packageName, null))
            .putExtra(Intent.EXTRA_USER, app.user)
    )

    @SuppressLint("WrongConstant")
    override fun expandNotifications() {
        // Hidden API, but on the permitted list and used by most third-party launchers.
        runCatching {
            Class.forName("android.app.StatusBarManager")
                .getMethod("expandNotificationsPanel")
                .invoke(getSystemService("statusbar"))
        }
    }

    override fun lockScreen() {
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> toast("Locking by gesture needs Android 9 or newer")
            !LockService.lock() -> {
                toast("Enable “Hearth screen lock” to lock with a gesture")
                openAccessibilitySettings()
            }
        }
    }

    override fun openWallpaperPicker() =
        start(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))

    override fun openHomeSettings() = start(Intent(Settings.ACTION_HOME_SETTINGS))

    override fun openAccessibilitySettings() = start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    override fun openAlarms() = start(Intent(AlarmClock.ACTION_SHOW_ALARMS))

    override fun openUsageAccess() = start(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))

    override fun openAppSettings() =
        start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    override fun setWindowBlur(radius: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setBackgroundBlurRadius(radius)
    }

    private fun start(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { toast("Nothing here can handle that") }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
