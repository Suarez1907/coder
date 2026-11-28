package com.suarez.coder.formal

/**
 * Faz 8A — Biçimsel Orkestrasyon Durum Makinesi (`OrchestrationFsm`).
 *
 * Çekirdek orkestrasyon yetki ve geçiş kararlarını tek bir değişmez (immutable) veri modeli ve
 * saf deterministik geçiş fonksiyonu ([transition]) ile tanımlar.
 *
 * 5 Güvenlik Özelliği ([FsmModelChecker] tarafından tüm erişilebilir durum uzayında kanıtlanır):
 * 1) `INV_NO_READONLY_DEADLOCK`
 * 2) `INV_ARCHITECT_LOCK`
 * 3) `INV_NO_EVOLUTION_AUTO_MERGE`
 * 4) `INV_NO_MERGE_ON_RED_CI`
 * 5) `INV_TERMINATION_GUARD`
 */
object OrchestrationFsm {

    enum class TaskIntent { QUESTION, WRITE }
    enum class ExecutionMode { SINGLE_AGENT, ARCHITECT_EDITOR, CONTRACT_DAG, SWARM }
    enum class ActiveRole { IMPLEMENTER, LOCKED_ARCHITECT }
    enum class ToolAccess { READ_ONLY, READ_WRITE }
    enum class CiState { IDLE, RUNNING, FAILED, PASSED }
    enum class Phase { PLANNING, EDITING, HANDOFF, HEALING, MERGING, HALTED_SAFE }

    enum class FsmEvent {
        ADAPTIVE_ESCALATE,
        OSCILLATION_BREAK,
        WATCHDOG_ABORT,
        PREFLIGHT_BLOCK,
        PROOF_VIOLATION,
        CI_FAIL,
        CI_PASS,
        REQUEST_AUTO_MERGE,
        HANDOFF_TO_IMPLEMENTER,
        BUDGET_EXHAUSTED,
    }

    data class FsmState(
        val taskIntent: TaskIntent,
        val executionMode: ExecutionMode,
        val activeRole: ActiveRole,
        val toolAccess: ToolAccess,
        val ciState: CiState = CiState.IDLE,
        val isEvolutionSession: Boolean = false,
        val architectModelOverridden: Boolean = false,
        val mergeExecuted: Boolean = false,
        val phase: Phase = Phase.PLANNING,
    )

    /** Standard reachable initial states across intents, modes, and evolution isolation. */
    fun initialStates(): List<FsmState> {
        val out = ArrayList<FsmState>()
        for (intent in TaskIntent.values()) {
            for (mode in ExecutionMode.values()) {
                for (evo in listOf(false, true)) {
                    val access = if (intent == TaskIntent.WRITE) ToolAccess.READ_WRITE else ToolAccess.READ_ONLY
                    val role = if (mode == ExecutionMode.ARCHITECT_EDITOR) ActiveRole.LOCKED_ARCHITECT else ActiveRole.IMPLEMENTER
                    out += FsmState(
                        taskIntent = intent,
                        executionMode = mode,
                        activeRole = role,
                        toolAccess = access,
                        ciState = CiState.IDLE,
                        isEvolutionSession = evo,
                        architectModelOverridden = false,
                        mergeExecuted = false,
                        phase = Phase.PLANNING,
                    )
                }
            }
        }
        return out
    }

    /**
     * Pure deterministic transition function preserving all 5 safety properties across every event sequence.
     */
    fun transition(state: FsmState, event: FsmEvent): FsmState {
        if (state.phase == Phase.HALTED_SAFE) return state
        return when (event) {
            FsmEvent.BUDGET_EXHAUSTED -> state.copy(
                phase = Phase.HALTED_SAFE,
                mergeExecuted = false,
            )
            FsmEvent.PROOF_VIOLATION -> state.copy(
                phase = Phase.HALTED_SAFE,
                mergeExecuted = false,
            )
            FsmEvent.ADAPTIVE_ESCALATE -> {
                // Escalates to locked architect (NEVER overrides architectModelOverridden).
                // If WRITE intent enters EDITING, guarantees READ_WRITE access (no READ_ONLY deadlock).
                val nextAccess = if (state.taskIntent == TaskIntent.WRITE) ToolAccess.READ_WRITE else state.toolAccess
                val nextPhase = if (state.taskIntent == TaskIntent.WRITE) Phase.EDITING else Phase.PLANNING
                state.copy(
                    activeRole = ActiveRole.LOCKED_ARCHITECT,
                    toolAccess = nextAccess,
                    phase = nextPhase,
                    architectModelOverridden = false,
                )
            }
            FsmEvent.HANDOFF_TO_IMPLEMENTER -> {
                val nextAccess = if (state.taskIntent == TaskIntent.WRITE) ToolAccess.READ_WRITE else ToolAccess.READ_ONLY
                val nextPhase = if (state.taskIntent == TaskIntent.WRITE) Phase.EDITING else Phase.HANDOFF
                state.copy(
                    activeRole = ActiveRole.IMPLEMENTER,
                    toolAccess = nextAccess,
                    phase = nextPhase,
                )
            }
            FsmEvent.OSCILLATION_BREAK -> {
                val nextAccess = if (state.taskIntent == TaskIntent.WRITE) ToolAccess.READ_WRITE else state.toolAccess
                state.copy(
                    activeRole = ActiveRole.LOCKED_ARCHITECT,
                    toolAccess = nextAccess,
                    phase = if (state.taskIntent == TaskIntent.WRITE) Phase.HEALING else Phase.HALTED_SAFE,
                    architectModelOverridden = false,
                )
            }
            FsmEvent.WATCHDOG_ABORT -> state.copy(
                toolAccess = if (state.taskIntent == TaskIntent.WRITE) ToolAccess.READ_WRITE else state.toolAccess,
                phase = if (state.taskIntent == TaskIntent.WRITE) Phase.HEALING else Phase.HALTED_SAFE,
            )
            FsmEvent.PREFLIGHT_BLOCK -> state.copy(
                toolAccess = if (state.taskIntent == TaskIntent.WRITE) ToolAccess.READ_WRITE else state.toolAccess,
                phase = if (state.taskIntent == TaskIntent.WRITE) Phase.HEALING else Phase.HALTED_SAFE,
                mergeExecuted = false,
            )
            FsmEvent.CI_FAIL -> state.copy(
                ciState = CiState.FAILED,
                toolAccess = if (state.taskIntent == TaskIntent.WRITE) ToolAccess.READ_WRITE else state.toolAccess,
                phase = if (state.taskIntent == TaskIntent.WRITE) Phase.HEALING else Phase.HALTED_SAFE,
                mergeExecuted = false,
            )
            FsmEvent.CI_PASS -> state.copy(
                ciState = CiState.PASSED,
                phase = if (state.phase == Phase.HEALING || state.phase == Phase.EDITING) Phase.HANDOFF else state.phase,
            )
            FsmEvent.REQUEST_AUTO_MERGE -> {
                // Hard safety gates: NEVER merge in evolution session, NEVER merge unless CI PASSED.
                if (state.isEvolutionSession || state.ciState != CiState.PASSED) {
                    state.copy(phase = Phase.HALTED_SAFE, mergeExecuted = false)
                } else {
                    state.copy(phase = Phase.MERGING, mergeExecuted = true)
                }
            }
        }
    }
}
