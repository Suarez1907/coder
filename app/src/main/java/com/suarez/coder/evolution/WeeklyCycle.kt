package com.suarez.coder.evolution

import android.content.Context
import com.suarez.coder.agent.Agent
import com.suarez.coder.agent.StopException
import com.suarez.coder.cost.Pricing
import com.suarez.coder.data.Settings
import com.suarez.coder.llm.ModelRef
import com.suarez.coder.skills.SkillImporter
import com.suarez.coder.skills.SkillRepository
import org.json.JSONObject
import java.io.File

/**
 * The weekly self-improvement loop: crash watch (and rollback at level 3), GitHub scouting, then exams that decide
 * queued experiments within the weekly exam budget. Changes are applied only when they win (level 2+) or proposed.
 */
class WeeklyCycle(
    private val context: Context,
    private val settings: Settings = Settings(context),
    private val store: EvolutionStore = EvolutionEngine.storeFor(context),
    private val onStatus: (String) -> Unit = {},
) {
    companion object {
        const val QUESTIONS = 3
        const val REVIEW_MARK = "## PR incelemelerinden"
        const val MIN_QUESTIONS = 2

        fun weekStart(now: Long) = now - WeeklyReport.WEEK_MS

        /** Configuration fingerprint: a baseline exam result is reused only while this stays the same. */
        fun configHash(s: Settings, rules: List<String>, enabledSkills: List<String>) =
            listOf(s.architectMode, s.architectModel, s.editorModel, s.smartRouting, rules.sorted(), enabledSkills.sorted()).toString().hashCode().toString(16)

        /** A cheaper pool model for a role, not tried yet: the default experiment when nothing else is queued. */
        fun cheaperModel(current: String, pool: List<String>, price: (String) -> Double, tried: Set<String>): String? =
            pool.filter { it != current && it !in tried && price(it) < price(current) * 0.8 }.minByOrNull { price(it) }
    }

    private val agent = Agent(settings) // GitHub + pricing; not used for model calls
    private val skills = SkillRepository(File(context.filesDir, "skills"), { null }, { _, _ -> null })
    val actions = SelfPrActions(agent.tools.github, store)

    private fun appVersion(): Long = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L)

    suspend fun run(): String {
        val out = ArrayList<String>()
        out += watchCrashes()
        runCatching { rollbackIfNeeded() }.getOrNull()?.let { out += it; store.addLog(EvoTier.INFO, it) }
        if (settings.scoutEnabled && settings.githubToken.isNotBlank()) {
            onStatus("GitHub taranıyor")
            val repos = (settings.profiles().map { it.repo } + settings.defaultRepo).filter { it.isNotBlank() }.distinct()
            val importer = SkillImporter({ r, ref -> agent.tools.github.treeEntries(r, ref) }, { r, p, ref -> agent.tools.github.readFileRaw(r, p, ref) })
            out += runCatching { SkillScout(agent.tools.github, importer, skills, store).run(repos) }.getOrElse { "Keşif hatası: ${it.message?.take(120)}" }
            store.addLog(EvoTier.INFO, out.last())
        }
        if (settings.githubToken.isNotBlank()) {
            onStatus("Proje sağlığı kontrol ediliyor")
            val repos = (settings.profiles().map { it.repo } + settings.defaultRepo).filter { it.isNotBlank() }.distinct().take(8)
            val health = ProjectHealth(agent.tools.github)
            val results = repos.map { health.check(agent.tools.normalizeRepo(it)) }
            File(store.dir, "health.json").writeText(org.json.JSONArray().also { a -> results.forEach { a.put(it.toJson()) } }.toString())
            val bad = results.filter { !it.ok }
            if (results.isNotEmpty()) {
                store.addLog(EvoTier.INFO, "Proje sağlığı: " + (if (bad.isEmpty()) "${results.size} repo sorunsuz." else bad.joinToString(" | ") { it.line() }))
                out += "Proje sağlığı: ${results.size - bad.size}/${results.size} sorunsuz."
            }
        }
        if (settings.learnFromReviews && settings.githubToken.isNotBlank()) {
            onStatus("PR yorumlarından kurallar çıkarılıyor")
            runCatching { learnFromReviews() }.getOrElse { "PR yorumları okunamadı: ${it.message?.take(100)}" }.takeIf { it.isNotBlank() }?.let {
                out += it; store.addLog(EvoTier.MEMORY, it)
            }
        }
        // A new app version is measured on the fixed task set first (fair comparison with the previous version).
        if (settings.evalOnUpdate && settings.githubToken.isNotBlank()) {
            val suite = EvalSuite(context, settings, store, onStatus)
            if (suite.evals.runs().lastOrNull()?.version != suite.version()) {
                val left = { settings.examWeeklyBudget - store.spentSince(weekStart(System.currentTimeMillis()), EvoTier.EXAM) }
                if (left() > 0.05) out += runCatching { suite.run(left) }.getOrElse { "Değerlendirme hatası: ${it.message?.take(100)}" }
            }
        }
        // Self-improvement proposals (each must win an exam before it is used).
        if (settings.githubToken.isNotBlank() && store.experiments().none { it.state == Experiment.State.QUEUED }) {
            if (settings.procedureMining) runCatching { mineProcedure() }.getOrNull()?.let { out += it; store.addLog(EvoTier.MEMORY, it) }
        }
        if (settings.promptEvolution && store.experiments().none { it.state == Experiment.State.QUEUED }) {
            runCatching { evolvePrompt() }.getOrNull()?.let { out += it; store.addLog(EvoTier.POLICY, it) }
        }
        queueDefaultExperiment()
        out += runExperiments()
        return out.filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * The user's own PR review comments (new since the last run) become short project rules in the repo's lessons,
     * so the same correction never has to be written twice.
     */
    suspend fun learnFromReviews(): String {
        val gh = agent.tools.github
        val login = JSONObject(gh.raw("GET", "/user", null).substringAfter('\n')).optString("login").ifBlank { return "" }
        val seenFile = File(store.dir, "reviews_seen.json")
        val seen = runCatching { JSONObject(seenFile.readText()) }.getOrDefault(JSONObject())
        val repos = (settings.profiles().map { it.repo } + settings.defaultRepo).map { agent.tools.normalizeRepo(it) }.filter { it.contains('/') }.distinct().take(8)
        var learned = 0
        for (repo in repos) {
            val body = runCatching { gh.raw("GET", "/repos/$repo/pulls/comments?sort=created&direction=desc&per_page=50", null) }.getOrNull() ?: continue
            if (!body.startsWith("HTTP 200")) continue
            val a = runCatching { org.json.JSONArray(body.substringAfter('\n')) }.getOrNull() ?: continue
            val last = seen.optLong(repo)
            val mine = (0 until a.length()).map { a.getJSONObject(it) }
                .filter { it.optJSONObject("user")?.optString("login") == login && it.optLong("id") > last }
            if (mine.isEmpty()) continue
            seen.put(repo, mine.maxOf { it.optLong("id") })
            if (mine.size < 2) continue
            val existing = store.lessons(repo)
            val section = existing.substringAfter(REVIEW_MARK, "").trim()
            val prompt = "EXISTING RULES:\n${section.ifBlank { "(none)" }}\n\nNEW REVIEW COMMENTS:\n" +
                mine.take(30).joinToString("\n") { "- (${it.optString("path")}) ${it.optString("body").replace('\n', ' ').take(400)}" }
            val tmp = ArrayList<com.suarez.coder.llm.ChatMessage>()
            val reply = agent.callModel(tmp, listOf(com.suarez.coder.llm.ChatMessage.User(prompt)), agent.routerRef() ?: agent.editorRef(), "evolution",
                com.suarez.coder.agent.ReviewLearner.SYSTEM, emptyList(), silentUi, useCache = false, maxOut = 800, thinking = "low")
            val cost = tmp.filterIsInstance<com.suarez.coder.llm.ChatMessage.UsageRecord>().sumOf { agent.costOf(it) }
            val rules = com.suarez.coder.agent.ReviewLearner.rules(reply.text)
            if (rules.isEmpty()) continue
            store.saveLessons(repo, (existing.substringBefore(REVIEW_MARK).trimEnd() + "\n\n" + REVIEW_MARK + "\n" + rules.joinToString("\n")).trim())
            store.addLog(EvoTier.MEMORY, "$repo: PR yorumlarından ${rules.size} kural", cost)
            learned += rules.size
        }
        seenFile.writeText(seen.toString())
        return if (learned > 0) "PR yorumlarından $learned kural öğrenildi." else ""
    }

    private val silentUi = object : com.suarez.coder.agent.AgentUi {
        override fun update() {}
        override suspend fun confirm(title: String, body: String, okText: String, cancelText: String) = false
    }

    /** Reports new crashes; a spike after a self-PR update is reverted (level 3) or flagged for the user. */
    fun watchCrashes(): String {
        val crashes = CrashRecorder.collect(context)
        crashes.forEach { store.addLog(EvoTier.INFO, "Uygulama çöktü (sürüm ${it.version}): ${it.headline}") }
        val m = actions.lastMerge() ?: return if (crashes.isEmpty()) "" else "${crashes.size} çökme kaydedildi."
        val all = File(context.filesDir, "crashes").listFiles().orEmpty().mapNotNull { f ->
            Regex("crash-(\\d+)-v(\\d+)").find(f.name)?.let { it.groupValues[1].toLong() to it.groupValues[2].toLong() }
        }
        if (SelfPrActions.isSpike(m, all)) return "" // rollbackIfNeeded decides (it needs the network)
        if (!m.counted && !m.reverted && System.currentTimeMillis() > m.at + SelfPrActions.WATCH_MS) {
            actions.saveLastMerge(m.copy(counted = true))
            store.bump("merged_ok")
        }
        return if (crashes.isEmpty()) "" else "${crashes.size} çökme kaydedildi."
    }

    /** At level 3 a crash spike after a self-PR is reverted automatically; otherwise the user is asked in the UI. */
    suspend fun rollbackIfNeeded(): String? {
        val m = actions.lastMerge() ?: return null
        val all = File(context.filesDir, "crashes").listFiles().orEmpty().mapNotNull { f ->
            Regex("crash-(\\d+)-v(\\d+)").find(f.name)?.let { it.groupValues[1].toLong() to it.groupValues[2].toLong() }
        }
        if (!SelfPrActions.isSpike(m, all)) return null
        if (settings.autonomyLevel < 3) return "Son öz-gelişim güncellemesinden (#${m.number}) sonra çökmeler var. Geri almak için Öz-Gelişim ekranına bakın."
        return actions.revert(m, "güncellemeden sonra çökme artışı")
    }

    /** Reflective prompt evolution: one section rewritten from recent failures, queued as an exam experiment. */
    suspend fun evolvePrompt(): String? {
        val since = System.currentTimeMillis() - 30L * 86_400_000L
        val traces = store.traces().filter { it.at >= since }
        val section = PromptEvolver.target(traces) ?: return null
        val last = store.seenRepos().any { it == "prompt:$section:" + (System.currentTimeMillis() / (7 * 86_400_000L)) }
        if (last) return null // one rewrite per section per week
        val current = store.promptOverrides()[section] ?: com.suarez.coder.agent.PromptSections.default(section)
        val tmp = ArrayList<com.suarez.coder.llm.ChatMessage>()
        val reply = agent.callModel(tmp, listOf(com.suarez.coder.llm.ChatMessage.User(PromptEvolver.prompt(section, current, PromptEvolver.failures(traces, section)))),
            agent.escalationRef() ?: agent.editorRef(), "evolution", PromptEvolver.SYSTEM, emptyList(), silentUi, useCache = false, maxOut = 1_500, thinking = "medium")
        val cost = tmp.filterIsInstance<com.suarez.coder.llm.ChatMessage.UsageRecord>().sumOf { agent.costOf(it) }
        store.markSeen(listOf("prompt:$section:" + (System.currentTimeMillis() / (7 * 86_400_000L))))
        val text = PromptEvolver.clean(section, reply.text, current) ?: return "İstem evrimi: önerilen metin kuralları korumadı, atlandı.".also { store.addLog(EvoTier.POLICY, it, cost) }
        store.addLog(EvoTier.POLICY, "İstem evrimi önerisi hazırlandı: ${com.suarez.coder.agent.PromptSections.label(section)}", cost)
        return if (store.queue(Experiment.prompt(section, text, "istem evrimi"))) "İstem evrimi: ${com.suarez.coder.agent.PromptSections.label(section)} için yeni metin sınava alındı." else null
    }

    /** Procedure memory: a recurring successful procedure becomes a (disabled) skill, queued for an exam. */
    suspend fun mineProcedure(): String? {
        val seen = store.seenRepos()
        val cluster = ProcedureMiner.clusters(store.traces()).firstOrNull { "proc:${it.key}" !in seen } ?: return null
        store.markSeen(listOf("proc:${cluster.key}"))
        val tmp = ArrayList<com.suarez.coder.llm.ChatMessage>()
        val reply = agent.callModel(tmp, listOf(com.suarez.coder.llm.ChatMessage.User(ProcedureMiner.prompt(cluster))),
            agent.routerRef() ?: agent.editorRef(), "evolution", ProcedureMiner.SYSTEM, emptyList(), silentUi, useCache = false, maxOut = 1_500, thinking = "low")
        val cost = tmp.filterIsInstance<com.suarez.coder.llm.ChatMessage.UsageRecord>().sumOf { agent.costOf(it) }
        val md = reply.text.trim().removePrefix("```markdown").removePrefix("```md").removePrefix("```").removeSuffix("```").trim()
        val skill = com.suarez.coder.skills.SkillParser.parse(md, "proc-" + cluster.key, source = com.suarez.coder.skills.Skill.Source.GLOBAL)
            ?.takeIf { it.name.startsWith("proc-") } ?: return null.also { store.addLog(EvoTier.MEMORY, "Prosedür yeteneği yazılamadı.", cost) }
        if (com.suarez.coder.skills.SkillSafety.scan(skill).isNotEmpty()) { store.addLog(EvoTier.MEMORY, "Prosedür yeteneği güvenlik taramasından geçmedi: /${skill.name}", cost); return null }
        skills.saveGlobal(skill)
        skills.setEnabled(skill.name, false) // only an exam win turns it on
        store.addLog(EvoTier.MEMORY, "Prosedür yeteneği önerildi: /${skill.name} (${cluster.traces.size} başarılı görevden)", cost)
        return if (store.queue(Experiment.skill(skill.name, "prosedür madenciliği"))) "Prosedür yeteneği /${skill.name} sınava alındı." else null
    }

    private fun queueDefaultExperiment() {
        if (store.experiments().any { it.state == Experiment.State.QUEUED }) return
        val pool = settings.modelPool
        if (pool.size < 2) return
        val tried = store.experiments().filter { it.kind == Experiment.Kind.MODEL }.map { it.payload.optString("model") }.toSet()
        val price = { m: String -> Pricing.blended(agent.price(m.substringAfter('/'))) }
        cheaperModel(settings.editorModel, pool, price, tried)?.let { store.queue(Experiment.model("editor", it, "daha ucuz model denemesi")) }
    }

    private fun enabledSkills() = skills.all("").filter { it.enabled }.map { it.name }

    private suspend fun runExperiments(): String {
        val exp = store.experiments().firstOrNull { it.state == Experiment.State.QUEUED } ?: return "Sırada deney yok."
        val left = { settings.examWeeklyBudget - store.spentSince(weekStart(System.currentTimeMillis()), EvoTier.EXAM) }
        if (left() <= 0.01) return "Haftalık sınav bütçesi doldu."
        val unusable = store.seenRepos().filter { it.startsWith("case:") }.map { it.removePrefix("case:") }.toSet()
        val cases = ExamCases.pick(ExamCases.from(store.traces(), unusable), QUESTIONS)
        if (cases.size < MIN_QUESTIONS) return "Sınav için yeterli başarılı görev yok (en az $MIN_QUESTIONS gerekli; CI'lı ve başlangıç commit'i bilinen)."
        store.saveExperiment(exp.copy(state = Experiment.State.RUNNING))
        val runner = ExamRunner(context, settings, left, onStatus)
        val rules = store.policyRules()
        val base = ExamRunner.Variant(rules = rules, tag = "mevcut")
        val cand = when (exp.kind) {
            Experiment.Kind.MODEL -> base.copy(roles = ModelRef.parse(exp.payload.optString("model"))?.let { mapOf(exp.payload.optString("role") to it) }.orEmpty(), tag = "model")
            Experiment.Kind.RULE -> base.copy(rules = rules + exp.payload.optString("text"), tag = "kural")
            Experiment.Kind.SKILL -> base.copy(skills = setOf(exp.payload.optString("name")), tag = "yetenek")
            Experiment.Kind.PROMPT -> base.copy(prompts = mapOf(exp.payload.optString("section") to exp.payload.optString("text")), tag = "istem")
        }
        // Adopted prompt sections are part of the configuration a cached baseline belongs to.
        val hash = configHash(settings, rules + store.promptOverrides().entries.map { "${it.key}=${it.value.hashCode()}" }, enabledSkills())
        var b = ExamScore.EMPTY; var c = ExamScore.EMPTY
        try {
            for (case in cases) {
                val cachedBase = store.baseline(hash, case.id)
                val sb = cachedBase ?: runner.score(case, base).also { logExam(runner.lastCost, "taban", case) }
                if (sb == null) { store.markSeen(listOf("case:${case.id}")); continue }
                if (cachedBase == null) store.saveBaseline(hash, case.id, sb)
                val sc = runner.score(case, cand).also { logExam(runner.lastCost, exp.title, case) } ?: continue
                b += sb
                c += sc
            }
        } catch (e: StopException) {
            store.saveExperiment(exp.copy(state = Experiment.State.QUEUED, note = e.message))
            return e.message ?: "Bütçe doldu."
        }
        if (c.total < MIN_QUESTIONS) {
            store.saveExperiment(exp.copy(state = Experiment.State.FAILED, decidedAt = System.currentTimeMillis(), baseline = b, candidate = c,
                note = "Ölçülebilir soru yetmedi (CI başlamadı)."))
            return "${exp.title}: ölçülemedi."
        }
        val won = Experiment.wins(b, c)
        val state = when {
            !won -> Experiment.State.REJECTED
            settings.autonomyLevel >= 2 -> Experiment.State.ADOPTED
            else -> Experiment.State.PROPOSED
        }
        val decided = exp.copy(state = state, decidedAt = System.currentTimeMillis(), baseline = b, candidate = c)
        store.saveExperiment(decided)
        if (state == Experiment.State.ADOPTED) ExperimentApplier(settings, store, skills).apply(decided)
        store.addLog(EvoTier.EXAM, "${exp.title}: ${state.label} (mevcut $b, aday $c)")
        return "${exp.title}: ${state.label}."
    }

    private fun logExam(cost: Double, what: String, case: ExamCase) {
        if (cost > 0) store.addLog(EvoTier.EXAM, "Sınav sorusu (${case.repo}): $what", cost)
    }
}

/** Applies a won experiment and logs how to undo it. */
class ExperimentApplier(private val settings: Settings, private val store: EvolutionStore, private val skills: SkillRepository) {
    fun apply(e: Experiment) {
        when (e.kind) {
            Experiment.Kind.MODEL -> {
                val role = e.payload.optString("role"); val model = e.payload.optString("model")
                val old = if (role == "architect") settings.architectModel else settings.editorModel
                if (role == "architect") settings.architectModel = model else settings.editorModel = model
                store.addLog(EvoTier.MODEL, "Sınavı kazandı, uygulandı: ${e.title}", undo = JSONObject().put("kind", "MODEL").put("role", role).put("old", old).toString())
            }
            Experiment.Kind.RULE -> {
                val text = e.payload.optString("text")
                store.setPolicyRules(store.policyRules() + text)
                store.addLog(EvoTier.POLICY, "Sınavı kazandı, uygulandı: ${e.title}", undo = JSONObject().put("kind", "RULE").put("text", text).toString())
            }
            Experiment.Kind.SKILL -> {
                val name = e.payload.optString("name")
                skills.setEnabled(name, true)
                store.addLog(EvoTier.MEMORY, "Sınavı kazandı, açıldı: /$name", undo = JSONObject().put("kind", "SKILL").put("name", name).toString())
            }
            Experiment.Kind.PROMPT -> {
                val section = e.payload.optString("section")
                val old = store.promptOverrides()[section]
                store.setPromptOverride(section, e.payload.optString("text"))
                store.addLog(EvoTier.POLICY, "Sınavı kazandı, uygulandı: ${e.title}",
                    undo = JSONObject().put("kind", "PROMPT").put("section", section).put("old", old ?: JSONObject.NULL).toString())
            }
        }
    }

    /** Undo payload from a log entry; returns true when handled. */
    fun undo(json: String): Boolean {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return false
        when (o.optString("kind")) {
            "MODEL" -> if (o.optString("role") == "architect") settings.architectModel = o.optString("old") else settings.editorModel = o.optString("old")
            "RULE" -> store.setPolicyRules(store.policyRules() - o.optString("text"))
            "SKILL" -> skills.setEnabled(o.optString("name"), false)
            "PROMPT" -> store.setPromptOverride(o.optString("section"), if (o.isNull("old")) null else o.optString("old"))
            else -> return false
        }
        return true
    }
}
