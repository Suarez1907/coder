package com.suarez.coder

import com.suarez.coder.ci.Flaky
import com.suarez.coder.data.SessionStore
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.security.Deps
import com.suarez.coder.security.RegistryChecker
import com.suarez.coder.security.Secrets
import com.suarez.coder.security.SupplyChainPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformUnitTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun chatLogAppendsAndRewritesOnlyWhenNeeded() {
        val dir = tmp.newFolder("chats")
        val store = SessionStore(dir)
        val big = ChatMessage.ToolResult("c", "github_read_file", "x".repeat(50_000))
        val msgs = mutableListOf<ChatMessage>(ChatMessage.User("merhaba"), big)
        store.save("s", msgs, "r", 0.0)
        assertTrue(store.lastWriteBytes > 50_000) // first write: whole log
        msgs += ChatMessage.Notice("adım")
        store.save("s", msgs, "r", 0.0)
        assertTrue(store.lastWriteBytes < 200) // append only the new message
        msgs.add(1, ChatMessage.ContextBlock("bağlam", "etiket")) // inserted in the middle: rewrite
        store.save("s", msgs, "r", 0.0)
        assertTrue(store.lastWriteBytes > 50_000)
        assertEquals(msgs, SessionStore(dir).load("s"))
        // A torn last line (the app died mid-write) is skipped, the rest loads.
        java.io.File(dir, "s.jsonl").appendText("{\"t\":\"notice\",\"te")
        assertEquals(msgs.size, SessionStore(dir).load("s").size)
        assertTrue(SessionStore(dir).search("bağlam").isNotEmpty())
    }

    @Test fun legacyJsonChatsStillLoad() {
        val dir = tmp.newFolder("old")
        java.io.File(dir, "x.json").writeText(com.suarez.coder.llm.MessageJson.toJson(listOf(ChatMessage.User("eski"))))
        assertEquals("eski", (SessionStore(dir).load("x").single() as ChatMessage.User).text)
    }

    @Test fun secretsInAddedLinesOnly() {
        val token = "ghp_" + "A1b2C3d4".repeat(5)
        assertEquals("GitHub token", Secrets.scan("a.kt", null, "val t = \"$token\"", emptyList()).single().kind)
        assertTrue(Secrets.scan("a.kt", "val t = \"$token\"", "val t = \"$token\"\nval x = 1", emptyList()).isEmpty()) // already there
        assertEquals(1, Secrets.scan("c.kt", null, "key = \"my-own-gemini-key-123\"", listOf("my-own-gemini-key-123")).size)
        assertTrue(Secrets.scan("d.kt", null, "val k = \"sk-...\" // placeholder", emptyList()).isEmpty())
        assertEquals("Özel anahtar", Secrets.scan("id", null, "-----BEGIN OPENSSH PRIVATE KEY-----", emptyList()).single().kind)
    }

    @Test fun addedDependencies() {
        val npm = Deps.added("web/package.json", """{"dependencies":{"react":"^18.2.0"}}""", """{"dependencies":{"react":"^18.2.0","lodahs":"^4.17.21"},"devDependencies":{"vite":"5.1.0"}}""")
        assertEquals(listOf("lodahs" to "4.17.21", "vite" to "5.1.0"), npm.map { it.name to it.version })
        assertEquals(listOf("reqeusts"), Deps.added("requirements.txt", "flask==3.0.0", "flask==3.0.0\nreqeusts==2.31.0  # http").map { it.name })
        assertEquals(listOf("com.squareup.retrofit2:retrofit"), Deps.added("app/build.gradle.kts", "", "implementation(\"com.squareup.retrofit2:retrofit:2.11.0\")").map { it.name })
        val toml = "[versions]\nroom = \"2.6.1\"\n[libraries]\nroom = { module = \"androidx.room:room-runtime\", version.ref = \"room\" }\n"
        assertEquals(listOf(Deps.Dep("Maven", "androidx.room:room-runtime", "2.6.1")), Deps.added("gradle/libs.versions.toml", "[versions]\n[libraries]\n", toml))
        assertEquals("lodash", Deps.lookalike("npm", "lodahs"))
        assertEquals("requests", Deps.lookalike("PyPI", "reqeusts"))
        assertNull(Deps.lookalike("npm", "react"))
        // preact is one edit from react, yet a real, old, popular package: the policy (not the name check) clears it.
        assertEquals("react", Deps.lookalike("npm", "preact"))
        val preact = RegistryChecker.Verdict(Deps.Dep("npm", "preact", "10.0.0"), true, 3_000, 3_000_000, emptyList())
        assertTrue(SupplyChainPolicy.evaluate(listOf(preact)).block.isEmpty())
    }

    @Test fun policyBlocksMissingAndLookalikeWarnsOnVulns() {
        val v = { n: String, e: Boolean?, vul: List<String> -> RegistryChecker.Verdict(Deps.Dep("npm", n, "1.0.0"), e, 10, 50, vul) }
        val o = SupplyChainPolicy.evaluate(listOf(v("left-padd", false, emptyList()), v("lodahs", true, emptyList()), v("left-pad", true, listOf("GHSA-x"))))
        assertEquals(2, o.block.size)
        assertTrue(o.warn.any { it.contains("GHSA-x") })
    }

    @Test fun flakyParsing() {
        assertTrue(Flaky.isTransient("Error: java.net.SocketException: Connection reset by peer"))
        assertFalse(Flaky.isTransient("e: Main.kt:3:1 Unresolved reference: foo"))
        val log = "ScoreTest > savesScore FAILED\nFAILED tests/test_api.py::test_login - AssertionError\n✕ renders header (12 ms)"
        assertEquals(setOf("ScoreTest.savesScore", "tests/test_api.py::test_login", "renders header"), Flaky.failedTests(log))
        assertTrue(Flaky.onlyKnownFlaky("ScoreTest > savesScore FAILED", setOf("ScoreTest.savesScore")))
        assertFalse(Flaky.onlyKnownFlaky("ScoreTest > savesScore FAILED\nOtherTest > x FAILED", setOf("ScoreTest.savesScore")))
    }
}
