package com.suarez.coder.evolution

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tier 1 (and the input for tiers 2-3): one call to the evolution model reads a [SessionTrace] and returns a strict
 * JSON [EvolutionReport]. [ask] sends (system, user) to the isolated evolution model and returns its text.
 */
class ReflectorEngine(private val ask: suspend (system: String, user: String, maxOut: Int) -> String) {

    companion object {
        const val SYSTEM = "You are the post-session REFLECTOR of Coder, an Android coding agent. You read a structured trace of one " +
            "finished coding session and reply with ONE JSON object only (no prose, no markdown fences). Be conservative: most " +
            "sessions teach nothing; then return empty lists and null. Never invent facts that are not in the trace."

        fun prompt(t: SessionTrace, stats: String, notes: String, tiers: Set<EvoTier>, coderFiles: List<String>): String = buildString {
            append("SESSION TRACE\n").append(t.toJson().toString(2)).append("\n\n")
            if (stats.isNotBlank()) append("ROUTING STATISTICS (route|model)\n").append(stats).append("\n\n")
            if (notes.isNotBlank()) append("CURRENT LESSONS FOR THIS REPO\n").append(notes.take(4_000)).append("\n\n")
            append("Return exactly this JSON shape:\n")
            append("{\"lessons\": [\"...\"], \"routing\": {\"complexity_threshold\": null, \"local_hints\": [{\"pattern\": \"...\", \"route\": \"SIMPLE|QUESTION|COMPLEX\"}], ")
            append("\"escalation_notes\": [{\"task_type\": \"SIMPLE_EDIT\", \"model\": \"...\", \"note\": \"...\"}]}, ")
            append("\"self_improvement\": null}\n\nRules:\n")
            if (EvoTier.MEMORY in tiers) {
                append("- lessons: 0-3 short, concrete, reusable facts about the TARGET project that would have prevented an error in this ")
                append("trace (e.g. a build requirement, a file convention). Not generic advice. Do not repeat CURRENT LESSONS.\n")
            } else append("- lessons: always [].\n")
            if (EvoTier.ROUTING in tiers) {
                append("- routing: only when the statistics or the trace show a clear pattern. complexity_threshold must be between 1.0 and 2.2 ")
                append("or null. local_hints: short phrases (3-40 chars) from requests that should always take that route. escalation_notes: ")
                append("only for a cheap model that repeatedly fails one task type.\n")
            } else append("- routing: always {\"complexity_threshold\": null, \"local_hints\": [], \"escalation_notes\": []}.\n")
            if (EvoTier.SELF_PR in tiers) {
                append("- self_improvement: only if the trace proves a REPEATABLE bug in Coder's OWN code (not the target project), e.g. its ")
                append("SEARCH/REPLACE matcher, CI log filter, history pruning, argument parsing, routing or UI. Then return ")
                append("{\"title\": \"...\", \"problem\": \"...\", \"files\": [\"app/src/main/java/...\"], \"topic\": \"short-slug\", \"repeatable\": true}. ")
                append("Use only paths from this list:\n").append(coderFiles.take(150).joinToString("\n")).append('\n')
            } else append("- self_improvement: always null.\n")
        }

        /** The first balanced JSON object in [text] (models sometimes wrap it in prose or fences). */
        fun extractJson(text: String): JSONObject? {
            val start = text.indexOf('{')
            if (start < 0) return null
            var depth = 0; var inStr = false; var esc = false
            for (i in start until text.length) {
                val c = text[i]
                if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) return runCatching { JSONObject(text.substring(start, i + 1)) }.getOrNull() }
                }
            }
            return null
        }

        private fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.opt(it)?.toString()?.trim()?.ifBlank { null } }

        fun parse(text: String): EvolutionReport? {
            val o = extractJson(text) ?: return null
            val r = o.optJSONObject("routing") ?: JSONObject()
            val hints = r.optJSONArray("local_hints")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
                ?.map { LocalHint(it.optString("pattern"), it.optString("route")) }.orEmpty()
            val notes = r.optJSONArray("escalation_notes")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
                ?.map { EscalationNote(it.optString("task_type"), it.optString("model"), it.optString("note")) }.orEmpty()
            val si = o.optJSONObject("self_improvement")?.let { s ->
                SelfImprovement(s.optString("title").trim(), s.optString("problem").trim(), strings(s.optJSONArray("files")),
                    s.optString("topic").ifBlank { s.optString("title") }, s.optBoolean("repeatable"))
            }?.takeIf { it.title.isNotBlank() && it.files.isNotEmpty() && it.repeatable }
            return EvolutionReport(
                lessons = strings(o.optJSONArray("lessons")).map { it.removePrefix("-").trim() }.filter { it.length in 8..240 }
                    .take(EvolutionGuard.MAX_LESSONS_PER_RUN),
                threshold = if (r.has("complexity_threshold") && !r.isNull("complexity_threshold")) r.optDouble("complexity_threshold").takeIf { !it.isNaN() } else null,
                hints = hints,
                escalationNotes = notes,
                selfImprovement = si,
            )
        }

        const val LESSONS_HEADING = "## Öğrenilen dersler"

        /** Adds new lessons as bullets (case-insensitive duplicates skipped). */
        fun mergeLessons(existing: String, lessons: List<String>): String {
            val known = existing.lines().map { it.trim().removePrefix("- ").trim().lowercase() }.toSet()
            val add = lessons.filter { it.trim().lowercase() !in known }.distinctBy { it.lowercase() }
            if (add.isEmpty()) return existing
            val base = existing.ifBlank { LESSONS_HEADING }
            return base.trimEnd() + "\n" + add.joinToString("\n") { "- ${it.trim()}" }
        }
    }

    suspend fun reflect(t: SessionTrace, stats: String, notes: String, tiers: Set<EvoTier>, coderFiles: List<String>): EvolutionReport? =
        parse(ask(SYSTEM, prompt(t, stats, notes, tiers, coderFiles), 1_500))

    /** Above the limit the model merges duplicates and drops stale rules; a hard cut guarantees the limit. */
    suspend fun pruneIfNeeded(notes: String): String {
        if (notes.length <= EvolutionGuard.NOTES_LIMIT) return notes
        val merged = runCatching {
            ask("You compress a list of project lessons. Merge duplicates, drop outdated or generic ones, keep concrete rules. " +
                "Reply with the list only: the heading line, then one '- ' bullet per lesson.",
                "Keep it under ${EvolutionGuard.NOTES_LIMIT - 500} characters.\n\n$notes", 1_200).trim()
        }.getOrNull()?.takeIf { it.contains("- ") } ?: notes
        val withHeading = if (merged.startsWith("#")) merged else "$LESSONS_HEADING\n$merged"
        return EvolutionGuard.hardPrune(withHeading)
    }
}
