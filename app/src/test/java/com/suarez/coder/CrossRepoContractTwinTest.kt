package com.suarez.coder

import com.suarez.coder.context.CrossRepoContractTwin
import com.suarez.coder.harness.PreFlightValidator
import com.suarez.coder.harness.StagedTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossRepoContractTwinTest {

    private class FakeGitHubSyncClient : CrossRepoContractTwin.GitHubSyncClient {
        val calls = ArrayList<String>()
        override fun defaultBranch(repo: String): String = "main"
        override fun createBranch(repo: String, branch: String, fromBranch: String) {
            calls += "createBranch:$repo:$branch:$fromBranch"
        }
        override fun commitFiles(repo: String, branch: String, files: Map<String, String>, message: String) {
            calls += "commitFiles:$repo:$branch:${files.keys.joinToString(",")}"
        }
        override fun createPullRequest(repo: String, headBranch: String, baseBranch: String, title: String, body: String): String {
            calls += "createPR:$repo:$headBranch->$baseBranch"
            return "https://github.com/$repo/pull/42"
        }
    }

    @Test
    fun `detects broken JSON field and workflow input with exact target line`() {
        val group = CrossRepoContractTwin.LinkedRepoGroup(
            name = "core-platform",
            repos = listOf("org/api-server", "org/ci-runner")
        )
        val twin = CrossRepoContractTwin(group)
        twin.registerRepoFiles(
            "org/ci-runner",
            mapOf(
                "runner.py" to listOf(
                    "import requests",
                    "def run(payload):",
                    "    return payload[\"user_id\"]"
                ).joinToString("\n"),
                ".github/workflows/deploy.yml" to listOf(
                    "on:",
                    "  workflow_dispatch:",
                    "    inputs:",
                    "      deploy_env:",
                    "        required: true"
                ).joinToString("\n")
            )
        )

        val tx = StagedTransaction()
        tx.stage(
            "api.py",
            before = "def handle(request):\n    uid = request.json[\"user_id\"]\n",
            after = "def handle(request):\n    uid = request.json[\"account_id\"]\n"
        )
        tx.stage(
            ".github/workflows/trigger.yml",
            before = "on:\n  workflow_dispatch:\n    inputs:\n      deploy_env:\n        required: true\n",
            after = "on:\n  workflow_dispatch:\n    inputs:\n      target_env:\n        required: true\n"
        )

        val breakages = twin.detectBreakages("org/api-server", tx)
        assertEquals(2, breakages.size)
        assertTrue(breakages.any { it.targetRepo == "org/ci-runner" && it.targetPath == "runner.py" && it.targetLine == 3 && it.key == "user_id" })
        assertTrue(breakages.any { it.targetRepo == "org/ci-runner" && it.targetPath == ".github/workflows/deploy.yml" && it.targetLine == 4 && it.key == "deploy_env" })

        val findings = PreFlightValidator.checkContracts("org/api-server", tx, twin)
        assertEquals(2, findings.size)
        assertTrue(findings.all { it.rule == "cross_repo_contract" })
        assertTrue(findings.any { it.detail.contains("org/ci-runner / runner.py:3 kırılacak") })
    }

    @Test
    fun `enforces max 5 linked repos limit`() {
        val group = CrossRepoContractTwin.LinkedRepoGroup(
            name = "too-many",
            repos = listOf("r/1", "r/2", "r/3", "r/4", "r/5", "r/6", "r/7")
        )
        val twin = CrossRepoContractTwin(group)
        assertEquals(CrossRepoContractTwin.MAX_LINKED_REPOS, twin.group.repos.size)
        assertEquals(listOf("r/1", "r/2", "r/3", "r/4", "r/5"), twin.group.repos)
    }

    @Test(expected = SecurityException::class)
    fun `refuses any branch or PR creation when userApproved is false`() {
        val twin = CrossRepoContractTwin(CrossRepoContractTwin.LinkedRepoGroup("g", listOf("a/1", "b/2")))
        val gh = FakeGitHubSyncClient()
        val breakage = CrossRepoContractTwin.ContractBreakage(
            "a/1", "api.py", "b/2", "runner.py", 10,
            CrossRepoContractTwin.ContractKind.JSON_FIELD, "user_id", "silindi"
        )
        try {
            twin.prepareSynchronizedPr(gh, breakage, mapOf("runner.py" to "fixed"), userApproved = false, now = 1000L)
        } finally {
            assertTrue(gh.calls.isEmpty())
        }
    }

    @Test
    fun `opens PR only on isolated coder sync-contract branch when userApproved is true`() {
        val twin = CrossRepoContractTwin(CrossRepoContractTwin.LinkedRepoGroup("g", listOf("a/1", "b/2")))
        val gh = FakeGitHubSyncClient()
        val breakage = CrossRepoContractTwin.ContractBreakage(
            "a/1", "api.py", "b/2", "runner.py", 10,
            CrossRepoContractTwin.ContractKind.JSON_FIELD, "user_id", "silindi"
        )
        val res = twin.prepareSynchronizedPr(gh, breakage, mapOf("runner.py" to "fixed"), userApproved = true, now = 123456L)
        assertEquals("coder/sync-contract-123456", res.branch)
        assertEquals("https://github.com/b/2/pull/42", res.prUrl)
        assertEquals(
            listOf(
                "createBranch:b/2:coder/sync-contract-123456:main",
                "commitFiles:b/2:coder/sync-contract-123456:runner.py",
                "createPR:b/2:coder/sync-contract-123456->main"
            ),
            gh.calls
        )
    }
}
