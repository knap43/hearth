package app.hearth.launcher.data

/**
 * Ranks how well [query] matches [target]; null means no match. Higher is better.
 *
 *   prefix            "tel"  → Telegram
 *   word substring    "maps" → Google Maps
 *   inner substring   "gram" → Telegram
 *   subsequence       "gm"   → Google Maps, "yt" → YouTube
 *
 * Case-insensitive; treats "ё" as "е".
 */
object Fuzzy {

    fun score(query: String, target: String): Int? {
        val q = normalize(query.trim())
        if (q.isEmpty()) return 0
        val t = normalize(target)
        // Lowercasing can change length for a few exotic characters; keep indices aligned.
        val shape = if (t.length == target.length) target else t

        if (t.startsWith(q)) return 3000 - t.length

        val at = t.indexOf(q)
        if (at >= 0) return (if (isBoundary(shape, at)) 2000 else 1000) - at - t.length

        var score = 0
        var ti = 0
        var prev = -1
        for (c in q) {
            if (c.isWhitespace()) continue
            while (ti < t.length && t[ti] != c) ti++
            if (ti == t.length) return null
            score += when {
                prev >= 0 && ti == prev + 1 -> 8
                isBoundary(shape, ti) -> 10
                else -> 1
            }
            prev = ti
            ti++
        }
        return score - t.length / 8
    }

    private fun normalize(s: String) = s.lowercase().replace('ё', 'е')

    private fun isBoundary(s: String, i: Int): Boolean =
        i == 0 || !s[i - 1].isLetterOrDigit() || (s[i].isUpperCase() && s[i - 1].isLowerCase())
}
