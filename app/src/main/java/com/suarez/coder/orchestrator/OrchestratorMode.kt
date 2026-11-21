package com.suarez.coder.orchestrator

/** Chosen by the user (Settings default, switchable per task in the control sheet); never changed automatically. */
enum class OrchestratorMode(val label: String, val short: String) {
    /** The usual SmartRouter → Agent / ArchitectEditor flow, with no extra calls. */
    OFF("Kapalı", "Kapalı"),
    /** The architect plans a task list; subtasks run one by one, each committed, with short hand-offs. */
    LIGHT_SEQUENTIAL("Hafif Sıralı", "Sıralı"),
    /** Planner → isolated workers (parallel when files do not overlap) → reviewer; one commit at the end. */
    FULL_MULTI_AGENT("Tam", "Tam");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: OFF
    }
}
