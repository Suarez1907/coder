package com.suarez.coder.qa

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Lightweight crash file plus a parser for pasted logcat. The handler writes, then delegates to the previous
 * handler so the system crash flow still runs. Model weights are not involved.
 */
object CrashLoopRecorder {
    const val BANNER = "Önceki oturumda çökme yakalandı"
    const val FIX = "Kök Nedeni Düzelt"

    data class Record(val at: Long, val screen: String, val repo: String, val version: String, val trace: String)

    data class Frame(val file: String, val line: Int, val symbol: String, val raw: String)

    @Volatile var screen: () -> String = { "" }
    @Volatile var repo: () -> String = { "" }
    @Volatile var version: () -> String = { "" }
    @Volatile private var installed = false
    @Volatile private var dir: File? = null

    private const val FILE = "crash-loop/unresolved.txt"

    fun install(filesDir: File) {
        if (installed) {
            dir = filesDir
            return
        }
        installed = true
        dir = filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(filesDir, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun write(filesDir: File, thread: Thread, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val text = buildString {
            append("at=").append(System.currentTimeMillis()).append('\n')
            append("screen=").append(screen().replace('\n', ' ')).append('\n')
            append("repo=").append(repo().replace('\n', ' ')).append('\n')
            append("version=").append(version().replace('\n', ' ')).append('\n')
            append("thread=").append(thread.name).append('\n')
            append("---\n")
            append(sw.toString().take(20_000))
        }
        val f = File(filesDir, FILE)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    fun pending(filesDir: File): Record? = read(File(filesDir, FILE))

    fun resolve(filesDir: File) {
        val f = File(filesDir, FILE)
        if (!f.exists()) return
        val seen = File(f.parentFile, "resolved-${f.lastModified()}.txt")
        if (!f.renameTo(seen)) f.delete()
    }

    fun read(file: File): Record? {
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val head = text.substringBefore("---\n")
        fun field(k: String) = head.lineSequence().firstOrNull { it.startsWith("$k=") }?.substringAfter("=").orEmpty()
        return Record(field("at").toLongOrNull() ?: 0L, field("screen"), field("repo"), field("version"), text.substringAfter("---\n"))
    }

    fun looksLikeCrash(text: String): Boolean {
        if (text.length < 40) return false
        val t = text
        val hasEx = t.contains("Exception") || t.contains("Error") || t.contains("FATAL")
        val hasFrame = t.contains("\tat ") || t.contains("Caused by") || t.contains("AndroidRuntime") || FRAME.containsMatchIn(t)
        return hasEx && hasFrame
    }

    /** File and line pairs from a pasted logcat or stack trace. Deterministic, no network. */
    fun parseCrashTrace(text: String): List<Frame> {
        val out = ArrayList<Frame>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val m = FRAME.find(line) ?: continue
            val file = m.groupValues[2].ifBlank { m.groupValues[1].substringAfterLast('.') }
            val n = m.groupValues[3].toIntOrNull() ?: continue
            if (n <= 0) continue
            out += Frame(file, n, m.groupValues[1], line)
        }
        return out.distinctBy { "${it.file}:${it.line}:${it.symbol}" }
    }

    fun prompt(record: Record): String = buildString {
        append("Önceki oturum çökmesini kök nedeninden düzelt.\n")
        append("Ekran: ").append(record.screen.ifBlank { "?" }).append('\n')
        append("Repo: ").append(record.repo.ifBlank { "?" }).append('\n')
        append("Sürüm: ").append(record.version.ifBlank { "?" }).append('\n')
        val frames = parseCrashTrace(record.trace)
        if (frames.isNotEmpty()) {
            append("Eşleşen satırlar:\n")
            frames.take(16).forEach { append(it.file).append(':').append(it.line).append(' ').append(it.symbol).append('\n') }
        }
        append("\n")
        append(record.trace.take(6_000))
    }

    private val FRAME = Regex("(?:at\\s+)?([A-Za-z0-9_.$]+)\\((?:([A-Za-z0-9_.]+\\.(?:kt|java|kts)):(\\d+)|[A-Za-z0-9_.$]+)\\)")
}
