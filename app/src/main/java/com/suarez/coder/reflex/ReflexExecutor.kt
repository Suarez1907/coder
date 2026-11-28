package com.suarez.coder.reflex

import com.suarez.coder.harness.PreFlightValidator
import com.suarez.coder.harness.StagedTransaction
import com.suarez.coder.proof.InvariantMiner
import com.suarez.coder.proof.ProofCarryingPatchGate

/**
 * Faz 7A — Sıfır-Maliyetli Refleks Yürütücü (`ReflexExecutor`).
 *
 * Kullanıcı yeni bir görev gönderdiğinde ana LLM çağrısından **önce** aktif refleks kataloğunu `<5 ms`
 * içinde tarar. Kesin eşleşme + tüm parametreler çıkarıldığında LLM hiç çağrılmadan `StagedTransaction`
 * üzerinde dönüşümü uygular ve `PreFlightValidator` + `ProofCarryingPatchGate` ile doğrular.
 * Herhangi bir önkoşul veya doğrulama uymazsa sıfır hasarla geri alır (sessiz LLM geri dönüşü).
 */
object ReflexExecutor {

    data class ExecutionResult(
        val matched: Boolean,
        val succeeded: Boolean,
        val reflexId: String?,
        val reflexName: String?,
        val staged: StagedTransaction,
        val elapsedMs: Long,
        val costUsd: Double = 0.0,
        val estimatedSavedUsd: Double = 0.0,
        val reason: String,
    ) {
        /** Compact monochrome chat status line: `Refleks çalıştı: <ad> · 4 ms · $0.0000` */
        fun statusLine(): String =
            "Refleks çalıştı: ${reflexName ?: reflexId.orEmpty()} · $elapsedMs ms · \$0.0000"
    }

    fun tryExecute(
        request: String,
        catalog: List<ReflexCompiler.ReflexProgram>,
        files: Map<String, String>,
        invariants: List<InvariantMiner.Invariant> = emptyList(),
    ): ExecutionResult {
        val t0 = System.nanoTime()
        val active = catalog.filter { it.status == ReflexCompiler.Status.ACTIVE }
        for (prog in active) {
            val params = matchAndExtractParams(request, prog) ?: continue
            val tx = StagedTransaction()
            val working = HashMap(files)
            var ok = true
            var failReason = ""

            for (op in prog.operations) {
                val path = substitute(op.targetPath, params)
                val anchor = substitute(op.anchorOrPattern, params)
                val payload = substitute(op.payloadTemplate, params)
                val before = working[path]
                val after = applyOp(op.kind, before, anchor, payload)
                if (after == null || after == before) {
                    ok = false
                    failReason = "İşlem önkoşulu sağlanmadı: ${op.kind} @ $path"
                    break
                }
                working[path] = after
                tx.stage(path, files[path], after)
            }

            if (ok) {
                val preflight = tx.edits().flatMap { e -> PreFlightValidator.check(e.path, e.before, e.after) }
                if (preflight.isNotEmpty()) {
                    ok = false
                    failReason = "PreFlightValidator reddi: ${preflight.first().detail}"
                }
            }
            if (ok && invariants.isNotEmpty()) {
                val proof = ProofCarryingPatchGate.verify(tx, invariants)
                if (proof.blocked) {
                    ok = false
                    failReason = proof.blockMessage()
                }
            }

            val elapsed = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            if (ok) {
                return ExecutionResult(
                    matched = true,
                    succeeded = true,
                    reflexId = prog.id,
                    reflexName = prog.name,
                    staged = tx,
                    elapsedMs = elapsed,
                    costUsd = 0.0,
                    estimatedSavedUsd = prog.estimatedCostSavedPerRunUsd,
                    reason = "Başarılı",
                )
            } else {
                // Zero-damage rollback
                tx.clear()
                return ExecutionResult(
                    matched = true,
                    succeeded = false,
                    reflexId = prog.id,
                    reflexName = prog.name,
                    staged = StagedTransaction(),
                    elapsedMs = elapsed,
                    costUsd = 0.0,
                    estimatedSavedUsd = 0.0,
                    reason = failReason,
                )
            }
        }
        val elapsed = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(0L)
        return ExecutionResult(false, false, null, null, StagedTransaction(), elapsed, 0.0, 0.0, "Eşleşen aktif refleks yok.")
    }

    private fun matchAndExtractParams(request: String, prog: ReflexCompiler.ReflexProgram): Map<String, String>? {
        val params = LinkedHashMap<String, String>()
        var matchedAny = false
        for (rawPattern in prog.intentMatchers) {
            val re = runCatching { Regex(rawPattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: continue
            val m = re.find(request) ?: continue
            matchedAny = true
            // Support named or positional extraction aligned with requiredParams
            prog.requiredParams.forEachIndexed { idx, name ->
                val byName = runCatching { m.groups[name]?.value }.getOrNull()
                val byPos = m.groupValues.getOrNull(idx + 1)?.takeIf { it.isNotBlank() }
                val value = byName ?: byPos
                if (!value.isNullOrBlank()) params[name] = value
            }
        }
        if (!matchedAny) return null
        if (prog.requiredParams.any { it !in params }) return null
        return params
    }

    private fun substitute(template: String, params: Map<String, String>): String {
        var out = template
        for ((k, v) in params) {
            out = out.replace("{{$k}}", v)
        }
        return out
    }

    private fun applyOp(kind: ReflexCompiler.OpKind, before: String?, anchor: String, payload: String): String? = when (kind) {
        ReflexCompiler.OpKind.CREATE_FROM_TEMPLATE -> if (before == null) payload else null
        ReflexCompiler.OpKind.INSERT_AFTER_ANCHOR -> {
            if (before == null || anchor.isBlank()) null
            else {
                val idx = before.indexOf(anchor)
                if (idx < 0 || before.indexOf(anchor, idx + 1) >= 0) null
                else before.substring(0, idx + anchor.length) + "\n" + payload + before.substring(idx + anchor.length)
            }
        }
        ReflexCompiler.OpKind.CODE_MOD_RENAME -> {
            if (before == null || anchor.isBlank() || !before.contains(anchor)) null
            else before.replace(Regex("""\b${Regex.escape(anchor)}\b"""), payload)
        }
        ReflexCompiler.OpKind.PATCH_REGEX_UNIQUE -> {
            if (before == null || anchor.isBlank()) null
            else {
                val re = runCatching { Regex(anchor) }.getOrNull() ?: return null
                val matches = re.findAll(before).toList()
                if (matches.size != 1) null else re.replaceFirst(before, Regex.escapeReplacement(payload))
            }
        }
        ReflexCompiler.OpKind.UPDATE_VERSION_FIELD -> {
            if (before == null || anchor.isBlank()) null
            else {
                val re = Regex("""(${Regex.escape(anchor)}\s*=\s*["']?)([^"'\n]+)(["']?)""")
                if (!re.containsMatchIn(before)) null
                else re.find(before)?.let { m ->
                    before.substring(0, m.range.first) + m.groupValues[1] + payload + m.groupValues[3] +
                        before.substring(m.range.last + 1)
                }
            }
        }
    }
}
