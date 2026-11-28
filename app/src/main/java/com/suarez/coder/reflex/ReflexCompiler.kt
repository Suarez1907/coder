package com.suarez.coder.reflex

import com.suarez.coder.harness.PreFlightValidator
import org.json.JSONArray
import org.json.JSONObject

/**
 * Faz 7A — Bildirimsel Refleks Derleyici (`ReflexCompiler`).
 *
 * Android çalışma zamanında asla güvensiz dinamik kod (`eval` / runtime `kotlinc`) çalıştırmaz.
 * Tekrarlayan akışları güvenli, kum havuzlu bir **Declarative Reflex IR ([ReflexProgram])** yapısına
 * derler ve geçmiş 3 örnek üzerinde `$0` çevrimdışı dry-run ile doğruladıktan sonra `status = DRAFT`
 * olarak kaydeder (kullanıcı onaylamadan aktifleşmez).
 */
object ReflexCompiler {

    enum class Status { DRAFT, ACTIVE, DISABLED }

    enum class OpKind {
        CODE_MOD_RENAME,
        INSERT_AFTER_ANCHOR,
        PATCH_REGEX_UNIQUE,
        CREATE_FROM_TEMPLATE,
        UPDATE_VERSION_FIELD,
    }

    data class ReflexOp(
        val kind: OpKind,
        val targetPath: String,
        val anchorOrPattern: String,
        val payloadTemplate: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("kind", kind.name)
            .put("targetPath", targetPath)
            .put("anchorOrPattern", anchorOrPattern)
            .put("payloadTemplate", payloadTemplate)

        companion object {
            fun fromJson(o: JSONObject): ReflexOp = ReflexOp(
                kind = runCatching { OpKind.valueOf(o.optString("kind")) }.getOrDefault(OpKind.INSERT_AFTER_ANCHOR),
                targetPath = o.optString("targetPath"),
                anchorOrPattern = o.optString("anchorOrPattern"),
                payloadTemplate = o.optString("payloadTemplate"),
            )
        }
    }

    data class ReflexProgram(
        val id: String,
        val name: String,
        val intentMatchers: List<String>,
        val requiredParams: List<String>,
        val operations: List<ReflexOp>,
        val status: Status = Status.DRAFT,
        val hits: Int = 0,
        val estimatedCostSavedPerRunUsd: Double = 0.015,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("intentMatchers", JSONArray(intentMatchers))
            .put("requiredParams", JSONArray(requiredParams))
            .put("operations", JSONArray().also { a -> operations.forEach { a.put(it.toJson()) } })
            .put("status", status.name)
            .put("hits", hits)
            .put("estimatedCostSavedPerRunUsd", estimatedCostSavedPerRunUsd)

        companion object {
            fun fromJson(o: JSONObject): ReflexProgram {
                val matchers = o.optJSONArray("intentMatchers")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                val params = o.optJSONArray("requiredParams")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                val ops = o.optJSONArray("operations")?.let { a -> (0 until a.length()).map { ReflexOp.fromJson(a.getJSONObject(it)) } } ?: emptyList()
                return ReflexProgram(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    intentMatchers = matchers,
                    requiredParams = params,
                    operations = ops,
                    status = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.DRAFT),
                    hits = o.optInt("hits", 0),
                    estimatedCostSavedPerRunUsd = o.optDouble("estimatedCostSavedPerRunUsd", 0.015),
                )
            }
        }
    }

    data class ReplayExample(
        val request: String,
        val filesBefore: Map<String, String>,
    )

    data class CompileResult(
        val accepted: Boolean,
        val program: ReflexProgram?,
        val passedExamples: Int,
        val totalExamples: Int,
        val reason: String,
    )

    /**
     * Validates a candidate [ReflexProgram] by dry-running it offline ($0) across all [examples].
     * Only if 100% of examples produce valid, preflight-clean diffs does it pass and become `DRAFT`.
     */
    fun compileAndVerify(candidate: ReflexProgram, examples: List<ReplayExample>): CompileResult {
        if (examples.size < TracePatternMiner.MIN_GREEN_OCCURRENCES) {
            return CompileResult(false, null, 0, examples.size, "En az ${TracePatternMiner.MIN_GREEN_OCCURRENCES} geçmiş örnek gerekir.")
        }
        val testActive = candidate.copy(status = Status.ACTIVE)
        var passed = 0
        for (ex in examples) {
            val res = ReflexExecutor.tryExecute(ex.request, listOf(testActive), ex.filesBefore)
            if (!res.matched || !res.succeeded || res.staged.edits().isEmpty()) {
                return CompileResult(false, null, passed, examples.size, "Örnek dry-run başarısız: ${res.reason}")
            }
            val findings = res.staged.edits().flatMap { e -> PreFlightValidator.check(e.path, e.before, e.after) }
            if (findings.isNotEmpty()) {
                return CompileResult(false, null, passed, examples.size, "PreFlightValidator dry-run reddi: ${findings.first().detail}")
            }
            passed++
        }
        val draft = candidate.copy(status = Status.DRAFT)
        return CompileResult(true, draft, passed, examples.size, "Dry-run %100 geçti ($passed/${examples.size}); DRAFT olarak onay bekliyor.")
    }
}
