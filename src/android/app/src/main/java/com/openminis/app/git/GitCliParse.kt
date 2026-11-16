package com.openminis.app.git

/**
 * Parse half of the git panel: pure string→model functions that never run a
 * process. Split out of [GitCli] when the branchesCmd/HEAD fallback fix pushed
 * that file past the 800-line new-file cap (scripts/ci/architecture-policy.json
 * rule 1). Membership, not taste: these four take output text and return data,
 * so they test without a shell and change at a different rate than the command
 * builders they were extracted from.
 *
 * Called unqualified from [GitCli] (`import com.openminis.app.git.GitCliParse.*`
 * is unnecessary — same package) and still reachable as `GitCli.parseRemote(…)`
 * through the delegating aliases left behind there, so the 37 existing call
 * sites and GitCliParseTest keep compiling unchanged.
 *
 * Orthogonal to [GitCliCmd]: that one builds the shell strings, this one reads
 * what came back. Neither owns the other's half of the pipeline.
 */
object GitCliParse {

    /**
     * Shell-wrapper noise that must never reach the user-facing error text:
     * the persistent-shell watchdog prints `Killed` / `( sleep …; kill … )`
     * job lines onto stderr, and the probe's own `cfg` printf runs even when
     * cwd is not a repo.
     */
    internal val NOISE_LINE = Regex(
        "(?i)\\bkilled\\b|/bin/bash: line \\d+|^\\(\\s*sleep|sleep \\d+; kill|^cfg\\t",
    )

    /**
     * Rename rows spell the two paths as `old => new` or collapsed as
     * `prefix/{old => new}`; both resolve to `(oldPath, newPath)`. Returns
     * null when the row is not a rename (no ` => ` marker).
     */
    fun splitRename(path: String): Pair<String, String>? {
        val arrow = path.indexOf(" => ")
        if (arrow < 0) return null
        val lhs = path.substring(0, arrow)
        val rhs = path.substring(arrow + 4)
        if ("{" in lhs && rhs.endsWith("}")) {
            val prefix = lhs.substringBeforeLast('{')
            val oldName = lhs.substringAfterLast('{')
            val newName = rhs.substringBeforeLast('}')
            return prefix + oldName to prefix + newName
        }
        return lhs to rhs
    }

    /**
     * First push URL from `git remote -v`, or null when no remote exists.
     * The push entry wins over the fetch entry when their URLs differ;
     * with equal URLs the first line is returned.
     */
    fun parseRemote(output: String): String? {
        var fallback: String? = null
        for (raw in output.lines()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            val f = line.split('\t')
            if (f.size < 2) continue
            val url = f[1].substringBefore(" (").trim()
            if (url.isEmpty()) continue
            if ("(push)" in f[1]) return url
            if (fallback == null) fallback = url
        }
        return fallback
    }

    private const val DIFF_MAX_CHARS = 200_000

    fun parseLog(output: String): List<GitCli.GitCommit> = output.lines().mapNotNull { raw ->
        val line = raw.trimEnd('\r')
        if (line.isBlank()) return@mapNotNull null
        val f = line.split('\u001f')
        if (f.size < 5) return@mapNotNull null
        GitCli.GitCommit(
            hash = f[0],
            shortHash = f[1],
            author = f[2],
            epochSeconds = f[3].toLongOrNull() ?: 0L,
            subject = f.drop(4).joinToString("\u001f"),
        )
    }

    /** Same records as [GitCli.parseLog] with `%P` parents inserted as field 2. */
    fun parseGraphLog(output: String): List<GitCli.GitCommit> = output.lines().mapNotNull { raw ->
        val line = raw.trimEnd('\r')
        if (line.isBlank()) return@mapNotNull null
        val f = line.split('\u001f')
        if (f.size < 6) return@mapNotNull null
        GitCli.GitCommit(
            hash = f[0],
            shortHash = f[2],
            author = f[3],
            epochSeconds = f[4].toLongOrNull() ?: 0L,
            subject = f.drop(5).joinToString("\u001f"),
            parents = f[1].split(' ').filter { it.isNotBlank() },
        )
    }

    /**
     * Graph record WITH the `%D` decoration column (ZCode contract): the
     * refs column is field 6 (the format pins one record per line, so the
     * subject cannot bleed into it). `HEAD -> main` expands to the HEAD and
     * main badges; `tag: v1` strips the prefix; blank → no badges.
     */
    fun parseGraphLogWithRefs(output: String): List<GitCli.GitCommit> = output.lines().mapNotNull { raw ->
        val line = raw.trimEnd('\r')
        if (line.isBlank()) return@mapNotNull null
        val f = line.split('\u001f')
        if (f.size < 7) return@mapNotNull null
        val refs = f[6].split(',').flatMap { token ->
            val t = token.trim()
            when {
                t.isBlank() -> emptyList()
                t.startsWith("HEAD ->") -> listOf("HEAD", t.substringAfter("->").trim())
                else -> listOf(t.removePrefix("tag: "))
            }
        }.distinct()
        GitCli.GitCommit(
            hash = f[0],
            shortHash = f[2],
            author = f[3],
            epochSeconds = f[4].toLongOrNull() ?: 0L,
            subject = f[5],
            parents = f[1].split(' ').filter { it.isNotBlank() },
            refs = refs,
        )
    }

    /**
     * Record line = 9 `\x1f` fields ending with subject+body; the body's own
     * newlines spill into trailing lines and are reassembled verbatim.
     * Shell noise is skipped before the record and inside the body.
     */
    fun parseCommitDetail(output: String): GitCli.GitCommitDetail? {
        val lines = output.lines()
        val start = lines.indexOfFirst { l ->
            !NOISE_LINE.containsMatchIn(l) &&
                l.substringBefore('\u001f').length == 40 &&
                l.split('\u001f').size >= 9
        }
        if (start < 0) return null
        val f = lines[start].split('\u001f')
        val bodyParts = mutableListOf<String>()
        f.drop(8).joinToString("\u001f").trim().takeIf { it.isNotEmpty() }?.let { bodyParts += it }
        for (l in lines.drop(start + 1)) {
            val line = l.trimEnd('\r')
            if (NOISE_LINE.containsMatchIn(line)) continue
            if (line.isBlank()) break
            bodyParts += line
        }
        return GitCli.GitCommitDetail(
            hash = f[0],
            author = f[1],
            authorEmail = f[2],
            authorEpoch = f[3].toLongOrNull() ?: 0L,
            committer = f[4],
            committerEmail = f[5],
            committerEpoch = f[6].toLongOrNull() ?: 0L,
            subject = f[7],
            body = bodyParts.joinToString("\n").trim(),
        )
    }

    /**
     * Per-file stats of one commit. `-\t-` rows are binary files or pure
     * renames; rename rows resolve via [splitRename] into renameFrom/new
     * path (both the `old => new` and `prefix/{old => new}` spellings).
     */
    fun parseCommitNumstat(output: String): List<GitCli.CommitFileStat> {
        val out = mutableListOf<GitCli.CommitFileStat>()
        for (raw in output.lines()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            val f = line.split('\t')
            if (f.size < 3) continue
            val path = f.drop(2).joinToString("\t")
            if (f[0] == "-" && f[1] == "-") {
                val rename = splitRename(path)
                out += if (rename != null) {
                    GitCli.CommitFileStat(
                        path = rename.second,
                        added = 0,
                        deleted = 0,
                        binary = true,
                        renameFrom = rename.first,
                    )
                } else {
                    GitCli.CommitFileStat(path, 0, 0, binary = true)
                }
                continue
            }
            out += GitCli.CommitFileStat(
                path = path,
                added = f[0].toIntOrNull() ?: 0,
                deleted = f[1].toIntOrNull() ?: 0,
                binary = false,
            )
        }
        return out
    }

    internal fun truncateDiff(s: String): String =
        if (s.length <= DIFF_MAX_CHARS) s
        else s.take(DIFF_MAX_CHARS) + "\n… [diff truncated at ${DIFF_MAX_CHARS / 1000}K chars]"
}
