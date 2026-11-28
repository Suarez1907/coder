package com.suarez.coder.evolution

import com.suarez.coder.formal.FsmModelChecker
import com.suarez.coder.harness.PreFlightValidator

/**
 * Faz 8B — Kırmızı Takım vs. Mavi Takım Ortak Evrimi (`AdversarialCoEvolutionEngine`).
 *
 * Kırmızı Çizgiler:
 * - Varsayılan KAPALI (`enabled = false`); yalnızca şarj + Wi-Fi + idle veya manuel 1 tur taramayla çalışır.
 * - Kırmızı Takım saldırıları **yalnızca yerel bellek/sandbox** içinde çalışır; hiçbir gerçek dala bozuk kod push etmez.
 * - Mavi Takım aşı PR'ları `isEvolutionSession = true` ile izole edilir, asla otomatik merge edilmez (`autoMerge = false`),
 *   kilitli Mimar modeline asla dokunmaz ve gece bütçe tavanı (`dailyBudgetUsd = 0.05`) dışına çıkmaz.
 */
object AdversarialCoEvolutionEngine {

    const val DEFAULT_DAILY_BUDGET_USD = 0.05
    const val MAX_LLM_SCENARIOS_PER_NIGHT = 3

    enum class AttackCategory {
        BROKEN_SETTINGS_JSON,
        PREFLIGHT_EVASION,
        SECRET_LEAK_PATTERN,
        MALFORMED_SKILL_FRONTMATTER,
        FSM_PERMUTATION,
    }

    data class RedTeamProbe(
        val id: String,
        val category: AttackCategory,
        val path: String,
        val payload: String,
        val expectedBlocked: Boolean = true,
    )

    data class VulnerabilityFinding(
        val probe: RedTeamProbe,
        val detail: String,
    )

    data class VaccineDraftPr(
        val branchName: String,
        val isEvolutionSession: Boolean = true,
        val autoMerge: Boolean = false,
        val lockedArchitectModelId: String,
        val finding: VulnerabilityFinding,
        val regressionTestCaseJson: String,
    )

    data class CoEvolutionReport(
        val executed: Boolean,
        val probesRun: Int,
        val findings: List<VulnerabilityFinding>,
        val vaccineDrafts: List<VaccineDraftPr>,
        val spentUsd: Double,
        val autoMerged: Boolean = false,
        val stopReason: String,
    )

    /** Built-in $0 deterministic Red Team mutation probes (purely local in-memory). */
    fun deterministicProbes(): List<RedTeamProbe> = listOf(
        RedTeamProbe("rt_json_broken", AttackCategory.BROKEN_SETTINGS_JSON, "settings.json", "{ \"architect_model\": "),
        RedTeamProbe("rt_yaml_tab", AttackCategory.PREFLIGHT_EVASION, ".github/workflows/ci.yml", "jobs:\n\tbuild:\n\t\truns-on: ubuntu-latest"),
        RedTeamProbe("rt_merge_conflict", AttackCategory.PREFLIGHT_EVASION, "App.kt", "<<<<<<< HEAD\nval a = 1\n=======\nval a = 2\n>>>>>>> main"),
        RedTeamProbe("rt_unbalanced_kt", AttackCategory.PREFLIGHT_EVASION, "Broken.kt", "package a\nclass X { fun f() { return 1 }"),
    )

    /**
     * Runs one bounded Red-vs-Blue co-evolution cycle purely in-memory.
     * [shieldEvaluator] returns true when existing shields successfully block [RedTeamProbe].
     */
    suspend fun runCycle(
        enabled: Boolean,
        lockedArchitectModelId: String,
        dailyBudgetUsd: Double = DEFAULT_DAILY_BUDGET_USD,
        alreadySpentUsd: Double = 0.0,
        probes: List<RedTeamProbe> = deterministicProbes(),
        shieldEvaluator: (RedTeamProbe) -> Boolean = ::defaultShieldCheck,
        llmCompleter: EvolutionCompleter? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): CoEvolutionReport {
        if (!enabled) {
            return CoEvolutionReport(false, 0, emptyList(), emptyList(), 0.0, false, "Kırmızı/Mavi Ortak Evrim kapalı.")
        }
        val remainingCap = (dailyBudgetUsd - alreadySpentUsd).coerceAtLeast(0.0)
        var spent = 0.0
        val findings = ArrayList<VulnerabilityFinding>()
        val vaccines = ArrayList<VaccineDraftPr>()

        // 1) Verify FSM 5/5 safety properties
        val fsm = FsmModelChecker.verifyAll()
        if (!fsm.verified) {
            val probe = RedTeamProbe("rt_fsm", AttackCategory.FSM_PERMUTATION, "OrchestrationFsm.kt", fsm.summary())
            findings += VulnerabilityFinding(probe, fsm.summary())
        }

        // 2) Run deterministic $0 Red Team probes in memory
        for (probe in probes) {
            val blocked = shieldEvaluator(probe)
            if (probe.expectedBlocked && !blocked) {
                findings += VulnerabilityFinding(probe, "Kalkan bu saldırıyı yakalayamadı: ${probe.id} (${probe.category})")
            }
        }

        // 3) Blue Team vaccinator: generate isolated evolution draft PRs (NEVER auto-merge)
        for (finding in findings) {
            val left = (remainingCap - spent).coerceAtLeast(0.0)
            if (llmCompleter != null && left > 1e-6 && vaccines.size < MAX_LLM_SCENARIOS_PER_NIGHT) {
                val reply = runCatching {
                    llmCompleter.complete(
                        "Bu açığı kapatan en küçük doğrulama kuralını 1 cümlede özetle.",
                        "${finding.probe.id}: ${finding.detail}",
                        left,
                    )
                }.getOrNull()
                if (reply != null && reply.costUsd >= 0.0 && spent + reply.costUsd <= remainingCap + 1e-9) {
                    spent += reply.costUsd
                }
            }
            val regJson = """{"id":"vaccine_${finding.probe.id}","category":"${finding.probe.category}","path":"${finding.probe.path}","expectBlocked":true}"""
            vaccines += VaccineDraftPr(
                branchName = "evo/blue-vaccine-$nowMs-${vaccines.size}",
                isEvolutionSession = true,
                autoMerge = false,
                lockedArchitectModelId = lockedArchitectModelId,
                finding = finding,
                regressionTestCaseJson = regJson,
            )
            if (spent >= remainingCap - 1e-9 && llmCompleter != null) break
        }

        return CoEvolutionReport(
            executed = true,
            probesRun = probes.size + 1,
            findings = findings,
            vaccineDrafts = vaccines,
            spentUsd = spent,
            autoMerged = false,
            stopReason = if (findings.isEmpty()) "Tüm kalkanlar %100 geçti." else "${findings.size} açık için izole aşı taslağı hazırlandı.",
        )
    }

    private fun defaultShieldCheck(probe: RedTeamProbe): Boolean =
        PreFlightValidator.check(probe.path, null, probe.payload).isNotEmpty()
}
