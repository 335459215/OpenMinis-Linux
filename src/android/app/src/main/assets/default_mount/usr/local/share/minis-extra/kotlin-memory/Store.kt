package minis.memory

import java.sql.Connection
import java.sql.DriverManager

data class MemoryRow(
    val id: Long,
    val kind: String,
    val text: String,
    val summary: String,
    val importance: Double,
    val pinned: Boolean,
    val status: String,
    val updatedAt: Double,
    val accessCount: Int,
    val source: String
)

class Store(dbPath: String) : AutoCloseable {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    init {
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute(
                """CREATE TABLE IF NOT EXISTS memories(
                   id INTEGER PRIMARY KEY AUTOINCREMENT,
                   namespace TEXT NOT NULL DEFAULT 'default',
                   kind TEXT NOT NULL,
                   text TEXT NOT NULL,
                   summary TEXT NOT NULL,
                   confidence REAL NOT NULL DEFAULT 0.8,
                   valence REAL NOT NULL DEFAULT 0.0,
                   arousal REAL NOT NULL DEFAULT 0.3,
                   source TEXT NOT NULL DEFAULT 'app',
                   source_ref TEXT NOT NULL DEFAULT '',
                   visibility TEXT NOT NULL DEFAULT 'private',
                   status TEXT NOT NULL DEFAULT 'active',
                   pinned INTEGER NOT NULL DEFAULT 0,
                   created_at REAL NOT NULL,
                   updated_at REAL NOT NULL,
                   accessed_at REAL NOT NULL,
                   access_count INTEGER NOT NULL DEFAULT 0,
                   valid_from REAL,
                   valid_to REAL,
                   hash TEXT NOT NULL DEFAULT '',
                   meta TEXT NOT NULL DEFAULT '{}')"""
            )
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_mem_hash ON memories(namespace, hash)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_mem_status ON memories(namespace, status)")
            st.execute(
                """CREATE TABLE IF NOT EXISTS entities(
                   id INTEGER PRIMARY KEY AUTOINCREMENT,
                   namespace TEXT NOT NULL DEFAULT 'default',
                   name TEXT NOT NULL,
                   created_at REAL NOT NULL,
                   UNIQUE(namespace, name))"""
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS memory_entities(
                   memory_id INTEGER NOT NULL,
                   entity_id INTEGER NOT NULL,
                   PRIMARY KEY(memory_id, entity_id))"""
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS relations(
                   id INTEGER PRIMARY KEY AUTOINCREMENT,
                   namespace TEXT NOT NULL DEFAULT 'default',
                   src TEXT NOT NULL,
                   dst TEXT NOT NULL,
                   kind TEXT NOT NULL,
                   weight REAL NOT NULL DEFAULT 1.0,
                   created_at REAL NOT NULL)"""
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS recall_log(
                   id INTEGER PRIMARY KEY AUTOINCREMENT,
                   namespace TEXT NOT NULL DEFAULT 'default',
                   query TEXT NOT NULL,
                   hits INTEGER NOT NULL,
                   created_at REAL NOT NULL)"""
            )
            st.execute("CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT)")
            st.execute(
                """CREATE TABLE IF NOT EXISTS skills(
                   id INTEGER PRIMARY KEY AUTOINCREMENT,
                   namespace TEXT NOT NULL DEFAULT 'default',
                   name TEXT NOT NULL,
                   description TEXT NOT NULL DEFAULT '',
                   trigger TEXT NOT NULL DEFAULT '',
                   steps TEXT NOT NULL DEFAULT '',
                   tools TEXT NOT NULL DEFAULT '',
                   status TEXT NOT NULL DEFAULT 'active',
                   version INTEGER NOT NULL DEFAULT 1,
                   success_count INTEGER NOT NULL DEFAULT 0,
                   failure_count INTEGER NOT NULL DEFAULT 0,
                   created_at REAL NOT NULL,
                   updated_at REAL NOT NULL,
                   last_used_at REAL,
                   UNIQUE(namespace, name))"""
            )
        }
    }

    fun findByHash(ns: String, hash: String): MemoryRow? {
        conn.prepareStatement(
            "SELECT id,kind,text,summary,importance,pinned,status,updated_at,access_count,source " +
                "FROM memories WHERE namespace=? AND hash=?"
        ).use { ps ->
            ps.setString(1, ns); ps.setString(2, hash)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return MemoryRow(
                    rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getDouble(5), rs.getInt(6) != 0, rs.getString(7), rs.getDouble(8),
                    rs.getInt(9), rs.getString(10)
                )
            }
        }
    }

    fun insert(
        ns: String, kind: String, text: String, summary: String, hash: String,
        importance: Double, pinned: Boolean, source: String, now: Double
    ): Long {
        conn.prepareStatement(
            "INSERT INTO memories(namespace,kind,text,summary,hash,importance,pinned,status," +
                "source,created_at,updated_at,accessed_at,access_count,meta) " +
                "VALUES(?,?,?,?,?,?,?, 'active', ?,?,?,?,0,'{}')"
        ).use { ps ->
            ps.setString(1, ns); ps.setString(2, kind); ps.setString(3, text); ps.setString(4, summary)
            ps.setString(5, hash); ps.setDouble(6, importance); ps.setInt(7, if (pinned) 1 else 0)
            ps.setString(8, source); ps.setDouble(9, now); ps.setDouble(10, now); ps.setDouble(11, now)
            ps.executeUpdate()
        }
        conn.createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); return rs.getLong(1) }
        }
    }

    fun touch(id: Long) {
        val now = System.currentTimeMillis() / 1000.0
        conn.prepareStatement("UPDATE memories SET access_count=access_count+1, accessed_at=?, updated_at=? WHERE id=?")
            .use { ps -> ps.setDouble(1, now); ps.setDouble(2, now); ps.setLong(3, id); ps.executeUpdate() }
    }

    fun active(ns: String, limit: Int = 2000): List<MemoryRow> {
        val out = ArrayList<MemoryRow>()
        conn.prepareStatement(
            "SELECT id,kind,text,summary,importance,pinned,status,updated_at,access_count,source " +
                "FROM memories WHERE namespace=? AND status IN ('active','dormant') " +
                "ORDER BY updated_at DESC LIMIT ?"
        ).use { ps ->
            ps.setString(1, ns); ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                while (rs.next()) out.add(
                    MemoryRow(
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getDouble(5), rs.getInt(6) != 0, rs.getString(7), rs.getDouble(8),
                        rs.getInt(9), rs.getString(10)
                    )
                )
            }
        }
        return out
    }

    fun upsertEntity(ns: String, name: String): Long {
        val now = System.currentTimeMillis() / 1000.0
        conn.prepareStatement("INSERT OR IGNORE INTO entities(namespace,name,type,mention_count,created_at) VALUES(?,?,'concept',1,?)")
            .use { ps -> ps.setString(1, ns); ps.setString(2, name); ps.setDouble(3, now); ps.executeUpdate() }
        conn.prepareStatement("SELECT id FROM entities WHERE namespace=? AND name=?").use { ps ->
            ps.setString(1, ns); ps.setString(2, name)
            ps.executeQuery().use { rs -> rs.next(); return rs.getLong(1) }
        }
    }

    fun linkEntity(mid: Long, eid: Long) {
        conn.prepareStatement("INSERT OR IGNORE INTO memory_entities(memory_id,entity_id) VALUES(?,?)")
            .use { ps -> ps.setLong(1, mid); ps.setLong(2, eid); ps.executeUpdate() }
    }

    fun entitiesFor(mid: Long): List<String> {
        val out = ArrayList<String>()
        conn.prepareStatement(
            "SELECT e.name FROM memory_entities me JOIN entities e ON e.id=me.entity_id WHERE me.memory_id=?"
        ).use { ps ->
            ps.setLong(1, mid)
            ps.executeQuery().use { rs -> while (rs.next()) out.add(rs.getString(1)) }
        }
        return out
    }

    fun softDelete(id: Long) {
        conn.prepareStatement("UPDATE memories SET status='deleted', updated_at=? WHERE id=?")
            .use { ps -> ps.setDouble(1, System.currentTimeMillis() / 1000.0); ps.setLong(2, id); ps.executeUpdate() }
    }

    fun topByKind(ns: String, kinds: List<String>, limit: Int): List<MemoryRow> {
        val marks = kinds.joinToString(",") { "?" }
        val out = ArrayList<MemoryRow>()
        conn.prepareStatement(
            "SELECT id,kind,text,summary,importance,pinned,status,updated_at,access_count,source " +
                "FROM memories WHERE namespace=? AND status='active' AND kind IN ($marks) ORDER BY importance DESC LIMIT ?"
        ).use { ps ->
            ps.setString(1, ns)
            kinds.forEachIndexed { i, k -> ps.setString(i + 2, k) }
            ps.setInt(kinds.size + 2, limit)
            ps.executeQuery().use { rs ->
                while (rs.next()) out.add(
                    MemoryRow(
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getDouble(5), rs.getInt(6) != 0, rs.getString(7), rs.getDouble(8),
                        rs.getInt(9), rs.getString(10)
                    )
                )
            }
        }
        return out
    }


    /** 一次取全库实体映射，避免召回时逐行查询 */
    fun entitiesAll(): Map<Long, List<String>> {
        val m = HashMap<Long, MutableList<String>>()
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT me.memory_id, e.name FROM memory_entities me JOIN entities e ON e.id=me.entity_id"
            ).use { rs ->
                while (rs.next()) m.getOrPut(rs.getLong(1)) { ArrayList() }.add(rs.getString(2))
            }
        }
        return m
    }

    fun setStatus(id: Long, status: String) {
        conn.prepareStatement("UPDATE memories SET status=?, updated_at=? WHERE id=?").use { ps ->
            ps.setString(1, status); ps.setDouble(2, System.currentTimeMillis() / 1000.0)
            ps.setLong(3, id); ps.executeUpdate()
        }
    }

    fun skillUpsert(ns: String, name: String, body: String, score: Double): Long {
        val now = System.currentTimeMillis() / 1000.0
        conn.prepareStatement(
            "INSERT INTO skills(namespace,name,steps,status,created_at,updated_at) VALUES(?,?,?,'active',?,?) " +
                "ON CONFLICT(namespace,name) DO UPDATE SET steps=excluded.steps, updated_at=excluded.updated_at"
        ).use { ps ->
            ps.setString(1, ns); ps.setString(2, name); ps.setString(3, body)
            ps.setDouble(4, now); ps.setDouble(5, now); ps.executeUpdate()
        }
        conn.prepareStatement("SELECT id FROM skills WHERE namespace=? AND name=?").use { ps ->
            ps.setString(1, ns); ps.setString(2, name)
            ps.executeQuery().use { rs -> rs.next(); return rs.getLong(1) }
        }
    }

    fun skillList(ns: String): List<Triple<String, Double, Int>> {
        val out = ArrayList<Triple<String, Double, Int>>()
        conn.prepareStatement("SELECT name,success_count,failure_count FROM skills WHERE namespace=? ORDER BY success_count DESC, updated_at DESC").use { ps ->
            ps.setString(1, ns); ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val s = rs.getInt(2); val f = rs.getInt(3)
                    out.add(Triple(rs.getString(1), if (s + f == 0) 1.0 else s.toDouble() / (s + f), s + f))
                }
            }
        }
        return out
    }

    fun skillGet(ns: String, name: String): String? {
        conn.prepareStatement("SELECT steps FROM skills WHERE namespace=? AND name=?").use { ps ->
            ps.setString(1, ns); ps.setString(2, name)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getString(1) else null }
        }
    }

    fun counts(ns: String): Map<String, Int> {
        val m = LinkedHashMap<String, Int>()
        conn.prepareStatement("SELECT COUNT(*) FROM memories WHERE namespace=? AND status='active'").use { ps ->
            ps.setString(1, ns); ps.executeQuery().use { rs -> rs.next(); m["total"] = rs.getInt(1) }
        }
        conn.prepareStatement("SELECT COUNT(*) FROM memories WHERE namespace=? AND pinned=1 AND status='active'").use { ps ->
            ps.setString(1, ns); ps.executeQuery().use { rs -> rs.next(); m["pinned"] = rs.getInt(1) }
        }
        conn.prepareStatement("SELECT COUNT(*) FROM entities WHERE namespace=?").use { ps ->
            ps.setString(1, ns); ps.executeQuery().use { rs -> rs.next(); m["entities"] = rs.getInt(1) }
        }
        return m
    }

    fun logRecall(ns: String, query: String, hits: Int) {
        conn.prepareStatement("INSERT INTO recall_log(namespace,query,hit_ids,created_at) VALUES(?,?,?,?)").use { ps ->
            ps.setString(1, ns); ps.setString(2, query); ps.setString(3, "[$hits]")
            ps.setDouble(4, System.currentTimeMillis() / 1000.0); ps.executeUpdate()
        }
    }

    override fun close() = conn.close()
}
