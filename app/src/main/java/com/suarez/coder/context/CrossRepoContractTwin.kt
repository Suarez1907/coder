package com.suarez.coder.context

import com.suarez.coder.harness.StagedTransaction
import org.json.JSONArray
import org.json.JSONObject

/**
 * Çoklu-Repo Canlı Kontrat Ağı (Cross-Repo Contract Twin).
 * En fazla [MAX_LINKED_REPOS] (5) repoluk bağlantı gruplarını destekler,
 * $0 yerel analiz ile kontrat parmak izlerini çıkarır ve kırılmaları tespit eder.
 */
class CrossRepoContractTwin(group: LinkedRepoGroup) {

    val group: LinkedRepoGroup = group.clamped()
    private val repoFiles = LinkedHashMap<String, LinkedHashMap<String, String>>()

    enum class ContractKind {
        HTTP_ENDPOINT,
        JSON_FIELD,
        WORKFLOW_INPUT,
        WORKFLOW_OUTPUT,
        ARTIFACT_NAME,
        SHARED_SCHEMA
    }

    data class LinkedRepoGroup(
        val name: String,
        val repos: List<String>
    ) {
        fun clamped(): LinkedRepoGroup = copy(repos = repos.distinct().take(MAX_LINKED_REPOS))

        fun toJson(): String {
            val arr = JSONArray()
            repos.take(MAX_LINKED_REPOS).forEach { arr.put(it) }
            return JSONObject()
                .put("name", name)
                .put("repos", arr)
                .toString()
        }

        companion object {
            fun fromJson(json: String): LinkedRepoGroup {
                val obj = JSONObject(json)
                val name = obj.optString("name", "default")
                val arr = obj.optJSONArray("repos") ?: JSONArray()
                val list = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i).trim()
                    if (item.isNotEmpty() && item !in list && list.size < MAX_LINKED_REPOS) {
                        list += item
                    }
                }
                return LinkedRepoGroup(name, list)
            }
        }
    }

    data class ContractFingerprint(
        val repo: String,
        val path: String,
        val line: Int,
        val kind: ContractKind,
        val key: String
    )

    data class ContractBreakage(
        val sourceRepo: String,
        val sourcePath: String,
        val targetRepo: String,
        val targetPath: String,
        val targetLine: Int,
        val kind: ContractKind,
        val key: String,
        val reason: String
    ) {
        fun formatNotice(): String =
            "Kontrat Uyarısı: $targetRepo / $targetPath:$targetLine kırılacak ($key - $reason)"
    }

    data class SyncPrResult(
        val targetRepo: String,
        val branch: String,
        val prUrl: String
    )

    interface GitHubSyncClient {
        fun defaultBranch(repo: String): String
        fun createBranch(repo: String, branch: String, fromBranch: String)
        fun commitFiles(repo: String, branch: String, files: Map<String, String>, message: String)
        fun createPullRequest(repo: String, headBranch: String, baseBranch: String, title: String, body: String): String
    }

    fun registerRepoFiles(repo: String, files: Map<String, String>) {
        if (repo !in group.repos) return
        val map = repoFiles.getOrPut(repo) { LinkedHashMap() }
        map.putAll(files)
    }

    fun extractFingerprints(repo: String, path: String, content: String): List<ContractFingerprint> {
        val out = ArrayList<ContractFingerprint>()
        val lower = path.lowercase()
        val lines = content.lines()

        // (a) HTTP uç noktaları ve JSON alan adları
        lines.forEachIndexed { idx, line ->
            val lineNo = idx + 1
            HTTP_DECORATOR_RE.findAll(line).forEach { m ->
                out += ContractFingerprint(repo, path, lineNo, ContractKind.HTTP_ENDPOINT, m.groupValues[1])
            }
            KTOR_ROUTE_RE.findAll(line).forEach { m ->
                out += ContractFingerprint(repo, path, lineNo, ContractKind.HTTP_ENDPOINT, m.groupValues[1])
            }
            JSON_ACCESS_RE.findAll(line).forEach { m ->
                out += ContractFingerprint(repo, path, lineNo, ContractKind.JSON_FIELD, m.groupValues[1])
            }
            JSON_PUT_RE.findAll(line).forEach { m ->
                out += ContractFingerprint(repo, path, lineNo, ContractKind.JSON_FIELD, m.groupValues[1])
            }
        }

        // (b) GitHub Actions workflow_dispatch inputs/outputs & artifact adları
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            var inDispatch = false
            var inInputs = false
            var inOutputs = false
            lines.forEachIndexed { idx, raw ->
                val lineNo = idx + 1
                val trimmed = raw.trim()
                val indent = raw.takeWhile { it == ' ' }.length
                if (trimmed.startsWith("workflow_dispatch:")) {
                    inDispatch = true
                    inInputs = false
                    inOutputs = false
                } else if (inDispatch && trimmed.startsWith("inputs:")) {
                    inInputs = true
                    inOutputs = false
                } else if (trimmed.startsWith("outputs:")) {
                    inOutputs = true
                    inInputs = false
                } else if (inInputs && indent >= 6 && trimmed.endsWith(":") && !trimmed.startsWith("#")) {
                    val key = trimmed.removeSuffix(":").trim()
                    if (key.isNotEmpty() && key !in WORKFLOW_META_KEYS) {
                        out += ContractFingerprint(repo, path, lineNo, ContractKind.WORKFLOW_INPUT, key)
                    }
                } else if (inOutputs && indent >= 4 && trimmed.endsWith(":") && !trimmed.startsWith("#")) {
                    val key = trimmed.removeSuffix(":").trim()
                    if (key.isNotEmpty() && key !in WORKFLOW_META_KEYS) {
                        out += ContractFingerprint(repo, path, lineNo, ContractKind.WORKFLOW_OUTPUT, key)
                    }
                }
                ARTIFACT_NAME_RE.find(raw)?.let { m ->
                    out += ContractFingerprint(repo, path, lineNo, ContractKind.ARTIFACT_NAME, m.groupValues[1].trim())
                }
            }
        }

        // (c) Paylaşılan dosya şemaları (.json, SKILL.md, .glb manifest)
        if (lower.endsWith(".json")) {
            lines.forEachIndexed { idx, line ->
                JSON_KEY_RE.find(line)?.let { m ->
                    out += ContractFingerprint(repo, path, idx + 1, ContractKind.SHARED_SCHEMA, m.groupValues[1])
                }
            }
        } else if (lower.endsWith("skill.md") || lower.endsWith(".glb.json")) {
            lines.forEachIndexed { idx, line ->
                YAML_FRONTMATTER_KEY_RE.find(line)?.let { m ->
                    out += ContractFingerprint(repo, path, idx + 1, ContractKind.SHARED_SCHEMA, m.groupValues[1])
                }
            }
        }

        return out.distinctBy { "${it.path}:${it.line}:${it.kind}:${it.key}" }
    }

    fun detectBreakages(sourceRepo: String, tx: StagedTransaction): List<ContractBreakage> {
        if (sourceRepo !in group.repos) return emptyList()
        val breakages = ArrayList<ContractBreakage>()

        for (edit in tx.edits()) {
            val beforeFps = extractFingerprints(sourceRepo, edit.path, edit.before.orEmpty())
            val afterKeys = extractFingerprints(sourceRepo, edit.path, edit.after.orEmpty())
                .map { it.kind to it.key }
                .toSet()

            val removed = beforeFps.filter { (it.kind to it.key) !in afterKeys }
            if (removed.isEmpty()) continue

            for (targetRepo in group.repos) {
                if (targetRepo == sourceRepo) continue
                val files = repoFiles[targetRepo].orEmpty()
                for ((targetPath, targetContent) in files) {
                    val targetFps = extractFingerprints(targetRepo, targetPath, targetContent)
                    for (rem in removed) {
                        val hit = targetFps.firstOrNull { it.key == rem.key }
                        if (hit != null) {
                            breakages += ContractBreakage(
                                sourceRepo = sourceRepo,
                                sourcePath = edit.path,
                                targetRepo = targetRepo,
                                targetPath = targetPath,
                                targetLine = hit.line,
                                kind = rem.kind,
                                key = rem.key,
                                reason = "silindi veya değiştirildi"
                            )
                        }
                    }
                }
            }
        }
        return breakages.distinctBy { "${it.targetRepo}:${it.targetPath}:${it.targetLine}:${it.key}" }
    }

    /**
     * KIRMIZI ÇİZGİ 3:
     * - userApproved == false iken SecurityException fırlatır ve GitHub'a tek bir yazma isteği göndermez.
     * - Hedef dal her zaman "coder/sync-contract-<timestamp>" olur; main/master/defaultBranch üzerine
     *   doğrudan push veya otomatik merge ASLA yapılmaz.
     */
    fun prepareSynchronizedPr(
        gh: GitHubSyncClient,
        breakage: ContractBreakage,
        updatedFiles: Map<String, String>,
        userApproved: Boolean,
        now: Long = System.currentTimeMillis()
    ): SyncPrResult {
        if (!userApproved) {
            throw SecurityException("KIRMIZI ÇİZGİ 3: Kullanıcı onayı (userApproved=true) olmadan Repo B'de dal veya PR açılamaz.")
        }
        val baseBranch = gh.defaultBranch(breakage.targetRepo)
        val branch = "coder/sync-contract-$now"
        require(branch != "main" && branch != "master" && branch != baseBranch) {
            "Doğrudan ana dala push yasaktır."
        }
        gh.createBranch(breakage.targetRepo, branch, baseBranch)
        gh.commitFiles(
            breakage.targetRepo,
            branch,
            updatedFiles,
            "chore(contract): sync ${breakage.key} from ${breakage.sourceRepo}"
        )
        val prUrl = gh.createPullRequest(
            repo = breakage.targetRepo,
            headBranch = branch,
            baseBranch = baseBranch,
            title = "Sync contract: ${breakage.key} (${breakage.sourceRepo})",
            body = breakage.formatNotice()
        )
        return SyncPrResult(breakage.targetRepo, branch, prUrl)
    }

    companion object {
        const val MAX_LINKED_REPOS = 5

        private val HTTP_DECORATOR_RE = Regex("""@(?:app|router)\.(?:get|post|put|delete|patch)\(\s*["']([^"']+)["']""")
        private val KTOR_ROUTE_RE = Regex("""\b(?:get|post|put|delete|patch)\(\s*["'](/[^"']+)["']""")
        private val JSON_ACCESS_RE = Regex("""(?:request\.json|payload|data|body|json)\s*(?:\[\s*["']([^"']+)["']\s*\]|\.get\(\s*["']([^"']+)["'])""")
        private val JSON_PUT_RE = Regex("""\.(?:optString|getString|optInt|getInt|put)\(\s*["']([^"']+)["']""")
        private val ARTIFACT_NAME_RE = Regex("""^\s*name:\s*["']?([a-zA-Z0-9._-]+)["']?\s*$""")
        private val JSON_KEY_RE = Regex("""^\s*"([^"]+)"\s*:""")
        private val YAML_FRONTMATTER_KEY_RE = Regex("""^([a-zA-Z0-9_-]+)\s*:""")
        private val WORKFLOW_META_KEYS = setOf("description", "required", "default", "type", "options")
    }
}
