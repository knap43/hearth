package app.hearth.launcher.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("hearth", Context.MODE_PRIVATE)

    private val _home = MutableStateFlow(readList(KEY_HOME))
    val home: StateFlow<List<String>> = _home.asStateFlow()

    private val _dock = MutableStateFlow(readList(KEY_DOCK))
    val dock: StateFlow<List<String>> = _dock.asStateFlow()

    private val _gestures = MutableStateFlow(Gesture.entries.associateWith(::readGesture))
    val gestures: StateFlow<Map<Gesture, GestureAction>> = _gestures.asStateFlow()

    private val _columns = MutableStateFlow(sp.getInt(KEY_COLUMNS, 4))
    val columns: StateFlow<Int> = _columns.asStateFlow()

    /** "wallpaper", "system", or a preset as "#RRGGBB". */
    private val _accent = MutableStateFlow(sp.getString(KEY_ACCENT, null) ?: DEFAULT_ACCENT)
    val accent: StateFlow<String> = _accent.asStateFlow()

    private val _tintIcons = MutableStateFlow(sp.getBoolean(KEY_TINT, false))
    val tintIcons: StateFlow<Boolean> = _tintIcons.asStateFlow()

    private val _blur = MutableStateFlow(sp.getBoolean(KEY_BLUR, false))
    val blur: StateFlow<Boolean> = _blur.asStateFlow()

    private val _embers = MutableStateFlow(sp.getBoolean(KEY_EMBERS, true))
    val embers: StateFlow<Boolean> = _embers.asStateFlow()

    private val _coldWeeks = MutableStateFlow(sp.getInt(KEY_COLD_WEEKS, 4))
    val coldWeeks: StateFlow<Int> = _coldWeeks.asStateFlow()

    /** "usage" (most used first) or "alpha". */
    private val _drawerOrder = MutableStateFlow(sp.getString(KEY_ORDER, null) ?: ORDER_USAGE)
    val drawerOrder: StateFlow<String> = _drawerOrder.asStateFlow()

    private val _weather = MutableStateFlow(sp.getBoolean(KEY_WEATHER, true))
    val weather: StateFlow<Boolean> = _weather.asStateFlow()

    fun setWeather(on: Boolean) {
        _weather.value = on
        sp.edit { putBoolean(KEY_WEATHER, on) }
    }

    fun setDrawerOrder(order: String) {
        _drawerOrder.value = order
        sp.edit { putString(KEY_ORDER, order) }
    }

    fun setAccent(spec: String) {
        _accent.value = spec
        sp.edit { putString(KEY_ACCENT, spec) }
    }

    fun setTintIcons(on: Boolean) {
        _tintIcons.value = on
        sp.edit { putBoolean(KEY_TINT, on) }
    }

    fun setBlur(on: Boolean) {
        _blur.value = on
        sp.edit { putBoolean(KEY_BLUR, on) }
    }

    fun setEmbers(on: Boolean) {
        _embers.value = on
        sp.edit { putBoolean(KEY_EMBERS, on) }
    }

    fun setColdWeeks(weeks: Int) {
        _coldWeeks.value = weeks
        sp.edit { putInt(KEY_COLD_WEEKS, weeks) }
    }

    fun setHome(keys: List<String>) {
        _home.value = keys
        sp.edit { putString(KEY_HOME, keys.joinToString(SEP)) }
    }

    fun setDock(keys: List<String>) {
        _dock.value = keys
        sp.edit { putString(KEY_DOCK, keys.joinToString(SEP)) }
    }

    fun setGesture(gesture: Gesture, action: GestureAction) {
        _gestures.value = _gestures.value + (gesture to action)
        sp.edit { putString(gestureKey(gesture), action.encode()) }
    }

    fun setColumns(n: Int) {
        _columns.value = n
        sp.edit { putInt(KEY_COLUMNS, n) }
    }

    private fun readList(key: String): List<String> =
        sp.getString(key, null)?.split(SEP)?.filter { it.isNotEmpty() }.orEmpty()

    private fun readGesture(gesture: Gesture): GestureAction =
        GestureAction.decode(sp.getString(gestureKey(gesture), null)) ?: DEFAULTS.getValue(gesture)

    private fun gestureKey(g: Gesture) = "gesture.${g.name}"

    companion object {
        const val ORDER_USAGE = "usage"
        const val ORDER_ALPHA = "alpha"
        private const val KEY_ORDER = "drawerOrder"
        private const val KEY_WEATHER = "weather"
        const val SEP = "\n"
        const val KEY_HOME = "home"
        const val KEY_DOCK = "dock"
        const val KEY_COLUMNS = "columns"
        const val KEY_ACCENT = "accent"
        const val KEY_TINT = "tintIcons"
        const val KEY_BLUR = "blur"
        const val KEY_EMBERS = "embers"
        const val KEY_COLD_WEEKS = "coldWeeks"
        const val DEFAULT_ACCENT = "#FFB067"

        val DEFAULTS: Map<Gesture, GestureAction> = mapOf(
            Gesture.SwipeUp to GestureAction.Drawer,
            Gesture.SwipeDown to GestureAction.Notifications,
            Gesture.SwipeLeft to GestureAction.None,
            Gesture.SwipeRight to GestureAction.Search,
            Gesture.DoubleTap to GestureAction.LockScreen,
        )
    }
}
