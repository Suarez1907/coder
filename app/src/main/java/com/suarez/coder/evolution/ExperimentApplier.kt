package com.suarez.coder.evolution

import com.suarez.coder.data.Settings
import org.json.JSONObject

/**
 * Applies adopted experiments to the live settings and can undo them.
 * The architect (planner) model is the user's choice and is never touched — not by apply, not by undo.
 */
class ExperimentApplier(private val settings: Settings) {

    /** Returns true when the experiment had a persistent effect. */
    fun apply(e: Experiment): Boolean = when {
        e.isArchitectExperiment() -> false // kilit: mimar modeli değişmez
        e.kind == Experiment.Kind.MODEL && e.payload.optString("role") == "editor" -> {
            settings.editorModel = e.payload.optString("model")
            true
        }
        else -> false // RULE/SKILL/PROMPT kalıcı ayarla eşleşmez; henüz etkisiz
    }

    /** [json] is the undo record of a past apply. Returns true when it was safe to process. */
    fun undo(json: String): Boolean {
        val o = JSONObject(json)
        val kind = o.optString("kind")
        val role = o.optString("role")
        if (kind == "MODEL" && role == "architect") return false // kilit: undo bile mimara dokunamaz
        if (kind == "MODEL" && role == "editor") settings.editorModel = o.optString("old")
        return true
    }
}
