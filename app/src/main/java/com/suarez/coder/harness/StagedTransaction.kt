package com.suarez.coder.harness

import com.suarez.coder.diff.SearchReplace
import com.suarez.coder.tools.GitHub

class StagedTransaction {
    data class WriteItem(val path: String, val content: String?, val delete: Boolean = false)

    sealed class Outcome {
        data class DiffFailed(val message: String) : Outcome()
        data class PreFlightBlocked(val issues: List<PreFlightValidator.Issue>, val formattedReport: String) : Outcome()
        data class Ready(
            val staged: List<PreFlightValidator.StagedFile>,
            val tier2Count: Int,
            val tier3Count: Int,
            val addedLines: Int,
            val removedLines: Int
        ) : Outcome()
    }

    suspend fun stageDiff(
        diffText: String,
        defaultStartLine: Int? = null,
        loader: suspend (String) -> String?
    ): Outcome {
        val parseResult = SearchReplace.parse(diffText, defaultStartLine)
        if (parseResult.errors.isNotEmpty()) {
            return Outcome.DiffFailed("SEARCH/REPLACE ayrıştırma hatası:\n" + parseResult.errors.joinToString("\n"))
        }
        if (parseResult.blocks.isEmpty()) {
            return Outcome.DiffFailed("SEARCH/REPLACE bloğu bulunamadı.")
        }

        val grouped = parseResult.blocks.groupBy { it.path }
        val stagedList = ArrayList<PreFlightValidator.StagedFile>()
        var tier2 = 0
        var tier3 = 0
        var totalAdded = 0
        var totalRemoved = 0

        for ((path, blocks) in grouped) {
            val original = loader(path)
            val fileRes = SearchReplace.applyToFile(original, blocks)
            if (fileRes.failures.isNotEmpty()) {
                return Outcome.DiffFailed(SearchReplace.describeFailures(fileRes.failures))
            }

            for (tier in fileRes.tiersUsed.values) {
                if (tier == SearchReplace.MatchTier.WHITESPACE_NORMALIZED) tier2++
                if (tier == SearchReplace.MatchTier.LINE_ANCHOR) tier3++
            }
            val delta = SearchReplace.lineDelta(original, fileRes.content)
            totalAdded += delta.first
            totalRemoved += delta.second

            stagedList += PreFlightValidator.StagedFile(
                path = path,
                before = original,
                after = fileRes.content,
                explicitDelete = false
            )
        }

        val validation = PreFlightValidator.validate(stagedList)
        if (!validation.ok) {
            return Outcome.PreFlightBlocked(validation.issues, validation.report())
        }

        return Outcome.Ready(stagedList, tier2, tier3, totalAdded, totalRemoved)
    }

    suspend fun stageWrites(
        items: List<WriteItem>,
        loader: suspend (String) -> String?
    ): Outcome {
        val stagedList = ArrayList<PreFlightValidator.StagedFile>()
        var totalAdded = 0
        var totalRemoved = 0

        for (item in items) {
            val original = loader(item.path)
            val delta = SearchReplace.lineDelta(original, item.content)
            totalAdded += delta.first
            totalRemoved += delta.second
            stagedList += PreFlightValidator.StagedFile(
                path = item.path,
                before = original,
                after = item.content,
                explicitDelete = item.delete
            )
        }

        val validation = PreFlightValidator.validate(stagedList)
        if (!validation.ok) {
            return Outcome.PreFlightBlocked(validation.issues, validation.report())
        }

        return Outcome.Ready(stagedList, 0, 0, totalAdded, totalRemoved)
    }

    suspend fun commitAtomically(
        ready: Outcome.Ready,
        committer: suspend (List<GitHub.FileChange>) -> String
    ): String {
        val changes = ready.staged.map { sf ->
            GitHub.FileChange(
                path = sf.path,
                content = sf.after ?: "",
                delete = sf.explicitDelete || (sf.after == null)
            )
        }
        return committer(changes)
    }
}
