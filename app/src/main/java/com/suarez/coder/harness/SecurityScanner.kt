package com.suarez.coder.harness

import kotlin.math.log2

/**
 * $0 scan of a staged tree before push. A real secret blocks the push. Placeholders and test fixtures do not.
 * The architect model is not consulted.
 */
object SecurityScanner {
    data class Hit(val path: String, val line: Int, val kind: String, val detail: String)

    data class Report(val blocked: Boolean, val hits: List<Hit>) {
        fun repairPrompt(): String = if (!blocked) "" else buildString {
            append("Güvenlik taraması push'u durdurdu.\n")
            hits.forEach { append(it.path).append(':').append(it.line).append(' ').append(it.kind).append(" — ").append(it.detail).append('\n') }
            append("Gizlileri kaldır, tehlikeli izin veya exported/cleartext ekleme, imza dosyalarına dokunma.")
        }
    }

    fun scan(tx: StagedTransaction): Report {
        val hits = ArrayList<Hit>()
        for (edit in tx.edits()) {
            if (edit.after == null) continue
            hits += scanText(edit.path, edit.before, edit.after)
        }
        return Report(hits.isNotEmpty(), hits.distinctBy { "${it.path}:${it.line}:${it.kind}:${it.detail}" })
    }

    fun scanText(path: String, before: String?, after: String): List<Hit> {
        val hits = ArrayList<Hit>()
        val added = addedLines(before, after)
        for ((n, line) in added) {
            TOKEN.findAll(line).forEach { m ->
                if (!isPlaceholder(m.value)) hits += Hit(path, n, "secret", "Bilinen anahtar kalıbı.")
            }
            if (line.contains("-----BEGIN PRIVATE KEY-----") && !isPlaceholder(line) && !isFixture(path)) {
                hits += Hit(path, n, "secret", "Özel anahtar bloğu.")
            }
            entropyHit(path, n, line)?.let { hits += it }
            signingHit(path, n, line)?.let { hits += it }
        }
        if (path.endsWith("AndroidManifest.xml")) hits += manifestHits(path, before, after)
        if (isSensitivePath(path) && before != after && !isFixture(path)) {
            hits += Hit(path, 1, "sensitive_file", "Anahtar deposu veya güvenlik kilidi dosyası değişti.")
        }
        return hits
    }

    private fun addedLines(before: String?, after: String): List<Pair<Int, String>> {
        if (before == null) return after.lines().mapIndexed { i, l -> i + 1 to l }
        val old = before.lines().groupingBy { it }.eachCount().toMutableMap()
        val out = ArrayList<Pair<Int, String>>()
        after.lines().forEachIndexed { i, line ->
            val left = old[line] ?: 0
            if (left > 0) old[line] = left - 1 else if (line.isNotBlank()) out += (i + 1) to line
        }
        return out
    }

    private fun entropyHit(path: String, lineNo: Int, line: String): Hit? {
        if (isFixture(path)) return null
        val m = ASSIGN.find(line) ?: return null
        val value = m.groupValues[2]
        if (value.length < 24 || isPlaceholder(value)) return null
        if (shannon(value) < 3.7) return null
        return Hit(path, lineNo, "entropy", "Yüksek entropili gizli sabit.")
    }

    private fun signingHit(path: String, lineNo: Int, line: String): Hit? {
        if (isFixture(path)) return null
        val l = line.lowercase()
        val touched = "storepassword" in l || "keypassword" in l || "signingconfig" in l
        if (!touched || isPlaceholder(line)) return null
        return Hit(path, lineNo, "signing", "İmzalama parolası veya imza bloğu eklendi.")
    }

    private fun manifestHits(path: String, before: String?, after: String): List<Hit> {
        val hits = ArrayList<Hit>()
        val oldPerms = permissions(before)
        permissions(after).filter { it !in oldPerms && it in DANGEROUS }.forEach { perm ->
            hits += Hit(path, lineOf(after, perm), "manifest_permission", "Yeni tehlikeli izin: $perm")
        }
        if (countAttr(after, "android:exported=\"true\"") > countAttr(before, "android:exported=\"true\"")) {
            hits += Hit(path, lineOf(after, "android:exported=\"true\""), "manifest_exported", "android:exported=\"true\" eklendi.")
        }
        if (countAttr(after, "android:usesCleartextTraffic=\"true\"") > countAttr(before, "android:usesCleartextTraffic=\"true\"")) {
            hits += Hit(path, lineOf(after, "usesCleartextTraffic"), "manifest_cleartext", "usesCleartextTraffic=\"true\" eklendi.")
        }
        return hits
    }

    private fun permissions(xml: String?): Set<String> {
        if (xml.isNullOrBlank()) return emptySet()
        return PERM.findAll(xml).map { it.groupValues[1].substringAfterLast('.') }.toSet()
    }

    private fun countAttr(xml: String?, needle: String) = if (xml == null) 0 else Regex.fromLiteral(needle).findAll(xml).count()

    private fun lineOf(text: String, needle: String): Int {
        val i = text.lines().indexOfFirst { needle in it }
        return if (i < 0) 1 else i + 1
    }

    fun shannon(value: String): Double {
        if (value.isEmpty()) return 0.0
        val n = value.length.toDouble()
        return -value.groupingBy { it }.eachCount().values.sumOf { c ->
            val p = c / n
            p * log2(p)
        }
    }

    fun isPlaceholder(value: String): Boolean {
        val v = value.lowercase()
        if (WORDS.any { it in v }) return true
        if (v.length >= 8 && v.all { it == v[0] }) return true
        if (v.count { it == 'x' || it == '*' } > v.length * 0.5) return true
        return false
    }

    fun isFixture(path: String): Boolean {
        val p = path.replace('\\', '/').lowercase()
        return "/fixtures/" in p || "/fixture/" in p || p.contains("placeholder") || p.contains("/sample/") ||
            p.endsWith("fakesecrets.txt")
    }

    fun isSensitivePath(path: String): Boolean {
        val p = path.replace('\\', '/').lowercase()
        if (p.endsWith(".jks") || p.endsWith(".keystore") || p.endsWith(".p12")) return true
        if (p.endsWith("keystore.properties")) return true
        return SENSITIVE.containsMatchIn(p)
    }

    private val TOKEN = Regex("ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AIza[0-9A-Za-z\\-_]{20,}|sk-or-[A-Za-z0-9\\-_]{10,}|sk-ant-[A-Za-z0-9\\-_]{10,}|xoxb-[A-Za-z0-9\\-]{10,}")
    private val ASSIGN = Regex("(?i)(token|secret|password|api[_-]?key|private[_-]?key|auth)\\s*[:=]\\s*[\"']([A-Za-z0-9+/=_\\-]{24,})[\"']")
    private val PERM = Regex("android:name=\"(android\\.permission\\.[A-Z0-9_]+)\"")
    private val SENSITIVE = Regex("(^|/)(auth|security)/(lock|pin|biometric)")
    private val WORDS = listOf("example", "placeholder", "changeme", "dummy", "fake", "your_", "redacted", "sample", "insert", "replace_me", "todo", "testtoken", "not_a_secret", "yourtoken")
    private val DANGEROUS = setOf(
        "CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
        "READ_CONTACTS", "WRITE_CONTACTS", "READ_SMS", "SEND_SMS", "RECEIVE_SMS", "READ_CALL_LOG", "WRITE_CALL_LOG",
        "READ_PHONE_STATE", "CALL_PHONE", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES",
        "READ_MEDIA_VIDEO", "BODY_SENSORS", "ACTIVITY_RECOGNITION",
    )
}
