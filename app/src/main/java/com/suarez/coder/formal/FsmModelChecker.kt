package com.suarez.coder.formal

/**
 * Faz 8A — BFS Durum Uzayı Model Kontrolcüsü (`FsmModelChecker`).
 *
 * Başlangıç durumlarından itibaren erişilebilir tüm durum uzayını döngü-korumalı BFS ile tarar
 * (`<500 ms`) ve 5 zorunlu güvenlik özelliğini her erişilen durumda doğrular.
 * İhlal tespit edilirse tam karşı-örnek olay zincirini ([Counterexample.eventTrace]) döndürür.
 */
object FsmModelChecker {

    enum class SafetyProperty {
        INV_NO_READONLY_DEADLOCK,
        INV_ARCHITECT_LOCK,
        INV_NO_EVOLUTION_AUTO_MERGE,
        INV_NO_MERGE_ON_RED_CI,
        INV_TERMINATION_GUARD,
    }

    data class Counterexample(
        val property: SafetyProperty,
        val initialState: OrchestrationFsm.FsmState,
        val violatingState: OrchestrationFsm.FsmState,
        val eventTrace: List<OrchestrationFsm.FsmEvent>,
        val detail: String,
    )

    data class CheckReport(
        val statesExplored: Int,
        val transitionsChecked: Int,
        val propertiesPassed: Int,
        val totalProperties: Int = SafetyProperty.values().size,
        val counterexample: Counterexample? = null,
    ) {
        val verified: Boolean get() = counterexample == null
        fun summary(): String = if (verified) {
            "FSM: $statesExplored durum · $propertiesPassed/$totalProperties güvenlik özelliği kanıtlandı"
        } else {
            "FSM İHLAL (${counterexample!!.property}): ${counterexample.detail} | İz: ${counterexample.eventTrace.joinToString(" -> ")}"
        }
    }

    private data class Node(
        val initial: OrchestrationFsm.FsmState,
        val state: OrchestrationFsm.FsmState,
        val trace: List<OrchestrationFsm.FsmEvent>,
    )

    /**
     * Explores the entire reachable state space via BFS using [transitionFn] (injectable for negative tests).
     */
    fun verifyAll(
        initials: List<OrchestrationFsm.FsmState> = OrchestrationFsm.initialStates(),
        transitionFn: (OrchestrationFsm.FsmState, OrchestrationFsm.FsmEvent) -> OrchestrationFsm.FsmState = OrchestrationFsm::transition,
    ): CheckReport {
        val visited = LinkedHashSet<OrchestrationFsm.FsmState>()
        val queue = ArrayDeque<Node>()
        var transitionsChecked = 0

        for (init in initials) {
            checkStateProperties(init, init, emptyList(), null)?.let { ce ->
                return CheckReport(visited.size + 1, transitionsChecked, SafetyProperty.values().size - 1, counterexample = ce)
            }
            if (visited.add(init)) {
                queue.addLast(Node(init, init, emptyList()))
            }
        }

        val allEvents = OrchestrationFsm.FsmEvent.values()
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (ev in allEvents) {
                transitionsChecked++
                val next = transitionFn(cur.state, ev)
                val nextTrace = cur.trace + ev
                checkStateProperties(cur.initial, next, nextTrace, ev)?.let { ce ->
                    return CheckReport(visited.size, transitionsChecked, SafetyProperty.values().size - 1, counterexample = ce)
                }
                if (visited.add(next)) {
                    queue.addLast(Node(cur.initial, next, nextTrace))
                }
            }
        }

        return CheckReport(
            statesExplored = visited.size,
            transitionsChecked = transitionsChecked,
            propertiesPassed = SafetyProperty.values().size,
            counterexample = null,
        )
    }

    private fun checkStateProperties(
        initial: OrchestrationFsm.FsmState,
        s: OrchestrationFsm.FsmState,
        trace: List<OrchestrationFsm.FsmEvent>,
        lastEvent: OrchestrationFsm.FsmEvent?,
    ): Counterexample? {
        // 1) INV_NO_READONLY_DEADLOCK
        if (s.taskIntent == OrchestrationFsm.TaskIntent.WRITE &&
            (s.phase == OrchestrationFsm.Phase.EDITING || s.phase == OrchestrationFsm.Phase.HEALING) &&
            s.toolAccess == OrchestrationFsm.ToolAccess.READ_ONLY
        ) {
            return Counterexample(
                SafetyProperty.INV_NO_READONLY_DEADLOCK,
                initial,
                s,
                trace,
                "WRITE niyetli görev ${s.phase} fazında READ_ONLY yetkisinde kilitlendi.",
            )
        }
        // 2) INV_ARCHITECT_LOCK
        if (s.architectModelOverridden) {
            return Counterexample(
                SafetyProperty.INV_ARCHITECT_LOCK,
                initial,
                s,
                trace,
                "Kilitli Mimar modeli üzerine yazıldı.",
            )
        }
        // 3) INV_NO_EVOLUTION_AUTO_MERGE
        if (s.isEvolutionSession && s.mergeExecuted) {
            return Counterexample(
                SafetyProperty.INV_NO_EVOLUTION_AUTO_MERGE,
                initial,
                s,
                trace,
                "isEvolutionSession=true iken otomatik merge yürütüldü.",
            )
        }
        // 4) INV_NO_MERGE_ON_RED_CI
        if (s.mergeExecuted && s.ciState != OrchestrationFsm.CiState.PASSED) {
            return Counterexample(
                SafetyProperty.INV_NO_MERGE_ON_RED_CI,
                initial,
                s,
                trace,
                "CI durumu ${s.ciState} iken merge yürütüldü.",
            )
        }
        // 5) INV_TERMINATION_GUARD
        if ((lastEvent == OrchestrationFsm.FsmEvent.BUDGET_EXHAUSTED || lastEvent == OrchestrationFsm.FsmEvent.PROOF_VIOLATION) &&
            s.phase != OrchestrationFsm.Phase.HALTED_SAFE
        ) {
            return Counterexample(
                SafetyProperty.INV_TERMINATION_GUARD,
                initial,
                s,
                trace,
                "$lastEvent olayı sonrası sistem HALTED_SAFE durumuna geçmedi (${s.phase}).",
            )
        }
        return null
    }
}
