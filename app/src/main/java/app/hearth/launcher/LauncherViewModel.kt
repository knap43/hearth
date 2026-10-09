package app.hearth.launcher

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hearth.launcher.data.AppEntry
import app.hearth.launcher.data.AppRepository
import app.hearth.launcher.data.EmberStore
import app.hearth.launcher.data.Gesture
import app.hearth.launcher.data.GestureAction
import app.hearth.launcher.data.Prefs
import app.hearth.launcher.data.WeatherStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class Slot { Home, Dock }

class LauncherViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = Prefs(app)
    private val repository = AppRepository(app, viewModelScope).also { it.start() }
    val apps: StateFlow<List<AppEntry>> = repository.apps
    val embers = EmberStore(app)
    val weather = WeatherStore(app)

    fun refreshWeather(force: Boolean = false) {
        if (prefs.weather.value) viewModelScope.launch { weather.refresh(force) }
    }

    /** Whether this device composes window blurs right now (Android 12+, may toggle with battery saver). */
    val blurSupported = MutableStateFlow(false)

    init {
        viewModelScope.launch { apps.collectLatest { embers.refresh(it) } }
    }

    fun refreshEmbers() {
        viewModelScope.launch { embers.refresh(apps.value) }
    }

    fun recordLaunch(key: String) = embers.recordLaunch(key)

    var drawerOpen by mutableStateOf(false)
        private set
    var focusSearch by mutableStateOf(false)
        private set
    var settingsOpen by mutableStateOf(false)
    var pickingFor by mutableStateOf<Gesture?>(null)

    fun openDrawer(withKeyboard: Boolean) {
        focusSearch = withKeyboard
        drawerOpen = true
    }

    fun closeDrawer() {
        drawerOpen = false
    }

    fun closeAll() {
        pickingFor = null
        settingsOpen = false
        drawerOpen = false
    }

    fun togglePin(key: String) = update(Slot.Home) { if (key in it) it - key else it + key }

    fun toggleDock(key: String) = update(Slot.Dock) {
        when {
            key in it -> it - key
            it.size < DOCK_SIZE -> it + key
            else -> it
        }
    }

    /** Reorders among the apps actually shown; keys of uninstalled apps keep their place at the end. */
    fun move(slot: Slot, key: String, delta: Int) = update(slot) { list ->
        val present = apps.value.mapTo(HashSet()) { app -> app.key }
        val visible = list.filter { it in present }.toMutableList()
        val from = visible.indexOf(key)
        val to = from + delta
        if (from < 0 || to !in visible.indices) {
            list
        } else {
            visible.add(to, visible.removeAt(from))
            visible + list.filterNot { it in present }
        }
    }

    fun setGesture(gesture: Gesture, action: GestureAction) = prefs.setGesture(gesture, action)
    fun setColumns(n: Int) = prefs.setColumns(n)

    private fun update(slot: Slot, transform: (List<String>) -> List<String>) = when (slot) {
        Slot.Home -> prefs.setHome(transform(prefs.home.value))
        Slot.Dock -> prefs.setDock(transform(prefs.dock.value))
    }

    override fun onCleared() = repository.stop()

    companion object {
        const val DOCK_SIZE = 5
    }
}
