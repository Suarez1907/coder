package com.suarez.coder.context

import com.suarez.coder.data.Settings
import com.suarez.coder.tools.GitHub

/** Loads the files the user pinned as context; contents are memoised until the repo changes. */
class ContextProvider(private val settings: Settings, private val github: GitHub) {
    private val memo = HashMap<String, String?>()

    data class Loaded(val text: String, val files: Int, val chars: Int, val skipped: List<String>)

    companion object {
        const val MAX_TOTAL_CHARS = 150_000
        const val NOTES_PATH = ".coder/NOTES.md"
        const val AUTO_MAX_PER_FILE = 30_000
        const val AUTO_MAX_TOTAL = 80_000
    }

    fun invalidate() = memo.clear()

    /** Exam runs: a different repo/branch and no pinned files, without touching the user's settings. */
    var repoOverride: String? = null
    var refOverride: String? = null
    val repo: String get() = repoOverride ?: settings.contextRepo.ifBlank { settings.defaultRepo }
    val ref: String? get() = refOverride ?: settings.contextRef.ifBlank { null }
    val hasManualFiles: Boolean get() = settings.contextFiles.isNotEmpty()

    /** Persistent project notes (.coder/NOTES.md), or null when the repo has none. */
    suspend fun loadNotes(): String? {
        if (repo.isBlank()) return null
        val key = "$repo@${ref ?: ""}:.coder/NOTES.md"
        val text = if (memo.containsKey(key)) memo[key] else runCatching { github.readFileRaw(repo, NOTES_PATH, ref) }.getOrNull().also { memo[key] = it }
        return text?.takeIf { it.isNotBlank() }?.take(8_000)
    }

    /** The user's pinned files. */
    suspend fun load(): Loaded? = load(repo, ref, if (repoOverride != null) emptyList() else settings.contextFiles, "Kullanıcının seçtiği")

    /** Files picked automatically for one request. */
    suspend fun loadAuto(repo: String, ref: String?, files: List<String>): Loaded? =
        load(repo, ref, files, "Akıllı dosya seçicinin (Decision 1) bu istek için seçtiği", AUTO_MAX_PER_FILE, AUTO_MAX_TOTAL)

    private suspend fun load(
        repo: String, ref: String?, files: List<String>, who: String,
        maxPerFile: Int = Int.MAX_VALUE, maxTotal: Int = MAX_TOTAL_CHARS,
    ): Loaded? {
        if (repo.isBlank() || files.isEmpty()) return null
        val sb = StringBuilder()
        val skipped = ArrayList<String>()
        var count = 0
        for (path in files.sorted()) {
            if (FileFilter.isBinaryOrLock(path)) { skipped += "$path (binary/lock)"; continue }
            val key = "$repo@${ref ?: ""}:$path"
            val content = if (memo.containsKey(key)) memo[key] else runCatching { github.readFileRaw(repo, path, ref) }.getOrNull().also { memo[key] = it }
            if (content == null) { skipped += "$path (bulunamadı)"; continue }
            // Large files (e.g. generated level data) are cut: the editor reads the full file itself when it edits it.
            val lines = content.count { it == '\n' } + 1
            val text = if (content.length <= maxPerFile) content
                else content.take(maxPerFile) + "\n… (dosya $lines satır, ${content.length} karakter; devamı için github_read_file ile aralık oku)"
            if (sb.length + text.length > maxTotal) { skipped += "$path (bağlam sınırı aşıldı)"; continue }
            sb.append("### ").append(path).append("\n```\n").append(text).append(if (text.endsWith("\n")) "" else "\n").append("```\n\n")
            count++
        }
        if (count == 0) return null
        val header = "$who bağlam dosyaları (repo: $repo${ref?.let { ", ref: $it" } ?: ""}). " +
            "Bunlar zaten elinde; tekrar okuma. Commit sonrası değişmiş olabilirler.\n\n"
        return Loaded(header + sb.toString(), count, sb.length, skipped)
    }
}
