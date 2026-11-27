package com.suarez.coder.orchestrator

import com.suarez.coder.evolution.TaskDomain
import com.suarez.coder.harness.StagedTransaction
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger

/**
 * Contract-first DAG. The architect model id is fixed for the whole run: strategy experiments may change only the
 * implementer hint, never this id. Subtasks share one in-memory [StagedTransaction]. A commit is allowed only when
 * every node finished and validation is clean.
 */
class ContractDagOrchestrator(
    val architectModelId: String,
    maxConcurrency: Int = 2,
    private val implementerFor: (DagNode) -> String = { "implementer" },
) {
    /** Set by the runner. Null means the cache has not been measured and steps keep their normal pace. */
    var cacheTracker: com.suarez.coder.cost.CacheTtlTracker? = null
    var cacheProvider: String = "gemini"
    val maxConcurrency: Int = maxConcurrency.coerceIn(1, 3)

    /** Refuses every attempt to point the run at another architect. */
    fun retarget(@Suppress("UNUSED_PARAMETER") modelId: String): Boolean = false

    fun lock(nodes: List<DagNode>): TaskContract {
        val problems = ArrayList<String>()
        val seen = HashMap<String, String>()
        for (n in nodes) {
            if (n.ownedFiles.isEmpty()) problems += "${n.id}: ownedFiles boş"
            for (f in n.ownedFiles) {
                val prev = seen.put(f, n.id)
                if (prev != null) problems += "$f: $prev ve ${n.id} çakışıyor"
            }
        }
        cycle(nodes)?.let { problems += "döngü: $it" }
        val signatures = derive(nodes, SIGNATURE)
        val models = derive(nodes, MODEL)
        val tests = nodes.flatMap { n ->
            n.acceptanceChecks.filter { it.contains("test", ignoreCase = true) } +
                n.ownedFiles.filter { isTestPath(it) }
        }.distinct()
        if (signatures.isEmpty()) problems += "paylaşılan imza yok"
        if (tests.isEmpty()) problems += "test beklentisi yok"
        val impl = nodes.associate { n ->
            val picked = implementerFor(n)
            n.id to if (picked.equals(architectModelId, true)) "implementer" else picked
        }
        return TaskContract(
            architectModelId = architectModelId,
            signatures = signatures,
            models = models,
            ownership = nodes.associate { it.id to it.ownedFiles },
            acceptance = nodes.flatMap { it.acceptanceChecks },
            testExpectations = tests,
            implementers = impl,
            conflict = problems.isNotEmpty(),
            problems = problems,
        )
    }

    private fun derive(nodes: List<DagNode>, kind: Regex): List<String> =
        nodes.flatMap { n -> (n.acceptanceChecks + listOf(n.instructions, n.title)).flatMap { line -> kind.findAll(line).map { it.value.trim() } } }
            .distinct()

    private fun isTestPath(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return "/test/" in path || name.endsWith("Test.kt") || name.endsWith("Test.java") || name.startsWith("test_")
    }

    suspend fun execute(
        nodes: List<DagNode>,
        validate: (StagedTransaction) -> List<String> = { emptyList() },
        worker: suspend (DagNode, TaskContract) -> NodeResult,
    ): DagRun {
        val contract = lock(nodes)
        val pace = com.suarez.coder.cost.FinOpsArbitrageEngine.pace(cacheTracker, cacheProvider)
        var sequential = contract.conflict
        var fellBack = contract.conflict
        val tx = StagedTransaction()
        val waveLog = ArrayList<List<String>>()
        val errors = ArrayList<String>()
        val failures = HashMap<String, Int>()
        val flight = AtomicInteger(0)
        val maxFlight = AtomicInteger(0)
        var pending = nodes.map { it.id }.toMutableSet()
        val done = HashSet<String>()
        var guard = 0
        while (pending.isNotEmpty() && errors.none { it.startsWith("durdu") } && guard++ < nodes.size * 6 + 2) {
            val ready = nodes.filter { n -> n.id in pending && n.dependsOn.all { it in done } }
            if (ready.isEmpty()) {
                errors += "durdu: bağımlılık"
                break
            }
            val wave = if (sequential) listOf(ready.first()) else selectWave(ready)
            waveLog += wave.map { it.id }
            // A warm KV cache is not asked to wait. A cold cache prefers compaction over a pause.
            if (!pace.minimizeWaits && pace.interStepDelayMs > 0) {
                kotlinx.coroutines.delay(pace.interStepDelayMs)
            }
            val results = coroutineScope {
                wave.map { node ->
                    async {
                        val now = flight.incrementAndGet()
                        maxFlight.updateAndGet { m -> maxOf(m, now) }
                        try {
                            node.id to worker(node, contract)
                        } finally {
                            flight.decrementAndGet()
                        }
                    }
                }.awaitAll()
            }
            var needFallback = false
            for ((id, result) in results) {
                when (result) {
                    is NodeResult.Ok -> {
                        result.edits.forEach { tx.stage(it.path, it.before, it.after) }
                        done += id
                        pending.remove(id)
                    }
                    is NodeResult.Fail -> {
                        val n = (failures[id] ?: 0) + 1
                        failures[id] = n
                        errors += "$id: ${result.message}"
                        if (sequential) {
                            errors += "durdu: sıralı deneme başarısız"
                            pending.clear()
                            tx.clear()
                        } else if (result.conflict || n >= 2) {
                            needFallback = true
                        }
                    }
                }
            }
            if (needFallback && !sequential) {
                sequential = true
                fellBack = true
                tx.clear()
                done.clear()
                pending = nodes.map { it.id }.toMutableSet()
                failures.clear()
                errors += "sıralı moda düşüldü"
            }
        }
        val validation = if (pending.isEmpty()) validate(tx) else listOf("tamamlanmadı")
        if (validation.isNotEmpty()) errors += validation
        val committed = pending.isEmpty() && validation.isEmpty() && errors.none { it.startsWith("durdu") }
        if (!committed) tx.clear()
        return DagRun(
            architectModelId = architectModelId,
            contract = contract,
            mode = if (sequential || fellBack) "sequential" else "parallel",
            fellBack = fellBack,
            committed = committed,
            waves = waveLog,
            maxInFlight = maxFlight.get(),
            errors = errors,
            staged = tx,
            preferCompact = pace.preferCompactAndTurn0,
        )
    }

    private fun selectWave(ready: List<DagNode>): List<DagNode> {
        val claimed = HashSet<String>()
        val wave = ArrayList<DagNode>()
        for (n in ready) {
            if (wave.size >= maxConcurrency) break
            if (n.ownedFiles.any { it in claimed }) continue
            wave += n
            claimed += n.ownedFiles
        }
        return wave.ifEmpty { listOf(ready.first()) }
    }

    companion object {
        private val SIGNATURE = Regex("""(?:fun|def|func|class|interface|object)\s+[A-Za-z_][A-Za-z0-9_]*""")
        private val MODEL = Regex("""(?:data\s+class|class|interface|object)\s+[A-Za-z_][A-Za-z0-9_]*""")
    }

    private fun cycle(nodes: List<DagNode>): String? {
        val byId = nodes.associateBy { it.id }
        val state = HashMap<String, Int>()
        fun visit(id: String, path: List<String>): String? {
            when (state[id]) { 1 -> return (path + id).joinToString(" → "); 2 -> return null }
            state[id] = 1
            for (d in byId[id]?.dependsOn.orEmpty()) if (d in byId) visit(d, path + id)?.let { return it }
            state[id] = 2
            return null
        }
        for (n in nodes) visit(n.id, emptyList())?.let { return it }
        return null
    }
}

data class DagNode(
    val id: String,
    val dependsOn: List<String> = emptyList(),
    val ownedFiles: List<String> = emptyList(),
    val acceptanceChecks: List<String> = emptyList(),
    val title: String = "",
    val instructions: String = "",
    val domain: TaskDomain = TaskDomain.GENERAL,
)

data class TaskContract(
    val architectModelId: String,
    val signatures: List<String>,
    val models: List<String>,
    val ownership: Map<String, List<String>>,
    val acceptance: List<String>,
    val testExpectations: List<String>,
    val implementers: Map<String, String>,
    val conflict: Boolean,
    val problems: List<String> = emptyList(),
)

data class FileEdit(val path: String, val before: String?, val after: String?)

sealed class NodeResult {
    data class Ok(val edits: List<FileEdit>) : NodeResult()
    data class Fail(val message: String, val conflict: Boolean = false) : NodeResult()
}

data class DagRun(
    val architectModelId: String,
    val contract: TaskContract,
    val mode: String,
    val fellBack: Boolean,
    val committed: Boolean,
    val waves: List<List<String>>,
    val maxInFlight: Int,
    val errors: List<String>,
    val staged: StagedTransaction,
    /** Cache had expired: the caller should compact and pack Turn-0 before the next cold write. */
    val preferCompact: Boolean = false,
)
