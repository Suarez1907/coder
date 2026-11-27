package com.suarez.coder

import com.suarez.coder.harness.PreFlightValidator
import com.suarez.coder.harness.SecurityScanner
import com.suarez.coder.harness.StagedTransaction
import com.suarez.coder.qa.CrashLoopRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SecurityScannerTest {
    @Test fun blocksRealTokensUnsafeManifestAndSigning() {
        val tx = StagedTransaction()
        tx.stage("Env.kt", "fun old() = 1\n", """
            const val A = "ghp_abcdefghijklmnopqrstuvwxyz0123456789"
            const val B = "github_pat_abcdefghijklmnopqrstuvwxyz11"
            const val C = "AIzaSyA1234567890abcdefghijklmnop"
            const val D = "sk-or-v1-abcdefghijklmnopqrstuvwxyz"
            const val E = "sk-ant-api03-abcdefghijklmnopqrstuvwxyz"
            const val F = "xoxb-1234567890-abcdefghij"
            const val K = "-----BEGIN PRIVATE KEY-----"
            api_key = "aB3dE6gH9jK2mN5pQ8sT1uV4wX7yZ0"
            storePassword = "s3cret-value-aaa"
        """.trimIndent())
        tx.stage("app/src/main/AndroidManifest.xml", "<manifest></manifest>", """
            <manifest>
              <uses-permission android:name="android.permission.CAMERA"/>
              <application android:usesCleartextTraffic="true">
                <activity android:exported="true" />
              </application>
            </manifest>
        """.trimIndent())
        tx.stage("keys/app.keystore", "old", "new")
        val report = PreFlightValidator.securityGate(tx)
        assertTrue(report.blocked)
        assertTrue(report.repairPrompt().contains("durdurdu"))
        val kinds = report.hits.map { it.kind }.toSet()
        assertTrue(kinds.contains("secret"))
        assertTrue(kinds.contains("entropy"))
        assertTrue(kinds.contains("signing"))
        assertTrue(kinds.contains("manifest_permission"))
        assertTrue(kinds.contains("manifest_exported"))
        assertTrue(kinds.contains("manifest_cleartext"))
        assertTrue(kinds.contains("sensitive_file"))
    }

    @Test fun placeholdersAndFixturesAreNotSecrets() {
        val tx = StagedTransaction()
        tx.stage("Notes.kt", null, """
            const val token = "ghp_exampleexampleexampleexample"
            const val placeholder = "xxxxxxxxxxxxxxxxxxxxxxxx"
            fun add(a: Int, b: Int) = a + b
        """.trimIndent())
        tx.stage("src/test/fixtures/sample.txt", null, "api_key = \"aB3dE6gH9jK2mN5pQ8sT1uV4wX7yZ0\"")
        val report = SecurityScanner.scan(tx)
        assertFalse(report.blocked.toString(), report.blocked)
        assertTrue(report.hits.isEmpty())
    }

    @Test fun unchangedSecretLineIsNotANewHit() {
        val line = "const val A = \"ghp_abcdefghijklmnopqrstuvwxyz0123456789\""
        val report = SecurityScanner.scanText("Env.kt", line, line)
        assertTrue(report.isEmpty())
    }
}

class CrashTraceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun mapsPastedStackToFileAndLine() {
        val text = """
            FATAL EXCEPTION: main
            java.lang.NullPointerException: boom
            at com.suarez.coder.ui.ChatScreenKt.UserBubble(ChatScreen.kt:775)
            Caused by: java.lang.IllegalStateException
        """.trimIndent()
        assertTrue(CrashLoopRecorder.looksLikeCrash(text))
        val frame = CrashLoopRecorder.parseCrashTrace(text).single()
        assertEquals("ChatScreen.kt", frame.file)
        assertEquals(775, frame.line)
        assertTrue(frame.symbol.contains("UserBubble"))
    }

    @Test fun writesRecordThenClearsIt() {
        val dir = tmp.newFolder()
        CrashLoopRecorder.screen = { "sohbet" }
        CrashLoopRecorder.repo = { "me/app" }
        CrashLoopRecorder.version = { "1.0.9" }
        CrashLoopRecorder.write(dir, Thread.currentThread(), IllegalStateException("boom"))
        val rec = CrashLoopRecorder.pending(dir)
        assertNotNull(rec)
        assertEquals("sohbet", rec!!.screen)
        assertEquals("me/app", rec.repo)
        assertTrue(rec.trace.contains("boom"))
        assertTrue(java.io.File(dir, "crashes").listFiles().orEmpty().any { it.name.startsWith("crash-") && it.name.contains("-v") })
        assertTrue(CrashLoopRecorder.prompt(rec).contains("ChatScreen.kt") || CrashLoopRecorder.prompt(rec).contains("boom"))
        CrashLoopRecorder.resolve(dir)
        assertNull(CrashLoopRecorder.pending(dir))
    }
}
