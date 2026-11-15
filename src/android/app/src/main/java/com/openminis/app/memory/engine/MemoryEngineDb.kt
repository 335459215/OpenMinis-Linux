package com.openminis.app.memory.engine

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 结构化记忆库：与 Markdown 记忆并存，给 recall 提供打分与实体索引。
 * 独立库文件，避免触碰 Room 的 schema 版本。
 */
internal class MemoryEngineDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS memories(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                namespace TEXT NOT NULL DEFAULT 'default',
                kind TEXT NOT NULL DEFAULT 'fact',
                text TEXT NOT NULL,
                summary TEXT NOT NULL DEFAULT '',
                hash TEXT NOT NULL,
                importance REAL NOT NULL DEFAULT 0.6,
                pinned INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'active',
                source TEXT NOT NULL DEFAULT 'app',
                created_at REAL NOT NULL,
                updated_at REAL NOT NULL,
                accessed_at REAL NOT NULL,
                access_count INTEGER NOT NULL DEFAULT 0,
                UNIQUE(namespace, hash))""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS entities(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                namespace TEXT NOT NULL DEFAULT 'default',
                name TEXT NOT NULL,
                UNIQUE(namespace, name))""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS memory_entities(
                memory_id INTEGER NOT NULL,
                entity_id INTEGER NOT NULL,
                PRIMARY KEY(memory_id, entity_id))""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS recall_log(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                namespace TEXT NOT NULL DEFAULT 'default',
                query TEXT NOT NULL,
                hits INTEGER NOT NULL,
                created_at REAL NOT NULL)""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1：没有迁移，只保留现有数据。
    }

    companion object {
        private const val DB_NAME = "minis-memory-engine.db"
        private const val DB_VERSION = 1
    }
}
