package minis.memory

import java.security.MessageDigest

object Util {
    private val WS = Regex("[ \t]+")
    private val NL3 = Regex("\n{3,}")
    private val LATIN = Regex("[a-z0-9_]{2,}")
    private val CJK = Regex("[\u4e00-\u9fff]+")
    private val QUOTED = Regex("[\"“”'‘’「」]([^\"“”'‘’「」]{2,24})[\"“”'‘’「」]")
    private val HASHTAG = Regex("#([\\p{L}\\p{N}_]{1,24})")
    private val CAP = Regex("\\b([A-Z][A-Za-z0-9_\\-]{2,24})\\b")

    fun normalize(s: String): String =
        s.replace("\r\n", "\n").replace("\r", "\n")
            .replace(WS, " ").replace(NL3, "\n\n").trim()

    fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun shortHash(s: String, n: Int = 10): String = sha256(s).take(n)

    fun summary(s: String, max: Int = 120): String =
        if (s.length <= max) s else s.take(max - 1) + "…"

    /** 拉丁词 + 中文 bigram，避免中文整句被当成一个 token */
    fun tokenize(text: String): Set<String> {
        val low = text.lowercase()
        val out = HashSet<String>()
        LATIN.findAll(low).forEach { out.add(it.value) }
        CJK.findAll(low).forEach { m ->
            val run = m.value
            if (run.length == 1) out.add(run)
            else for (i in 0 until run.length - 1) out.add(run.substring(i, i + 2))
        }
        return out
    }

    fun charNgrams(text: String, n: Int = 2): Set<String> {
        val s = text.lowercase().replace(Regex("\\s+"), "")
        if (s.length < n) return setOf(s)
        val out = HashSet<String>()
        for (i in 0..s.length - n) out.add(s.substring(i, i + n))
        return out
    }

    fun cosineBinary(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return inter / Math.sqrt(a.size.toDouble() * b.size)
    }

    fun dice(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return 2.0 * inter / (a.size + b.size)
    }

    fun coverage(q: Set<String>, m: Set<String>, idf: Map<String, Double> = emptyMap()): Double {
        if (q.isEmpty()) return 0.0
        var num = 0.0
        var den = 0.0
        for (t in q) {
            val w = idf[t] ?: 1.0
            den += w
            if (t in m) num += w
        }
        return if (den == 0.0) 0.0 else (num / den).coerceIn(0.0, 1.0)
    }

    fun entities(text: String): List<String> {
        val found = ArrayList<String>()
        QUOTED.findAll(text).forEach { if (it.groupValues[1].trim().length in 2..24) found.add(it.groupValues[1].trim()) }
        HASHTAG.findAll(text).forEach { found.add(it.groupValues[1].trim()) }
        CAP.findAll(text).forEach {
            val v = it.groupValues[1]
            if (v.lowercase() !in setOf("the", "this", "that", "and", "for")) found.add(v)
        }
        val seen = HashSet<String>()
        return found.filter { seen.add(it.lowercase()) }.take(12)
    }
}
