package app.hearth.launcher

import app.hearth.launcher.data.AppEntry

/** Side effects that need an Activity; implemented by [MainActivity]. */
interface LauncherActions {
    fun launch(app: AppEntry)
    fun appInfo(app: AppEntry)
    fun uninstall(app: AppEntry)
    fun expandNotifications()
    fun lockScreen()
    fun openWallpaperPicker()
    fun openHomeSettings()
    fun openAccessibilitySettings()
    fun openAlarms()
    fun openUsageAccess()
    fun openAppSettings()
    /** Blurs the wallpaper behind the window; 0 clears it. No-op before Android 12. */
    fun setWindowBlur(radius: Int)
}
