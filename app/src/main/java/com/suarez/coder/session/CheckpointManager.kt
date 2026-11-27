package com.suarez.coder.session

import com.suarez.coder.orchestrator.OrchestratorMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One checkpoint at the start of each user turn. Rewind restores the chat index.
 * Git movement is a new branch or a revert commit. It never force-pushes, and it never moves main.
 */
data class TurnCheckpoint(
    val checkpointId: String,
    val turnIndex: Int,
    val repo: String,
    val branch: String,
    val commitSha: String?,
    val messageCount: Int,
    val cumulativeCostUsd: Double,
    val timestamp: Long,
)

data class GitStep(
    val action: String,
    val repo: String,
    val branch: String,
    val sha: String?,
    val force: Boolean = false,
) {
    val forcePushesMain: Boolean get() = force && branch in CheckpointManager.PROTECTED
}

data class RewindResult(val checkpoint: TurnCheckpoint, val messageCount: Int, val git: GitStep)

data class ForkResult(
    val checkpoint: TurnCheckpoint,
    val sessionId: String,
    val branch: String,
    val messageCount: Int,
    val force: Boolean = false,
)

class CheckpointManager(private val file: File) {
    fun load(): List<TurnCheckpoint> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { i -> decode(a.getJSONObject(i)) }
    }.getOrDefault(emptyList())

    fun record(
        sessionId: String,
        turnIndex: Int,
        repo: String,
        branch: String,
        commitSha: String?,
        messageCount: Int,
        cumulativeCostUsd: Double,
        timestamp: Long,
    ): TurnCheckpoint {
        val id = "$sessionId-$turnIndex-$timestamp"
        val point = TurnCheckpoint(id, turnIndex, repo, branch, commitSha, messageCount, cumulativeCostUsd, timestamp)
        val next = load().filterNot { it.checkpointId == id } + point
        save(next)
        return point
    }

    fun rewind(checkpointId: String, now: Long = System.currentTimeMillis()): RewindResult {
        val cp = load().first { it.checkpointId == checkpointId }
        return RewindResult(cp, cp.messageCount, gitPlan(cp, "coder/rewind-$now"))
    }

    fun fork(checkpointId: String, now: Long = System.currentTimeMillis()): ForkResult {
        val cp = load().first { it.checkpointId == checkpointId }
        val branch = "coder/fork-$now"
        return ForkResult(cp, "fork-$now", branch, cp.messageCount, false)
    }

    /** Chat prefix kept by a rewind or a fork. */
    fun <T> takeMessages(messages: List<T>, messageCount: Int): List<T> = messages.take(messageCount.coerceAtLeast(0))

    private fun gitPlan(cp: TurnCheckpoint, branchName: String): GitStep {
        val sha = cp.commitSha
        if (sha.isNullOrBlank()) return GitStep("none", cp.repo, cp.branch, null, false)
        val branch = if (cp.branch.lowercase() in CheckpointManager.PROTECTED) branchName else branchName
        return GitStep("create_branch", cp.repo, branch, sha, false)
    }

    private fun save(list: List<TurnCheckpoint>) {
        file.parentFile?.mkdirs()
        val a = JSONArray()
        list.forEach { a.put(encode(it)) }
        file.writeText(a.toString())
    }

    private fun encode(c: TurnCheckpoint) = JSONObject()
        .put("id", c.checkpointId).put("turn", c.turnIndex).put("repo", c.repo).put("branch", c.branch)
        .put("sha", c.commitSha ?: "").put("messages", c.messageCount).put("cost", c.cumulativeCostUsd).put("at", c.timestamp)

    private fun decode(o: JSONObject) = TurnCheckpoint(
        o.getString("id"), o.optInt("turn"), o.optString("repo"), o.optString("branch"),
        o.optString("sha").ifBlank { null }, o.optInt("messages"), o.optDouble("cost"), o.optLong("at"),
    )

    companion object {
        val PROTECTED = setOf("main", "master")
    }
}

data class SpecItem(val id: String, val title: String, val checked: Boolean = true)

data class SpecCardModel(
    val items: List<SpecItem>,
    val files: List<String>,
    val costLowUsd: Double,
    val costHighUsd: Double,
    val risk: String,
    val architectModelId: String,
    val note: String = "",
) {
    fun summary(): String = buildString {
        append("Risk ").append(risk).append(" · ")
        append(files.size).append(" dosya · ")
        append("$").append("%.2f".format(costLowUsd)).append("–$").append("%.2f".format(costHighUsd)).append('\n')
        items.filter { it.checked }.forEach { append("- ").append(it.title).append('\n') }
        if (note.isNotBlank()) append("Not: ").append(note)
    }
}

/**
 * Holds a complex task until the user accepts the spec card. Small tasks pass through.
 * Drafting echoes the locked architect id and never replaces it.
 */
object SpecApprovalGate {
    fun needsApproval(enabled: Boolean, text: String, mode: OrchestratorMode): Boolean {
        if (!enabled) return false
        val files = explicitFiles(text)
        val multi = files.size >= 3 || multiFileLanguage(text)
        val dag = mode == OrchestratorMode.CONTRACT_DAG && (multi || text.length > 180)
        if (files.size in 1..2 && !multi && !dag) return false
        if (!multi && !dag) return false
        return true
    }

    fun explicitFiles(text: String): List<String> =
        Regex("""[\w./-]+\.(?:kt|kts|java|xml|ts|tsx|py|gd|json)""").findAll(text).map { it.value }.distinct().toList()

    private fun multiFileLanguage(text: String): Boolean {
        val t = text.lowercase()
        return listOf("tüm dosya", "birden fazla dosya", "bütün dosya", "her dosya", "çok dosya").any { it in t }
    }

    fun riskOf(files: Int, needsDag: Boolean, text: String): String = when {
        needsDag || files >= 8 || text.contains("auth", true) || text.contains("ödeme") || text.contains("güvenlik") -> "Yüksek"
        files >= 3 -> "Orta"
        else -> "Düşük"
    }

    /** Deterministic card. [architectModelId] is stored and not rewritten by [lock]. */
    fun draft(text: String, architectModelId: String, graphFiles: List<String> = emptyList()): SpecCardModel {
        val named = (explicitFiles(text) + graphFiles).distinct()
        val files = named.ifEmpty { listOf("(görev metninden çıkarılacak)") }
        val dag = text.length > 180
        val risk = riskOf(files.size, dag, text)
        val sentences = text.split(Regex("[\\n.]+")).map { it.trim() }.filter { it.length > 8 }.take(6)
        val items = (sentences.ifEmpty { listOf(text.trim().take(160)) }).mapIndexed { i, s -> SpecItem("i$i", s.take(180), true) }
        val low = 0.02 + files.size * 0.01
        val high = low * 4
        return SpecCardModel(items, files, low, high, risk, architectModelId)
    }

    /** Ignores [modelId]. The card keeps the architect it was created with. */
    fun lock(card: SpecCardModel, @Suppress("UNUSED_PARAMETER") modelId: String): SpecCardModel = card

    fun approve(card: SpecCardModel, disabledIds: Set<String>, note: String): SpecCardModel =
        card.copy(items = card.items.map { if (it.id in disabledIds) it.copy(checked = false) else it }, note = note.trim())

    fun parse(json: String, architectModelId: String): SpecCardModel? = runCatching {
        val o = JSONObject(json)
        val items = o.optJSONArray("items")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val it = a.optJSONObject(i) ?: return@mapNotNull null
                SpecItem("i$i", it.optString("title").ifBlank { return@mapNotNull null }, it.optBoolean("checked", true))
            }
        }.orEmpty()
        val files = o.optJSONArray("files")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        if (items.isEmpty()) return null
        SpecCardModel(
            items, files, o.optDouble("costLow", 0.02), o.optDouble("costHigh", 0.1),
            o.optString("risk").ifBlank { "Orta" }, architectModelId, o.optString("note"),
        )
    }.getOrNull()
}
