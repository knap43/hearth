package app.hearth.launcher

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The only sanctioned way for an unprivileged app to lock the screen (Android 9+)
 * without becoming a device admin. Observes nothing; it merely exists to call
 * [GLOBAL_ACTION_LOCK_SCREEN].
 */
class LockService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        _running.value = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private fun detach() {
        instance = null
        _running.value = false
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: LockService? = null

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** @return false when the service isn't enabled or the OS is too old. */
        fun lock(): Boolean {
            val service = instance ?: return false
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        }
    }
}
