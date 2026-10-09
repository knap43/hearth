package app.hearth.launcher.data

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.math.pow

/**
 * Remembers when each app was last kindled.
 *
 * Launches made through Hearth are always counted. If the user grants usage access,
 * the system's own history is folded in as well, so apps opened from notifications
 * or share sheets stay warm too, and long-installed apps can cool from day one.
 */
class EmberStore(private val context: Context) {

    private val sp = context.getSharedPreferences("embers", Context.MODE_PRIVATE)

    private val _lastUsed = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** App key → epoch millis it was last warm. */
    val lastUsed: StateFlow<Map<String, Long>> = _lastUsed.asStateFlow()

    private val _frequency = MutableStateFlow<Map<String, Frequency>>(emptyMap())
    /** App key → launch frequency through Hearth, decaying with a two-week half-life. */
    val frequency: StateFlow<Map<String, Frequency>> = _frequency.asStateFlow()

    private val _usageAccess = MutableStateFlow(false)
    val usageAccess: StateFlow<Boolean> = _usageAccess.asStateFlow()

    fun recordLaunch(key: String) {
        val now = System.currentTimeMillis()
        val next = (_frequency.value[key] ?: readFrequency(key) ?: Frequency(0f, now)).bump(now)
        sp.edit {
            putLong("used.$key", now)
            putFloat("freq.$key", next.score)
            putLong("freqAt.$key", next.at)
        }
        _lastUsed.update { it + (key to now) }
        _frequency.update { it + (key to next) }
    }

    @Volatile private var lastRefresh = 0L
    @Volatile private var lastAccess = false
    @Volatile private var lastApps: List<AppEntry> = emptyList()

    /**
     * Rebuilds warmth. Coming home calls this every time, but the system usage query
     * is costly, so it only really runs when the app list or usage access changed,
     * or a few minutes have passed.
     */
    suspend fun refresh(apps: List<AppEntry>): Unit = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val access = hasUsageAccess()
        _usageAccess.value = access
        if (apps === lastApps && access == lastAccess && now - lastRefresh < MIN_REFRESH_MS) return@withContext
        lastApps = apps
        lastAccess = access
        lastRefresh = now

        val system: Map<String, Long> = if (access) {
            runCatching {
                context.getSystemService(UsageStatsManager::class.java)
                    .queryAndAggregateUsageStats(now - HISTORY_MS, now)
                    .mapValues { it.value.lastTimeUsed }
            }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }

        val result = HashMap<String, Long>(apps.size)
        val frequencies = HashMap<String, Frequency>()
        val firstSeen = ArrayList<String>()
        for (app in apps) {
            val pkg = app.component.packageName
            val own = sp.getLong("used.${app.key}", 0L)
            val seen = sp.getLong("seen.${app.key}", 0L).takeIf { it > 0L }
                ?: now.also { firstSeen += app.key }
            // With real history we can trust the install date; without it, an app
            // only starts cooling from the moment Hearth first saw it.
            val baseline = if (access) installTime(pkg) ?: seen else seen
            result[app.key] = maxOf(own, system[pkg] ?: 0L, baseline)
            readFrequency(app.key)?.let { frequencies[app.key] = it }
        }
        // Write only when something is new, not on every return home.
        if (firstSeen.isNotEmpty()) sp.edit { firstSeen.forEach { putLong("seen.$it", now) } }
        _lastUsed.value = result
        _frequency.value = frequencies
    }

    private fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun readFrequency(key: String): Frequency? {
        val at = sp.getLong("freqAt.$key", 0L)
        return if (at == 0L) null else Frequency(sp.getFloat("freq.$key", 0f), at)
    }

    private fun installTime(pkg: String): Long? =
        runCatching { context.packageManager.getPackageInfo(pkg, 0).firstInstallTime }.getOrNull()

    /** A launch score as of [at]; decays continuously, each launch adds 1. */
    data class Frequency(val score: Float, val at: Long) {
        fun valueAt(now: Long): Float =
            score * 0.5.pow((now - at).toDouble() / HALF_LIFE_MS).toFloat()

        fun bump(now: Long) = Frequency(valueAt(now) + 1f, now)
    }

    companion object {
        private const val DAY_MS = 86_400_000L
        private const val HALF_LIFE_MS = 14 * DAY_MS
        private const val HISTORY_MS = 120 * DAY_MS
        private const val MIN_REFRESH_MS = 5 * 60_000L

        /**
         * 1 = warm, 0 = ash. Fully warm for the first quarter of [coldDays],
         * then cooling linearly until it turns to ash at [coldDays].
         */
        fun warmth(lastUsed: Long?, now: Long, coldDays: Int): Float {
            if (lastUsed == null) return 1f
            val days = (now - lastUsed) / DAY_MS.toFloat()
            val warmDays = coldDays / 4f
            if (days <= warmDays) return 1f
            return (1f - (days - warmDays) / (coldDays - warmDays)).coerceIn(0f, 1f)
        }
    }
}
