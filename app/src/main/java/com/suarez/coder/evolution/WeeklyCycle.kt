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
        queueDefaultExperiment()
        out += runExperiments()
        return out.filter { it.isNotBlank() }.joinToString(" ")
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
        }
        val hash = configHash(settings, rules, enabledSkills())
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
        }
    }

    /** Undo payload from a log entry; returns true when handled. */
    fun undo(json: String): Boolean {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return false
        when (o.optString("kind")) {
            "MODEL" -> if (o.optString("role") == "architect") settings.architectModel = o.optString("old") else settings.editorModel = o.optString("old")
            "RULE" -> store.setPolicyRules(store.policyRules() - o.optString("text"))
            "SKILL" -> skills.setEnabled(o.optString("name"), false)
            else -> return false
        }
        return true
    }
}
