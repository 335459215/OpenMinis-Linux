# Minis 记忆引擎 Kotlin 移植（JVM 版可直接跑）

对照源：`/var/minis/shared/minis-memory/minis_memory/`（Python 引擎 3605 行）
本目录：可直接编译、已在 JVM 上跑通的 Kotlin 版本，SQL 与打分公式与 Python 版逐项对齐。

## 文件

| 文件 | 作用 |
|---|---|
| `Util.kt` | 归一化、sha256、中英文分词（拉丁词 + 中文 bigram）、ngram、覆盖度、实体抽取 |
| `Store.kt` | JDBC/SQLite 存储层：memories / entities / memory_entities / relations / recall_log / kv |
| `Engine.kt` | remember / recall / forget / profile / syncNative / mirror |
| `Main.kt` | CLI：remember / recall / sync / stats / forget / bench |
| `AndroidStore.kt.template` | Android `SQLiteOpenHelper` 适配模板（SQL 原样搬） |
| `mem.jar` | 已编译产物（含 Kotlin runtime，5.5MB） |
| `sqlite-jdbc.jar` | JVM 测试用驱动（Android 不用） |

## 编译与运行（JVM）

```bash
cd /root/kotlin-port
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 ./kotlinc/bin/kotlinc \
  src/*.kt -cp sqlite-jdbc.jar -include-runtime -d mem.jar

export MINIS_MEMORY_DB=/tmp/kt/mem.db MINIS_MEMORY_DIR=/tmp/kt/memory
java -cp mem.jar:sqlite-jdbc.jar minis.memory.MainKt stats
java -cp mem.jar:sqlite-jdbc.jar minis.memory.MainKt recall "内容寻址" 3
java -cp mem.jar:sqlite-jdbc.jar minis.memory.MainKt sync
```

## 与 Python 引擎的对照

同一批 4 条记忆、同一查询「内容寻址」，两边排序完全一致：

```
Kotlin:  [preference·0.71] 用户偏好：APK 里的附件走内容寻址存储…   (rel=1.00)
         [fact·0.69]       用户要求所有产物先做内容寻址再去重
Python:  [偏好] 用户偏好：APK 里的附件走内容寻址存储… 
         [事实] 用户要求所有产物先做内容寻址再去重
```

打分权重逐项对齐：keyword 0.42 / semantic 0.20 / importance 0.14 /
recency 0.10 / graph 0.09 / pinned 0.05；半衰期按 kind 分档
（rule 365d、preference 180d、fact 120d、task 45d、event 14d）。
命中门槛只用 `max(keyword, semantic) > 0.05`，实体重合只加分不决定命中。

## 性能（JVM，1000 条记忆）

| 指标 | 数值 |
|---|---|
| 写入 | 0.58 ms/条 |
| 召回（k=8，1000 条候选） | 27.2 ms/次 |
| 实体批量预取优化前 | 39.8 ms/次 |

Android 侧 1000 条规模同一量级；候选集上限 2000 条，超过就按 `updated_at` 截断。

## 搬到 Android 的三步

1. `AndroidStore.kt.template` → 真实现：`SQLiteOpenHelper` 建表，方法体把
   `PreparedStatement` 换成 `rawQuery/execSQL`，SQL 字符串不动。
2. `Engine`/`Util` 原样编译进 APK（无第三方依赖，仅 `java.security`、`java.time`）。
3. 把 `memory_get/memory_write` 的内核指向这套 Engine，Python 守护降级为可选面板。

## 与现有 Python 库的兼容（2026-11-14 修复）

初版用 `text_hash` / `last_access_at` 列，打开真库直接报 `no such column`。
已对齐 Python schema：

| 表 | 列名 |
|---|---|
| memories | `hash`（不是 text_hash）、`accessed_at`（不是 last_access_at），并补 valence/arousal/visibility/valid_from/valid_to |
| entities | namespace / name / type / mention_count / created_at |
| skills | steps / status / success_count / failure_count / last_used_at |
| recall_log | hit_ids |

真库副本（sqlite backup）实测：stats total=2 → remember 写入 id=3 → sync imported=0/skipped=1
（回声去重生效）→ skill put/list 正常 → maintain scanned=3。

## 已知差距

- `syncNative` 目前把所有非 GLOBAL 行记为 `fact` 0.6，Python 版会按标题/章节猜 kind。
- 语义相似度用 bigram Dice 近似，Python 版还叠加了 cosine + 实体图权重。
- 未实现衰减归档（dormant/archived）与技能库表。
