package com.suarez.coder.agent

import com.suarez.coder.cloudtools.CloudToolRunner
import com.suarez.coder.cloudtools.CloudWorkflowTemplates
import com.suarez.coder.tools.GitHub
import org.json.JSONObject

/**
 * Compile/type-check a change before it is committed: the files go to a throwaway branch with "[skip ci]" (so the
 * repo's own CI does not start), the quick workflow runs there, the branch is deleted. Only errors in the changed files
 * block the commit: problems the repo already had are reported but do not stop the work.
 */
class QuickCheck(private val gh: GitHub, private val runner: CloudToolRunner, private val ciErrors: suspend (String, Long) -> String) {
    data class Result(val ok: Boolean, val report: String)

    companion object {
        const val MAX_WAIT_MS = 15 * 60_000L

        /** Error lines that point at one of the changed files (paths in logs are absolute or module-relative). */
        fun relevant(errors: String, paths: List<String>): List<String> {
            val keys = paths.map { it.trim('/') }.filter { it.isNotBlank() }
            return errors.lines().filter { l -> keys.any { p -> l.contains(p) || (p.contains('/') && l.contains("/" + p.substringAfterLast('/') + ":")) } }
                .map { it.trim().take(300) }.distinct().take(30)
        }
    }

    suspend fun check(repo: String, branch: String, changes: List<Triple<String, String?, String?>>,
                      inputs: JSONObject = JSONObject(), tests: List<String> = emptyList()): Result {
        val source = gh.branchSha(repo, branch)?.let { branch } ?: gh.defaultBranch(repo)
        val tmp = "coder-qc-${System.currentTimeMillis() % 10_000_000}"
        val t = CloudWorkflowTemplates.QUICK
        val files = changes.map { (p, _, after) -> GitHub.FileChange(p, after, after == null) } + GitHub.FileChange(t.path, t.yaml, false)
        try {
            gh.commitFiles(repo, tmp, "[skip ci] coder: hızlı ön kontrol", files.distinctBy { it.path }, source)
            val run = runner.dispatchAndWait(repo, t, tmp, inputs, MAX_WAIT_MS)
                ?: return Result(true, "Hızlı ön kontrol zamanında bitmedi; commit'e devam edildi.")
            if (run.conclusion == "success") return Result(true, "Hızlı ön kontrol geçti (${run.url}).")
            val errors = ciErrors(repo, run.id)
            // A failing selected test is relevant: it exercises the changed code.
            val mine = relevant(errors, changes.map { it.first } + tests.map { it.substringAfterLast('/').substringBeforeLast('.') })
            if (mine.isEmpty()) return Result(true, "Hızlı ön kontrol başarısız ama hatalar değişen dosyalarda değil (repoda önceden var olabilir): ${run.url}\n" +
                errors.lines().filter { it.isNotBlank() }.take(8).joinToString("\n"))
            return Result(false, "Hızlı ön kontrol (derleme/tür denetimi) değişen dosyalarda hata buldu, commit YAPILMADI:\n" +
                mine.joinToString("\n") + "\n\nTam çıktı: ${run.url}")
        } finally {
            runCatching { gh.deleteBranch(repo, tmp) }
        }
    }
}
