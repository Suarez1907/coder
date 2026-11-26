package com.suarez.coder.evolution

import com.suarez.coder.tools.GitHub
import org.json.JSONObject

/**
 * Merging and undoing self-PRs. Used by the user's buttons and, at autonomy level 3, by the engine itself;
 * every merge is remembered so a crash spike after the update can revert it.
 */
class SelfPrActions(private val gh: GitHub, private val store: EvolutionStore) {
    data class LastMerge(val number: Int, val title: String, val sha: String, val at: Long, val version: Long, val auto: Boolean,
                         val counted: Boolean = false, val reverted: Boolean = false) {
        fun toJson(): JSONObject = JSONObject().put("n", number).put("title", title).put("sha", sha).put("at", at).put("v", version)
            .put("auto", auto).put("counted", counted).put("reverted", reverted)

        companion object {
            fun fromJson(o: JSONObject) = LastMerge(o.optInt("n"), o.optString("title"), o.optString("sha"), o.optLong("at"), o.optLong("v"),
                o.optBoolean("auto"), o.optBoolean("counted"), o.optBoolean("reverted"))
        }
    }

    companion object {
        const val WATCH_MS = 72 * 3_600_000L
        const val CRASH_SPIKE = 2
        /** Merges that lived through the watch window without a crash spike, needed before auto-merging. */
        const val TRUST_FOR_AUTO_MERGE = 2

        /** Level-3 gates for merging without asking. */
        fun mayAutoMerge(autonomy: Int, pr: PendingSelfPr, trustedMerges: Int, watching: Boolean): Boolean =
            autonomy >= 3 && pr.ciPassed && pr.files.size <= 3 && pr.files.none { EvolutionGuard.isProtected(it) } &&
                trustedMerges >= TRUST_FOR_AUTO_MERGE && !watching

        /** Crashes after the update that contains the merge, inside the watch window. */
        fun isSpike(m: LastMerge, crashes: List<Pair<Long, Long>>): Boolean =
            !m.reverted && crashes.count { (at, version) -> at >= m.at && at <= m.at + WATCH_MS && version > m.version } >= CRASH_SPIKE
    }

    fun lastMerge(): LastMerge? = runCatching { LastMerge.fromJson(JSONObject(java.io.File(store.dir, "last_merge.json").readText())) }.getOrNull()
    fun saveLastMerge(m: LastMerge) = java.io.File(store.dir, "last_merge.json").writeText(m.toJson().toString())

    /** Squash-merges; (ok, message). 405/409 keep the branch and mark a conflict. */
    suspend fun merge(p: PendingSelfPr, appVersion: Long, auto: Boolean): Pair<Boolean, String> {
        val (code, msg) = gh.tryMerge(EvolutionGuard.SELF_REPO, p.number, "squash")
        if (code == 405 || code == 409) {
            store.savePending(p.copy(state = PrState.CONFLICT))
            return false to "Merge yapılamadı (çakışma, HTTP $code): $msg. Dal korunuyor."
        }
        if (code !in 200..299) return false to "Merge hatası (HTTP $code): $msg"
        runCatching { gh.deleteBranch(EvolutionGuard.SELF_REPO, p.branch) }
        store.savePending(p.copy(state = PrState.MERGED))
        store.bump("merged")
        store.reject(p.topic) // a merged topic is done
        saveLastMerge(LastMerge(p.number, p.title, msg, System.currentTimeMillis(), appVersion, auto))
        store.addLog(EvoTier.SELF_PR, (if (auto) "Otomatik merge (seviye 3): " else "PR merge edildi: ") + "#${p.number} ${p.title}")
        return true to msg
    }

    /** Undoes a merged commit with a new PR that restores the parent's version of every changed file, then merges it. */
    suspend fun revert(m: LastMerge, reason: String): String {
        val repo = EvolutionGuard.SELF_REPO
        val info = gh.commitInfo(repo, m.sha)
        val parent = info.parents.firstOrNull() ?: return "Geri alınamadı: üst commit yok."
        val changes = info.files.map { (path, status) ->
            val before = if (status == "added") null else gh.readFileRaw(repo, path, parent)
            GitHub.FileChange(path, before, before == null)
        }
        val branch = "${EvolutionGuard.BRANCH_PREFIX}revert-${m.number}-${System.currentTimeMillis() % 100_000}"
        val base = gh.defaultBranch(repo)
        gh.commitFiles(repo, branch, "evo: #${m.number} geri alındı ($reason)", changes, base)
        val res = gh.createPullRequest(repo, "evo: #${m.number} geri al", branch, base, "Otomatik geri alma: $reason\n\nGeri alınan: #${m.number} ${m.title}")
        val number = Regex("#(\\d+)").find(res)?.groupValues?.get(1)?.toIntOrNull() ?: return "Geri alma PR'ı açılamadı: $res"
        val (code, msg) = gh.tryMerge(repo, number, "squash")
        runCatching { gh.deleteBranch(repo, branch) }
        saveLastMerge(m.copy(reverted = true))
        val text = if (code in 200..299) "#${m.number} geri alındı (PR #$number): $reason" else "Geri alma PR'ı #$number açıldı ama merge edilemedi: $msg"
        store.addLog(EvoTier.SELF_PR, text)
        return text
    }
}
