package com.suarez.coder.security

/**
 * Local commit gate. Faz 4 did not leave a class with this name; this is the deterministic scanner the
 * zero-token code-mod engine runs next to [PreFlightValidator]. It never calls a model or the network.
 * Findings are secrets in added lines (via [Secrets]) plus a few commands that must not land in a commit.
 */
object SecurityScanner {
    data class Finding(val path: String, val line: Int, val kind: String)

    private val DANGER = listOf(
        "uzak betik calistirma" to Regex("""(?i)\b(curl|wget)\b[^\n]{0,80}\|\s*(sh|bash|zsh)\b"""),
        "Runtime.exec" to Regex("""\bRuntime\.getRuntime\(\)\.exec\s*\("""),
        "ProcessBuilder" to Regex("""\bnew\s+ProcessBuilder\s*\("""),
        "os.system" to Regex("""\bos\.system\s*\("""),
        "shell=True" to Regex("""\bsubprocess\.(Popen|run|call)\s*\([^)]*shell\s*=\s*True"""),
    )

    fun scan(path: String, before: String?, after: String?, ownKeys: List<String> = emptyList()): List<Finding> {
        if (after == null) return emptyList()
        val secrets = Secrets.scan(path, before, after, ownKeys).map { Finding(it.path, it.line, it.kind) }
        val added = Secrets.addedLines(before, after)
        val danger = added.mapNotNull { (n, line) ->
            DANGER.firstOrNull { it.second.containsMatchIn(line) }?.let { Finding(path, n, it.first) }
        }
        return (secrets + danger).distinctBy { "${it.path}:${it.line}:${it.kind}" }.take(8)
    }

    fun summary(findings: List<Finding>): String =
        findings.joinToString("\n") { "${it.path}:${it.line} ${it.kind}" }
}
