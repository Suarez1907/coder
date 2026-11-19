package com.suarez.coder.context

import com.suarez.coder.data.Settings
import com.suarez.coder.tools.GitHub

/** Loads the files the user pinned as context; contents are memoised until the repo changes. */
class ContextProvider(private val settings: Settings, private val github: GitHub) {
    private val memo = HashMap<String, String?>()

    data class Loaded(val text: String, val files: Int, val chars: Int, val skipped: List<String>)

    companion object { const val MAX_TOTAL_CHARS = 150_000 }

    fun invalidate() = memo.clear()

    suspend fun load(): Loaded? {
        val repo = settings.contextRepo.ifBlank { settings.defaultRepo }
        val files = settings.contextFiles
        if (repo.isBlank() || files.isEmpty()) return null
        val ref = settings.contextRef.ifBlank { null }
        val sb = StringBuilder()
        val skipped = ArrayList<String>()
        var count = 0
        for (path in files.sorted()) {
            if (FileFilter.isBinaryOrLock(path)) { skipped += "$path (binary/lock)"; continue }
            val key = "$repo@${ref ?: ""}:$path"
            val content = if (memo.containsKey(key)) memo[key] else runCatching { github.readFileRaw(repo, path, ref) }.getOrNull().also { memo[key] = it }
            if (content == null) { skipped += "$path (bulunamadı)"; continue }
            if (sb.length + content.length > MAX_TOTAL_CHARS) { skipped += "$path (bağlam sınırı aşıldı)"; continue }
            sb.append("### ").append(path).append("\n```\n").append(content).append(if (content.endsWith("\n")) "" else "\n").append("```\n\n")
            count++
        }
        if (count == 0) return null
        val header = "Kullanıcının seçtiği bağlam dosyaları (repo: $repo${ref?.let { ", ref: $it" } ?: ""}). " +
            "Bunlar zaten elinde; tekrar okuma. Commit sonrası değişmiş olabilirler.\n\n"
        return Loaded(header + sb.toString(), count, sb.length, skipped)
    }
}
