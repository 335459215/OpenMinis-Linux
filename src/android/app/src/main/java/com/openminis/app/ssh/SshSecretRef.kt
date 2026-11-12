package com.openminis.app.ssh

/**
 * [T-ssh-credential-ref] A stored SSH secret is either a literal value or a
 * pointer at an app environment variable.
 *
 * Pointers resolve on the CONNECT path, never at write time: rotating the
 * variable takes effect without re-saving the host, and the raw secret is
 * never written into the host store.
 *
 * Two spellings are accepted, because this repo already ships both:
 *  - `$$NAME`   — the provider / MCP convention (ProvidersCollection, McpJsonRpc)
 *  - `env:NAME` — the spelling advertised on SshTool's `add_host` parameters
 */
sealed class SshSecretRef {
    /** Pointer at an environment variable; the caller resolves it. */
    data class EnvVar(val key: String) : SshSecretRef()

    /** Raw stored value; used verbatim. */
    data class Literal(val value: String) : SshSecretRef()
}

object SshSecretRefs {
    /** Literal text is `$$`. Escaped so the source stays readable. */
    const val DOLLAR_PREFIX: String = "\$\$"

    /** SshTool-style prefix. */
    const val ENV_PREFIX: String = "env:"

    /** Environment variable names, matching [com.openminis.app.data.repository.EnvVarRepository]'s KEY_REGEX. */
    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /**
     * True when [raw] carries a reference prefix, whether or not the name that
     * follows is well-formed. Callers use this to distinguish "user meant a
     * reference but typo'd it" (worth an error) from "ordinary literal".
     */
    fun hasRefPrefix(raw: String): Boolean {
        val t = raw.trim()
        return t.startsWith(DOLLAR_PREFIX) || t.startsWith(ENV_PREFIX)
    }

    /**
     * Extracts the variable name when [raw] is a well-formed reference.
     *
     * @return the name, or null when [raw] is a literal **or** a malformed
     *         reference. Pair with [hasRefPrefix] to tell those apart.
     */
    fun envKeyOrNull(raw: String): String? {
        val t = raw.trim()
        val name = when {
            t.startsWith(DOLLAR_PREFIX) -> t.removePrefix(DOLLAR_PREFIX).trim()
            t.startsWith(ENV_PREFIX) -> t.removePrefix(ENV_PREFIX).trim()
            else -> return null
        }
        return name.takeIf { NAME.matches(it) }
    }

    /** Reference when well-formed, literal otherwise. */
    fun parse(raw: String): SshSecretRef =
        envKeyOrNull(raw)?.let { SshSecretRef.EnvVar(it) } ?: SshSecretRef.Literal(raw)
}
