package com.suarez.coder.harness

import com.suarez.coder.diff.SearchReplace as DiffSR

typealias MatchTier = DiffSR.MatchTier
typealias SearchReplaceBlock = DiffSR.Block
typealias SearchReplaceParseResult = DiffSR.ParseResult
typealias SearchReplaceFailure = DiffSR.Failure
typealias SearchReplaceFileResult = DiffSR.FileResult
typealias SearchReplaceMatch = DiffSR.Match

/**
 * Harness facade for SearchReplace v2 engine, maintaining 100% interoperability
 * with com.suarez.coder.diff.SearchReplace.
 */
object SearchReplace {
    fun parse(text: String, defaultStartLine: Int? = null): DiffSR.ParseResult =
        DiffSR.parse(text, defaultStartLine)

    fun applyToFile(original: String?, blocks: List<DiffSR.Block>): DiffSR.FileResult =
        DiffSR.applyToFile(original, blocks)

    fun replaceOnce(content: String, search: String, replace: String, startLine: Int? = null): DiffSR.Match =
        DiffSR.replaceOnce(content, search, replace, startLine)

    fun countOccurrences(text: String, sub: String): Int =
        DiffSR.countOccurrences(text, sub)

    fun describeFailures(failures: List<DiffSR.Failure>): String =
        DiffSR.describeFailures(failures)

    fun lineDelta(before: String?, after: String?): Pair<Int, Int> =
        DiffSR.lineDelta(before, after)
}
