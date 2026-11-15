package minis.memory

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.ln
import kotlin.math.pow

data class Hit(val row: MemoryRow, val score: Double, val relevance: Double, val parts: Map<String, Double>)

class Engine(
    private val store: Store,
    private val memoryDir: String = "/var/minis/memory",
    private val namespace: String = "default"
) {
    private val weights = mapOf(
        "keyword" to 0.42, "semantic" to 0.20, "importance" to 0.14,
        "recency" to 0.10, "graph" to 0.09, "pinned" to 0.05
    )
    private val halfLife = mapOf(
        "rule" to 365.0, "preference" to 180.0, "fact" to 120.0,
        "skill" to 180.0, "task" to 45.0, "event" to 14.0, "reflection" to 90.0
    )
    private val prefix = Regex("^\\s*-\\s*(\\d{1,2}:\\d{2})?\\s*\\[([A-Za-z]+)·([0-9.]+)\\]\\s*")
    private val mm = Regex("<!--\\s*mm:[0-9a-f]+\\s*-->")
    private val heading = Regex("^#{1,6}\\s*(.+)$")

    private fun now() = System.currentTimeMillis() / 1000.0

    fun remember(
        text: String, kind: String = "fact", importance: Double = 0.6,
        pinned: Boolean = false, source: String = "app"
    ): Map<String, Any?> {
        val norm = Util.normalize(text)
        require(norm.length >= 2) { "text too short" }
        val hash = Util.sha256(norm)
        val existing = store.findByHash(namespace, hash)
        if (existing != null) {
            store.touch(existing.id)
            return mapOf("id" to existing.id, "deduplicated" to true, "mirrored" to null)
        }
        val id = store.insert(namespace, kind, norm, Util.summary(norm), hash, importance, pinned, source, now())
        for (e in Util.entities(norm)) store.linkEntity(id, store.upsertEntity(namespace, e))
        val mirrored = if (importance >= 0.55) mirror(norm, kind, importance, hash) else null
        return mapOf("id" to id, "deduplicated" to false, "mirrored" to mirrored)
    }

    fun recall(query: String, k: Int = 8): List<Hit> {
        val rows = store.active(namespace)
        if (rows.isEmpty()) return emptyList()
        val qTokens = Util.tokenize(query)
        val docs = rows.map { Util.tokenize("${it.text} ${it.summary}") }
        val df = HashMap<String, Int>()
        for (ts in docs) for (t in ts) df[t] = (df[t] ?: 0) + 1
        val n = rows.size.toDouble()
        val idf = df.mapValues { ln((n + 1) / (it.value + 1.0)) + 1.0 }

        val out = ArrayList<Hit>()
        val qNgrams = Util.charNgrams(query)
        val ents = store.entitiesAll()
        val intent = intentKinds(query)
        rows.forEachIndexed { i, row ->
            val m = docs[i]
            var keyword = Util.coverage(qTokens, m, idf)
            val body = "${row.text} ${row.summary}"
            val semantic = maxOf(
                Util.cosineBinary(qTokens, m),
                Util.dice(qNgrams, Util.charNgrams(body))
            )
            if (query.isNotEmpty() && body.lowercase().contains(query.lowercase())) {
                keyword = (keyword + 0.20).coerceAtMost(1.0)
            }
            val importance = (row.importance + 0.02 * ln(1.0 + row.accessCount)).coerceAtMost(1.0)
            val hl = halfLife[row.kind] ?: 90.0
            val ageDays = ((now() - row.updatedAt) / 86400.0).coerceAtLeast(0.0)
            val recency = 0.5.pow(ageDays / maxOf(1.0, hl))
            var graph = 0.0
            if (query.isNotEmpty()) {
                for (e in ents[row.id] ?: emptyList()) {
                    if (query.lowercase().contains(e.lowercase()) || Util.tokenize(e).any { it in qTokens }) {
                        graph = 1.0; break
                    }
                }
            }
            val pinned = if (row.pinned) 1.0 else 0.0
            val boost = if (row.kind in intent) 0.08 else 0.0
            val relevance = maxOf(keyword, semantic)
            val score =
                if (relevance <= 0.0) 0.0
                else weights["keyword"]!! * keyword + weights["semantic"]!! * semantic +
                    weights["importance"]!! * importance + weights["recency"]!! * recency +
                    weights["graph"]!! * graph + weights["pinned"]!! * pinned + boost
            if (relevance > 0.05 || row.pinned) {
                out.add(Hit(row, score, relevance, mapOf("keyword" to keyword, "semantic" to semantic, "graph" to graph)))
            }
        }
        store.logRecall(namespace, query, out.size)
        return out.sortedByDescending { it.score }.take(k)
    }

    fun forget(id: Long) = store.softDelete(id)

    fun profile(limit: Int = 8): List<MemoryRow> {
        val rules = store.topByKind(namespace, listOf("rule", "preference"), limit)
        val facts = store.topByKind(namespace, listOf("fact", "task", "skill"), limit / 2)
        return (rules + facts).distinctBy { it.id }.take(limit)
    }

    /** Markdown -> SQLite：按标题/前缀猜 kind，按正文哈希去重，堵住镜像回声 */
    fun syncNative(): Map<String, Int> {
        val dir = File(memoryDir)
        var files = 0
        var imported = 0
        var skipped = 0
        val list = dir.listFiles { f -> f.isFile && f.name.endsWith(".md") } ?: return mapOf()
        for (f in list) {
            files++
            val isGlobal = f.name.equals("GLOBAL.md", ignoreCase = true)
            var section = ""
            f.readLines().forEachIndexed { _, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("```") || line == "---" || line == "***") return@forEachIndexed
                if (line.startsWith("<!--")) return@forEachIndexed
                val h = heading.find(line)
                if (h != null) { section = h.groupValues[1].trim(); return@forEachIndexed }
                val p = prefix.find(line)
                val prefKind = p?.groupValues?.get(2)?.lowercase()
                val prefImp = p?.groupValues?.get(3)?.toDoubleOrNull()
                val body = line.replace(prefix, "").replace(mm, "").trim()
                if (body.length < 4) return@forEachIndexed
                val hash = Util.sha256(Util.normalize(body))
                if (store.findByHash(namespace, hash) != null) { skipped++; return@forEachIndexed }
                val (kind, imp) = when {
                    isGlobal -> "rule" to 0.82
                    prefKind != null -> prefKind to (prefImp ?: 0.6)
                    else -> inferKind(section)
                }
                val id = store.insert(namespace, kind, body, Util.summary(body), hash, imp, isGlobal, "native", now())
                for (e in Util.entities(body)) store.linkEntity(id, store.upsertEntity(namespace, e))
                imported++
            }
        }
        return mapOf("files" to files, "imported" to imported, "skipped" to skipped)
    }

    private fun inferKind(section: String): Pair<String, Double> = when {
        section.contains("规则") || section.contains("约定") || section.contains("原则") -> "rule" to 0.80
        section.contains("偏好") || section.contains("喜好") || section.contains("习惯") -> "preference" to 0.75
        section.contains("任务") || section.contains("待办") || section.contains("计划") -> "task" to 0.65
        section.contains("技能") || section.contains("流程") || section.contains("步骤") -> "skill" to 0.70
        section.contains("事实") || section.contains("信息") || section.contains("资料") -> "fact" to 0.65
        else -> "fact" to 0.60
    }

    /** 衰减归档：按 kind 半衰期折算，落到 dormant / archived */
    fun maintain(): Map<String, Int> {
        var dormant = 0
        var archived = 0
        val rows = store.active(namespace, 5000)
        for (r in rows) {
            if (r.status != "active") continue
            val hl = halfLife[r.kind] ?: 90.0
            val ageDays = ((now() - r.updatedAt) / 86400.0).coerceAtLeast(0.0)
            val decay = r.importance * 0.5.pow(ageDays / maxOf(1.0, hl))
            when {
                decay < 0.10 -> { store.setStatus(r.id, "archived"); archived++ }
                decay < 0.22 -> { store.setStatus(r.id, "dormant"); dormant++ }
            }
        }
        return mapOf("scanned" to rows.size, "dormant" to dormant, "archived" to archived)
    }

    fun skillPut(name: String, body: String, score: Double = 1.0) = store.skillUpsert(namespace, name, body, score)
    fun skillList() = store.skillList(namespace)
    fun skillGet(name: String) = store.skillGet(namespace, name)

    private fun intentKinds(q: String): Set<String> {
        val s = q.lowercase()
        val out = HashSet<String>()
        if (listOf("偏好", "喜欢", "习惯", "讨厌").any { s.contains(it) }) out.add("preference")
        if (listOf("规则", "以后都", "必须", "永远").any { s.contains(it) }) out.add("rule")
        if (listOf("任务", "待办", "计划", "进度").any { s.contains(it) }) out.add("task")
        if (listOf("事实", "信息", "资料").any { s.contains(it) }) out.add("fact")
        return out
    }

    private fun mirror(text: String, kind: String, importance: Double, hash: String): String {
        val dir = File(memoryDir)
        dir.mkdirs()
        val now = LocalDateTime.now()
        val file = File(dir, now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".md")
        val existing = if (file.exists()) file.readText() else ""
        if (existing.contains(hash.take(10))) return file.path
        val head = if (existing.isBlank()) "# ${now.toLocalDate()}\n\n" else ""
        val line = "- ${now.format(DateTimeFormatter.ofPattern("HH:mm"))} " +
            "[$kind·${"%.1f".format(importance)}] $text <!-- mm:${hash.take(10)} -->\n"
        file.appendText(head + line)
        return file.path
    }
}
