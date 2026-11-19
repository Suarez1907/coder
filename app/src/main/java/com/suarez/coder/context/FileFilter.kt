package com.suarez.coder.context

/**
 * Decides which repository files may be sent to the model.
 * Always excludes dependency/build folders, lock files and binary/media files, and honours .gitignore.
 */
class FileFilter(gitignore: String? = null) {
    private data class Rule(val regex: Regex, val negate: Boolean, val dirOnly: Boolean)

    private val rules: List<Rule> = gitignore?.lines()?.mapNotNull { parseRule(it) } ?: emptyList()

    companion object {
        val EXCLUDED_DIRS = setOf(
            "node_modules", ".git", "build", "dist", "out", ".gradle", ".idea", ".next", ".nuxt", "target",
            "__pycache__", ".venv", "venv", "coverage", "Pods", "DerivedData", ".dart_tool", ".cache", ".kotlin",
        )
        val EXCLUDED_FILES = setOf(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "bun.lockb", "poetry.lock", "Pipfile.lock",
            "Gemfile.lock", "composer.lock", "Cargo.lock", "gradlew", "gradlew.bat", ".DS_Store",
        )
        val BINARY_EXT = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg", "psd", "tiff", "heic",
            "mp3", "mp4", "wav", "ogg", "m4a", "webm", "mov", "avi",
            "zip", "gz", "tar", "rar", "7z", "jar", "aar", "apk", "aab", "keystore", "jks", "p12",
            "so", "dylib", "dll", "exe", "class", "dex", "bin", "o", "a", "wasm",
            "ttf", "otf", "woff", "woff2", "eot", "pdf", "db", "sqlite",
        )
        const val MAX_FILE_BYTES = 200_000L

        fun isBinaryOrLock(path: String): Boolean {
            val name = path.substringAfterLast('/')
            if (name in EXCLUDED_FILES || name.endsWith(".min.js") || name.endsWith(".map")) return true
            return name.substringAfterLast('.', "").lowercase() in BINARY_EXT
        }

        private fun parseRule(raw: String): Rule? {
            var line = raw.trimEnd()
            if (line.isBlank() || line.startsWith("#")) return null
            val negate = line.startsWith("!")
            if (negate) line = line.substring(1)
            val dirOnly = line.endsWith("/")
            line = line.trimEnd('/')
            val anchored = line.startsWith("/") || line.trimStart('/').contains('/')
            line = line.trimStart('/')
            if (line.isEmpty()) return null
            val body = globToRegex(line)
            val regex = if (anchored) Regex("^$body(/.*)?$") else Regex("^(.*/)?$body(/.*)?$")
            return Rule(regex, negate, dirOnly)
        }

        private fun globToRegex(glob: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                        if (i + 2 < glob.length && glob[i + 2] == '/') { sb.append("(.*/)?"); i += 3; continue }
                        sb.append(".*"); i += 2; continue
                    }
                    c == '*' -> sb.append("[^/]*")
                    c == '?' -> sb.append("[^/]")
                    c in ".()+|^$@%{}[]\\" -> sb.append('\\').append(c)
                    else -> sb.append(c)
                }
                i++
            }
            return sb.toString()
        }
    }

    fun isExcluded(path: String, size: Long? = null): Boolean {
        val p = path.trim('/')
        val parts = p.split('/')
        if (parts.dropLast(1).any { it in EXCLUDED_DIRS }) return true
        if (isBinaryOrLock(p)) return true
        if (size != null && size > MAX_FILE_BYTES) return true
        var ignored = false
        for (r in rules) {
            val m = r.regex.matches(p)
            // A dir-only rule must match a parent folder, not the file itself.
            val hit = if (r.dirOnly) m && !Regex(r.regex.pattern.removeSuffix("(/.*)?$") + "$").matches(p) else m
            if (hit) ignored = !r.negate
        }
        return ignored
    }
}
