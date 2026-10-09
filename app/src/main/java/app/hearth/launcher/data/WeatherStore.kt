package app.hearth.launcher.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

data class Weather(val tempC: Double, val code: Int, val isDay: Boolean, val fetchedAt: Long) {

    /** "7° · light rain"; Fahrenheit where that's the local custom. */
    fun line(locale: Locale): String {
        val fahrenheit = locale.country in FAHRENHEIT_COUNTRIES
        val t = if (fahrenheit) tempC * 9 / 5 + 32 else tempC
        val sky = describe()
        return if (sky.isEmpty()) "${t.roundToInt()}°" else "${t.roundToInt()}° · $sky"
    }

    /** WMO weather interpretation codes, as used by Open-Meteo. */
    private fun describe(): String = when (code) {
        0 -> if (isDay) "clear" else "clear night"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
        51, 53, 55 -> "drizzle"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "rain"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71 -> "light snow"
        73 -> "snow"
        75 -> "heavy snow"
        77 -> "snow grains"
        80 -> "light showers"
        81 -> "showers"
        82 -> "heavy showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"
        else -> ""
    }

    private companion object {
        val FAHRENHEIT_COUNTRIES = setOf("US", "LR", "MM")
    }
}

/**
 * Current conditions from Open-Meteo (no account, no key). Only coordinates rounded
 * to two decimals (~1 km) ever leave the phone. Refreshes at most every 30 minutes.
 */
class WeatherStore(private val context: Context) {

    private val sp = context.getSharedPreferences("weather", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _current = MutableStateFlow(readCached())
    val current: StateFlow<Weather?> = _current.asStateFlow()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun refresh(force: Boolean = false): Unit = mutex.withLock {
        if (!hasPermission()) return@withLock
        val cached = _current.value
        if (!force && cached != null && System.currentTimeMillis() - cached.fetchedAt < STALE_MS) return@withLock

        val location = locate() ?: return@withLock
        val weather = withContext(Dispatchers.IO) {
            runCatching { fetch(location.latitude, location.longitude) }.getOrNull()
        } ?: return@withLock

        _current.value = weather
        sp.edit {
            putFloat("temp", weather.tempC.toFloat())
            putInt("code", weather.code)
            putBoolean("day", weather.isDay)
            putLong("at", weather.fetchedAt)
        }
    }

    /** A recent known fix if there is one; otherwise ask for a fresh coarse fix (Android 11+). */
    @SuppressLint("MissingPermission")
    private suspend fun locate(): Location? = runCatching {
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = lm.getProviders(true)
        val now = System.currentTimeMillis()
        val known = providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (known != null && now - known.time < FRESH_FIX_MS) return@runCatching known

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // GPS is usable with only coarse permission from Android 12 (results are blurred).
            val candidates = buildList {
                add(LocationManager.NETWORK_PROVIDER)
                add("fused")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.GPS_PROVIDER)
            }
            val provider = candidates.firstOrNull { it in providers }
            if (provider != null) {
                val fresh = withTimeoutOrNull(FIX_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        val signal = CancellationSignal()
                        cont.invokeOnCancellation { signal.cancel() }
                        lm.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) }
                    }
                }
                if (fresh != null) return@runCatching fresh
            }
        }
        known?.takeIf { now - it.time < USABLE_FIX_MS }
    }.getOrNull()

    private fun fetch(lat: Double, lon: Double): Weather {
        val url = URL(
            String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
                    "&current=temperature_2m,weather_code,is_day",
                lat, lon,
            ),
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val current = JSONObject(body).getJSONObject("current")
            return Weather(
                tempC = current.getDouble("temperature_2m"),
                code = current.getInt("weather_code"),
                isDay = current.optInt("is_day", 1) == 1,
                fetchedAt = System.currentTimeMillis(),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun readCached(): Weather? {
        val at = sp.getLong("at", 0L)
        if (at == 0L) return null
        return Weather(sp.getFloat("temp", 0f).toDouble(), sp.getInt("code", -1), sp.getBoolean("day", true), at)
    }

    companion object {
        private const val MINUTE_MS = 60_000L
        private const val STALE_MS = 30 * MINUTE_MS
        private const val FRESH_FIX_MS = 60 * MINUTE_MS
        private const val USABLE_FIX_MS = 12 * 60 * MINUTE_MS
        private const val FIX_TIMEOUT_MS = 15_000L

        /** Older readings are hidden rather than shown as if current. */
        const val SHOW_FOR_MS = 3 * 60 * MINUTE_MS
    }
}
