package com.openminis.app.memory.engine

import android.content.Context

/**
 * 全局入口：主进程启动时 init，之后任何地方都能直接调用 recall/remember。
 * 惰性初始化，避免在子进程或 ACRA reporter 进程里打开数据库。
 */
object MemoryEngineProvider {

    @Volatile
    private var engine: MemoryEngine? = null

    fun init(context: Context) {
        if (engine == null) {
            synchronized(this) {
                if (engine == null) {
                    engine = MemoryEngine(context.applicationContext)
                }
            }
        }
    }

    fun isReady(): Boolean = engine != null

    fun recall(query: String, k: Int = 5): List<MemoryHit> =
        engine?.recall(query, k) ?: emptyList()

    fun remember(text: String, kind: String = "fact", importance: Double = 0.6): Long =
        engine?.remember(text, kind, importance) ?: -1L

    fun forget(id: Long) {
        engine?.forget(id)
    }

    fun stats(): Map<String, Int> = engine?.stats() ?: emptyMap()
}
