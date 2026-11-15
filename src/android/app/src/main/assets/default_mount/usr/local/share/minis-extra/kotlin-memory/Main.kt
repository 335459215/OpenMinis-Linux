package minis.memory

fun main(args: Array<String>) {
    val db = System.getenv("MINIS_MEMORY_DB") ?: "/var/minis/memory/minis-memory.db"
    val dir = System.getenv("MINIS_MEMORY_DIR") ?: "/var/minis/memory"
    val store = Store(db)
    val engine = Engine(store, dir)
    try {
        when (args.getOrNull(0)) {
            "remember" -> {
                val r = engine.remember(
                    args.getOrElse(1) { "" },
                    args.getOrElse(2) { "fact" },
                    args.getOrNull(3)?.toDoubleOrNull() ?: 0.6
                )
                println(r)
            }
            "recall" -> {
                val hits = engine.recall(args.getOrElse(1) { "" }, args.getOrNull(2)?.toIntOrNull() ?: 8)
                if (hits.isEmpty()) println("(no hit)") else {
                    println("【记忆便签】")
                    for (h in hits) println(
                        "- [${h.row.kind}·${"%.2f".format(h.score)}] ${h.row.text}  (rel=${"%.2f".format(h.relevance)})"
                    )
                }
            }
            "sync" -> println(engine.syncNative())
            "stats" -> println(store.counts("default"))
            "forget" -> {
                val id = args.getOrNull(1)?.toLongOrNull() ?: 0L
                engine.forget(id); println("deleted $id")
            }
            "maintain" -> println(engine.maintain())
            "skill" -> {
                when (args.getOrNull(1)) {
                    "put" -> println(engine.skillPut(args.getOrElse(2) { "" }, args.getOrElse(3) { "" }))
                    else -> engine.skillList().forEach { println("- ${it.first} (score=${it.second}, uses=${it.third})") }
                }
            }
            "bench" -> {
                val n = args.getOrNull(1)?.toIntOrNull() ?: 1000
                val t0 = System.nanoTime()
                for (i in 1..n) {
                    engine.remember("压测记忆 $i：内容寻址分块与沙箱策略第 $i 条样本", "fact", 0.3 + (i % 20) / 100.0)
                }
                val insertMs = (System.nanoTime() - t0) / 1_000_000.0
                val queries = listOf("内容寻址", "沙箱 seccomp", "分块去重", "PRoot 分片", "记忆召回")
                val t1 = System.nanoTime()
                var hits = 0
                repeat(20) { queries.forEach { q -> hits += engine.recall(q, 8).size } }
                val recallMs = (System.nanoTime() - t1) / 1_000_000.0
                println("rows=$n insert=${"%.0f".format(insertMs)}ms (${"%.2f".format(insertMs / n)}ms/row) " +
                    "recall=${"%.2f".format(recallMs / 100)}ms/query hits=$hits")
            }
            else -> println("usage: remember <text> [kind] [imp] | recall <q> [k] | sync | stats | forget <id> | maintain | skill put <name> <body> | skill | bench [n]")
        }
    } finally {
        store.close()
    }
}
