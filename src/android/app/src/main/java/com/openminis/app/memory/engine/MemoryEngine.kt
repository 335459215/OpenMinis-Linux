package com.openminis.app.memory.engine

import android.content.ContentValues
import android.content.Context
import java.security.MessageDigest
import kotlin.math.ln
import kotlin.math.pow

/** 一条结构化记忆的召回结果。 */
data class MemoryHit(val kind: String, val text: String, val score: Double)

/**
 * 轻量结构化记忆：写入时按 sha256 去重，召回时用关键词覆盖度 + 字符 ngram
 * 相似度 + 重要度/新鲜度打分。数据落在独立 SQLite 库里，和 Markdown 记忆互补。
 */
class MemoryEngine(context: Context) {

    private val helper = MemoryEngineDb(context)
    private val namespace = "default"

    private val latin = Regex("[a-z0-9_]{2,}")
    private val cjk = Regex("[\u4e00-\u9fff]+")
    private val halfLife = mapOf(
        "rule" to 365.0, "preference" to 180.0, "fact" to 120.0,
        "skill" to 180.0, "task" to 45.0, "event" to 14.0,
    )

    fun remember(text: String, kind: String = "fact", importance: Double = 0.6, source: String = "app"): Long {
        val norm = normalize(text)
        if (norm.length < 2) return -1L
        val hash = sha256(norm)
        val now = now()
        val db = helper.writableDatabase
        db.query(
            "memories", arrayOf("id"), "namespace=? AND hash=?",
            arrayOf(namespace, hash), null, null, null,
        ).use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                db.execSQL(
                    "UPDATE memories SET access_count=access_count+1, accessed_at=?, updated_at=? WHERE id=?",
                    arrayOf<Any>(now, now, id),
                )
                return id
            }
        }
        val cv = ContentValues().apply {
            put("namespace", namespace)
            put("kind", kind)
            put("text", norm)
            put("summary", if (norm.length <= 120) norm else norm.take(119) + "…")
            put("hash", hash)
            put("importance", importance.coerceIn(0.0, 1.0))
            put("pinned", 0)
            put("status", "active")
            put("source", source)
            put("created_at", now)
            put("updated_at", now)
            put("accessed_at", now)
            put("access_count", 0)
        }
        return db.insert("memories", null, cv)
    }

    fun recall(query: String, k: Int = 5): List<MemoryHit> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val qTokens = tokenize(q)
        if (qTokens.isEmpty()) return emptyList()
        val db = helper.readableDatabase
        val rows = ArrayList<Array<Any>>()
        db.query(
            "memories",
            arrayOf("kind", "text", "importance", "pinned", "updated_at", "access_count"),
            "namespace=? AND status='active'",
            arrayOf(namespace), null, null, "updated_at DESC", "2000",
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    arrayOf(
                        c.getString(0), c.getString(1), c.getDouble(2),
                        c.getInt(3) != 0, c.getDouble(4), c.getInt(5),
                    ),
                )
            }
        }
        if (rows.isEmpty()) return emptyList()

        val docs = rows.map { tokenize(it[1] as String) }
        val df = HashMap<String, Int>()
        for (doc in docs) for (t in doc) df[t] = (df[t] ?: 0) + 1
        val n = rows.size.toDouble()
        val idf = df.mapValues { ln((n + 1.0) / (it.value + 1.0)) + 1.0 }
        val qGrams = charNgrams(q)
        val now = now()

        val hits = ArrayList<Pair<Double, MemoryHit>>()
        rows.forEachIndexed { i, row ->
            val text = row[1] as String
            val tokens = docs[i]
            var keyword = 0.0
            var den = 0.0
            for (t in qTokens) {
                val w = idf[t] ?: 1.0
                den += w
                if (t in tokens) keyword += w
            }
            keyword = if (den == 0.0) 0.0 else (keyword / den).coerceIn(0.0, 1.0)
            if (text.lowercase().contains(q.lowercase())) keyword = (keyword + 0.2).coerceAtMost(1.0)

            val semantic = maxOf(cosine(qTokens, tokens), dice(qGrams, charNgrams(text)))
            val importance = ((row[2] as Double) + 0.02 * ln(1.0 + (row[5] as Int))).coerceAtMost(1.0)
            val kind = row[0] as String
            val ageDays = ((now - (row[4] as Double)) / 86400.0).coerceAtLeast(0.0)
            val recency = 0.5.pow(ageDays / maxOf(1.0, halfLife[kind] ?: 90.0))
            val pinned = if (row[3] as Boolean) 1.0 else 0.0
            val relevance = maxOf(keyword, semantic)
            if (relevance <= 0.05) return@forEachIndexed
            val score = 0.42 * keyword + 0.20 * semantic + 0.14 * importance +
                0.10 * recency + 0.09 * 0.0 + 0.05 * pinned
            hits.add(score to MemoryHit(kind, text, score))
        }

        db.execSQL(
            "INSERT INTO recall_log(namespace, query, hits, created_at) VALUES(?,?,?,?)",
            arrayOf<Any>(namespace, q, hits.size, now),
        )
        return hits.sortedByDescending { it.first }.take(k).map { it.second }
    }

    fun forget(id: Long) {
        helper.writableDatabase.execSQL(
            "UPDATE memories SET status='deleted', updated_at=? WHERE id=?",
            arrayOf<Any>(now(), id),
        )
    }

    fun stats(): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM memories WHERE namespace=? AND status='active'",
            arrayOf(namespace),
        ).use { c -> if (c.moveToFirst()) out["total"] = c.getInt(0) }
        return out
    }

    private fun now(): Double = System.currentTimeMillis() / 1000.0

    private fun normalize(s: String): String =
        s.replace("\r\n", "\n").replace("\r", "\n")
            .replace(Regex("[ \t]+"), " ").trim()

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun tokenize(text: String): Set<String> {
        val low = text.lowercase()
        val out = HashSet<String>()
        latin.findAll(low).forEach { out.add(it.value) }
        cjk.findAll(low).forEach { m ->
            val run = m.value
            if (run.length == 1) out.add(run)
            else for (i in 0 until run.length - 1) out.add(run.substring(i, i + 2))
        }
        return out
    }

    private fun charNgrams(text: String, n: Int = 2): Set<String> {
        val s = text.lowercase().replace(Regex("\\s+"), "")
        if (s.length < n) return setOf(s)
        val out = HashSet<String>()
        for (i in 0..s.length - n) out.add(s.substring(i, i + n))
        return out
    }

    private fun cosine(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.count { it in b } / Math.sqrt(a.size.toDouble() * b.size)
    }

    private fun dice(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 2.0 * a.count { it in b } / (a.size + b.size)
    }
}
