package app.hearth.launcher.data

enum class Gesture(val label: String) {
    SwipeUp("Swipe up"),
    SwipeDown("Swipe down"),
    SwipeLeft("Swipe left"),
    SwipeRight("Swipe right"),
    DoubleTap("Double tap"),
}

sealed interface GestureAction {
    data object None : GestureAction
    data object Drawer : GestureAction
    data object Search : GestureAction
    data object Notifications : GestureAction
    data object LockScreen : GestureAction
    data class Launch(val key: String) : GestureAction

    fun encode(): String = when (this) {
        None -> "none"
        Drawer -> "drawer"
        Search -> "search"
        Notifications -> "notifications"
        LockScreen -> "lock"
        is Launch -> "app:$key"
    }

    companion object {
        fun decode(raw: String?): GestureAction? = when {
            raw == null -> null
            raw.startsWith("app:") -> Launch(raw.removePrefix("app:"))
            raw == "none" -> None
            raw == "drawer" -> Drawer
            raw == "search" -> Search
            raw == "notifications" -> Notifications
            raw == "lock" -> LockScreen
            else -> null
        }
    }
}
