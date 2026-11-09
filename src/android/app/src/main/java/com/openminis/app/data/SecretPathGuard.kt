package com.openminis.app.data

/**
 * Path-level guard for read commands whose output would otherwise land in the
 * model's context verbatim.
 *
 * [EnvVarRedactor] masks env-var *values* that leak into tool output, which
 * covers `echo $TOKEN` but NOT `cat .git/config` / `grep` over a credentials
 * file: there the secret is file content, not an env-var value, so the redactor
 * never sees a match. This guard closes that hole at the *command* layer —
 * before execution output is captured — by refusing reads of well-known
 * credential-bearing paths.
 *
 * Design notes:
 *  - Only *read-ish* verbs are considered (`cat`, `grep`, `tail`, `less`, ...).
 *    A plain `rm .env` or `ls -la .env` does not disclose content and is left
 *    alone, so legitimate workflows (gitignoring, chmod) keep working.
 *  - Matching is on the whole command string with a word-boundary-anchored
 *    path regex, deliberately conservative: a false positive costs the agent
 *    one retry, a false negative costs the user a leaked credential.
 *  - Escape hatch: callers may pass [allowedPaths] (populated from an explicit
 *    user grant in Settings) whose commands bypass the guard.
 */
object SecretPathGuard {

    /** Read utility names that would dump file content into stdout. */
    private val READ_VERBS = Regex(
        """(?i)(?:^|[\s;&|(`])(?:cat|tac|head|tail|less|more|nl|od|xxd|hexdump|""" +
            """strings|base64|grep|egrep|fgrep|rg|awk|sed|cut|sort|uniq|jq|yq)\b"""
    )

    /** Commands that read via shell redirection, e.g. `source .env`. */
    private val SOURCE_RE = Regex("""(?i)(?:^|[\s;&|(`])(?:source|\.)\s+""")

    /**
     * Paths whose content is credential-bearing. Anchored on segment
     * boundaries so `/app/.env.example` and `docs/env.md` don't trip it, while
     * `.env`, `deploy/.env.production`, `x/.git/config` do.
     */
    private val SENSITIVE_PATHS = listOf(
        // dotenv files, but keep *.example / *.sample / *.template readable
        Regex("""(?i)(?:^|[/\s'"=])\.env(?:\.[\w-]+)?(?=$|[\s'";&|<>)])"""),
        // git internals — config may embed a PAT in the remote URL
        Regex("""(?i)(?:^|[/\s'"])\.git/(?:config|credentials|FETCH_HEAD|packed-refs)"""),
        // ssh / gpg material
        Regex("""(?i)(?:^|[/\s'"/])(?:id_rsa|id_dsa|id_ecdsa|id_ed25519)(?:\.pub)?(?=$|[\s'";&|])"""),
        Regex("""(?i)/\.ssh/(?:id_[a-z0-9]+|known_hosts|authorized_keys|config)(?=$|[\s'";&|])"""),
        Regex("""(?i)/\.gnupg/"""),
        // cloud / registry credentials
        Regex("""(?i)/\.aws/credentials"""),
        Regex("""(?i)/\.(?:docker|kube|config/gcloud)/config(?:\.json)?"""),
        Regex("""(?i)/\.npmrc(?=$|[\s'";&|])"""),
        Regex("""(?i)/\.pypirc(?=$|[\s'";&|])"""),
        Regex("""(?i)/\.netrc(?=$|[\s'";&|])"""),
        Regex("""(?i)/\.git-credentials"""),
        // private keys / keystores
        Regex("""(?i)\.(?:pem|p12|pfx|jks|keystore|key)(?=$|[\s'";&|])"""),
        Regex("""(?i)/etc/shadow(?=$|[\s'";&|])"""),
    )

    /** Filenames that are fine even though they sit next to secrets. */
    private val SAFE_SUFFIX = Regex("""(?i)\.(?:example|sample|template|md|mdx|txt)$""")

    /**
     * @return the matched sensitive path token, or null when the command is
     *         not a content-reading access to a credential-bearing path.
     */
    fun offendingPath(command: String, allowedPaths: Set<String> = emptySet()): String? {
        if (command.isBlank()) return null
        val reads = READ_VERBS.containsMatchIn(command) || SOURCE_RE.containsMatchIn(command)
        if (!reads) return null
        for (rx in SENSITIVE_PATHS) {
            val hit = rx.find(command)?.value?.trim(' ', '\'', '"', '=', '/', '|', ';', '&', '(', '`')
            if (hit.isNullOrEmpty()) continue
            if (SAFE_SUFFIX.containsMatchIn(hit)) continue
            if (allowedPaths.any { hit.endsWith(it.trimStart('/')) }) continue
            return hit
        }
        return null
    }

    /** Message returned to the model instead of the file contents. */
    fun blockedMessage(path: String): String = buildString {
        appendLine("Error: blocked by SecretPathGuard — '$path' may contain credentials.")
        appendLine("Its contents were NOT read and are not in your context.")
        appendLine("If the value is needed, reference it through its environment variable")
        appendLine("(e.g. use \$VAR inside the command) instead of reading the file.")
        append("If the user explicitly authorised this path in Settings, retry after that grant.")
    }
}
