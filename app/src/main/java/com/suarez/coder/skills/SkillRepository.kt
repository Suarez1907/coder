package com.suarez.coder.skills

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Global skills (stored on the device) + repo skills (`.coder/skills/<name>/SKILL.md` in the active repository).
 * A repo skill overrides a global one with the same name. Only [catalog] lines go into the system prompt; the body is
 * returned by [load] (the load_skill tool), so adding skills never inflates every request.
 */
class SkillRepository(
    private val dir: File,
    /** Paths of the repository tree (null when unavailable). */
    private val listPaths: suspend (repo: String) -> List<String>?,
    private val readFile: suspend (repo: String, path: String) -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val globalFile = File(dir, "skills.json")
    private val disabledFile = File(dir, "skills_disabled.json")
    private val repoCache = HashMap<String, Pair<Long, List<Skill>>>()

    init {
        dir.mkdirs()
        seedBuiltins()
    }

    companion object {
        const val REPO_TTL_MS = 10 * 60_000L
        const val MAX_REPO_SKILLS = 20
        const val MAX_BODY_CHARS = 30_000
        private val REPO_SKILL = Regex("^\\.coder/skills/([^/]+)/SKILL\\.md$", RegexOption.IGNORE_CASE)

        fun repoSkillPaths(paths: List<String>): List<Pair<String, String>> =
            paths.mapNotNull { p -> REPO_SKILL.find(p)?.let { SkillParser.normalizeName(it.groupValues[1]) to p } }
                .filter { SkillParser.isValidName(it.first) }.take(MAX_REPO_SKILLS)

        /** The catalog block for the system prompt (sorted, one line per skill), or null when there is none. */
        fun catalogText(skills: List<Skill>): String? {
            val active = skills.filter { it.enabled }.sortedBy { it.name }
            if (active.isEmpty()) return null
            return "SKILLS (call load_skill(name) to get the full instructions before doing such work):\n" +
                active.joinToString("\n") { it.catalogLine() }
        }

        /** What load_skill returns: the full instructions plus how to run its cloud tool. */
        fun loadText(s: Skill): String = buildString {
            append("SKILL ").append(s.name).append(" (").append(s.source.label).append(")\n\n")
            append(s.body.take(MAX_BODY_CHARS))
            s.workflow?.let {
                append("\n\nCLOUD TOOL: run_cloud_tool(tool=\"").append(it).append("\", script_path=..., output_dir=...) ")
                append("runs the script in GitHub Actions (workflow .github/workflows/coder-").append(it).append(".yml, installed on demand).")
            }
        }
    }

    // ---- Global skills ----

    @Synchronized fun globals(): List<Skill> {
        val disabled = disabled()
        return runCatching { JSONArray(globalFile.readText()) }.getOrDefault(JSONArray()).let { a ->
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val src = runCatching { Skill.Source.valueOf(o.optString("src")) }.getOrDefault(Skill.Source.GLOBAL)
                SkillParser.parse(o.optString("md"), o.optString("name"), src)?.let { it.copy(enabled = it.name !in disabled) }
            }
        }
    }

    @Synchronized fun saveGlobal(s: Skill) {
        val list = globals().filter { it.name != s.name } + s.copy(source = if (s.source == Skill.Source.REPO) Skill.Source.GLOBAL else s.source)
        writeGlobals(list)
    }

    @Synchronized fun deleteGlobal(name: String) = writeGlobals(globals().filter { it.name != name })

    private fun writeGlobals(list: List<Skill>) = globalFile.writeText(JSONArray().also { a ->
        list.sortedBy { it.name }.forEach { a.put(JSONObject().put("name", it.name).put("src", it.source.name).put("md", it.toMarkdown())) }
    }.toString())

    private fun seedBuiltins() {
        val marker = File(dir, "skills_seeded")
        if (marker.exists()) return
        val existing = globals().map { it.name }.toSet()
        val add = BuiltinSkills.ALL.mapNotNull { SkillParser.parse(it, source = Skill.Source.BUILTIN) }.filter { it.name !in existing }
        if (add.isNotEmpty()) writeGlobals(globals() + add)
        marker.writeText("1")
    }

    // ---- Enable / disable (by name, for both sources) ----

    @Synchronized fun disabled(): Set<String> = runCatching { JSONArray(disabledFile.readText()) }.getOrDefault(JSONArray())
        .let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }

    @Synchronized fun setEnabled(name: String, enabled: Boolean) {
        val d = disabled().toMutableSet()
        if (enabled) d -= name else d += name
        disabledFile.writeText(JSONArray(d.sorted()).toString())
    }

    // ---- Repo skills ----

    /** Discovers repo skills (cached for a few minutes); safe to call before every run. */
    suspend fun refreshRepo(repo: String, force: Boolean = false): List<Skill> {
        if (repo.isBlank()) return emptyList()
        val key = repo.lowercase()
        synchronized(this) { repoCache[key]?.let { (at, list) -> if (!force && now() - at < REPO_TTL_MS) return list } }
        val paths = runCatching { listPaths(repo) }.getOrNull() ?: return cachedRepo(repo)
        val skills = repoSkillPaths(paths).mapNotNull { (name, path) ->
            runCatching { readFile(repo, path) }.getOrNull()?.let { SkillParser.parse(it, name, Skill.Source.REPO, path) }
        }
        synchronized(this) { repoCache[key] = now() to skills }
        return skills
    }

    @Synchronized fun cachedRepo(repo: String): List<Skill> = repoCache[repo.lowercase()]?.second.orEmpty()

    /** Global + repo skills (repo wins on a name clash), with their enabled state. */
    fun all(repo: String): List<Skill> {
        val disabled = disabled()
        val repoSkills = cachedRepo(repo).map { it.copy(enabled = it.name !in disabled) }
        val names = repoSkills.map { it.name }.toSet()
        return (globals().filter { it.name !in names } + repoSkills).sortedBy { it.name }
    }

    fun catalog(repo: String): String? = catalogText(all(repo))

    /** The load_skill tool. */
    fun load(name: String, repo: String): String {
        val n = SkillParser.normalizeName(name.removePrefix("/"))
        val all = all(repo)
        val s = all.firstOrNull { it.name == n } ?: return "Yetenek bulunamadı: $name. Mevcut: ${all.filter { it.enabled }.joinToString { it.name }}"
        if (!s.enabled) return "Yetenek kapalı: ${s.name} (Ayarlar > Yetenekler'den açılabilir)."
        return loadText(s)
    }
}
