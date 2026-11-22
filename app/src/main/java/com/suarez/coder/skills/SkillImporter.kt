package com.suarez.coder.skills

import com.suarez.coder.tools.GitHub

/**
 * Finds SKILL.md files in GitHub repositories (starred ones, or a pasted link) and imports them as global skills,
 * together with the small text files next to them (references, templates, scripts).
 */
class SkillImporter(
    private val tree: suspend (repo: String, ref: String?) -> Pair<String, List<GitHub.TreeEntry>>?,
    private val read: suspend (repo: String, path: String, ref: String) -> String?,
) {
    /** One importable skill found in a repository. */
    data class Candidate(
        val repo: String,
        val ref: String,
        /** Path of SKILL.md. */
        val path: String,
        val name: String,
        val description: String,
        val files: List<GitHub.TreeEntry>,
    ) {
        val folder get() = path.substringBeforeLast('/', "")
        val origin get() = "$repo@$ref:$path"
        val hasScripts get() = files.any { isScript(it.path) }
        /** Path relative to the skill folder. */
        fun relative(p: String) = if (folder.isEmpty()) p else p.removePrefix("$folder/")
    }

    /** owner/repo, an optional ref and an optional path inside it. */
    data class Ref(val repo: String, val ref: String?, val path: String?)

    companion object {
        const val MAX_SKILLS_PER_REPO = 40
        const val MAX_FILE_BYTES = 100_000L
        const val MAX_TOTAL_BYTES = 300_000L
        const val MAX_FILES = 25
        private val TEXT_EXT = setOf("md", "txt", "py", "sh", "js", "ts", "mjs", "json", "yaml", "yml", "toml", "html", "css", "xml",
            "csv", "sql", "rb", "ps1", "kt", "java", "gd", "cs", "lua", "glsl", "tex", "jinja", "j2", "tmpl", "template")
        private val SCRIPT_EXT = setOf("py", "sh", "js", "ts", "mjs", "rb", "ps1")
        private val SKIP_DIRS = setOf("node_modules", ".git", "dist", "build", "__pycache__", ".venv", "venv")

        fun isScript(path: String) = path.substringAfterLast('.', "").lowercase() in SCRIPT_EXT

        /** Accepts owner/repo, github.com/owner/repo, …/tree/<ref>/<dir>, …/blob/<ref>/<dir>/SKILL.md. */
        fun parseRef(input: String): Ref? {
            val t = input.trim().removePrefix("https://").removePrefix("http://").removePrefix("www.").removePrefix("github.com/")
                .substringBefore('?').substringBefore('#').trim('/').removeSuffix(".git")
            val parts = t.split('/').filter { it.isNotBlank() }
            if (parts.size < 2 || parts.take(2).any { !Regex("^[A-Za-z0-9_.-]+$").matches(it) }) return null
            val repo = "${parts[0]}/${parts[1]}"
            if (parts.size >= 4 && parts[2] in setOf("tree", "blob")) {
                val path = parts.drop(4).joinToString("/").ifBlank { null }
                return Ref(repo, parts[3], path)
            }
            return Ref(repo, null, null)
        }

        /** SKILL.md files in a tree (case-insensitive), skipping vendored folders. */
        fun skillPaths(entries: List<GitHub.TreeEntry>, under: String? = null): List<String> {
            // A link to one SKILL.md means exactly that skill.
            if (under != null && under.substringAfterLast('/').equals("SKILL.md", true)) {
                return entries.map { it.path }.filter { it.equals(under.trim('/'), true) }
            }
            val prefix = under?.removeSuffix("/SKILL.md")?.removeSuffix("/skill.md")?.trim('/')?.ifBlank { null }
            return entries.map { it.path }
                .filter { it.substringAfterLast('/').equals("SKILL.md", true) }
                .filter { p -> p.split('/').none { it in SKIP_DIRS } }
                .filter { p -> prefix == null || p == "$prefix/${p.substringAfterLast('/')}" || p.startsWith("$prefix/") || p.equals(under, true) }
                .sorted().take(MAX_SKILLS_PER_REPO)
        }

        /**
         * Text files that belong to the skill at [skillPath]: inside its folder, not inside a nested skill, small enough;
         * at most [MAX_FILES] and [MAX_TOTAL_BYTES] in total. A SKILL.md at the repository root brings no files.
         */
        fun resourcesFor(skillPath: String, entries: List<GitHub.TreeEntry>): List<GitHub.TreeEntry> {
            val folder = skillPath.substringBeforeLast('/', "")
            if (folder.isEmpty()) return emptyList()
            val nested = skillPaths(entries).map { it.substringBeforeLast('/', "") }.filter { it != folder && it.startsWith("$folder/") }
            var total = 0L
            return entries.asSequence()
                .filter { it.path.startsWith("$folder/") && it.path != skillPath }
                .filter { e -> nested.none { e.path.startsWith("$it/") } }
                .filter { e -> e.path.split('/').none { it in SKIP_DIRS } }
                .filter { it.path.substringAfterLast('.', "").lowercase() in TEXT_EXT && it.size in 1..MAX_FILE_BYTES }
                .sortedBy { it.path }
                .takeWhile { total += it.size; total <= MAX_TOTAL_BYTES }
                .take(MAX_FILES).toList()
        }
    }

    /** Skills in [repo] (or only under [under]); each SKILL.md is read for its name and description. */
    suspend fun scan(repo: String, ref: String? = null, under: String? = null): List<Candidate> {
        val (branch, entries) = tree(repo, ref) ?: return emptyList()
        return skillPaths(entries, under).mapNotNull { p ->
            val text = read(repo, p, branch) ?: return@mapNotNull null
            val fallback = p.substringBeforeLast('/', "").substringAfterLast('/').ifBlank { repo.substringAfter('/') }
            val s = SkillParser.parse(text, fallback) ?: return@mapNotNull null
            Candidate(repo, branch, p, s.name, s.description, resourcesFor(p, entries))
        }
    }

    /** Reads SKILL.md and its files; the result is a global skill that remembers where it came from. */
    suspend fun import(c: Candidate): Skill? {
        val text = read(c.repo, c.path, c.ref) ?: return null
        val fallback = c.folder.substringAfterLast('/').ifBlank { c.repo.substringAfter('/') }
        val s = SkillParser.parse(text, fallback, Skill.Source.GLOBAL) ?: return null
        val res = c.files.mapNotNull { f -> read(c.repo, f.path, c.ref)?.let { c.relative(f.path) to it } }.toMap()
        return s.copy(origin = c.origin, resources = res)
    }

    /** Re-imports a skill from its origin ("owner/repo@ref:path"); the default branch is used when the ref is gone. */
    suspend fun update(origin: String): Skill? {
        val repo = origin.substringBefore('@')
        val path = origin.substringAfter(':', "").ifBlank { return null }
        val c = scan(repo, null, path).firstOrNull { it.path.equals(path, true) } ?: return null
        return import(c)
    }
}
