package com.suarez.coder.ci

import org.json.JSONObject
import java.io.File

/**
 * Flaky failures: network hiccups, runner trouble and tests known to flip. Such a red CI is re-run once (no model
 * call) instead of sending the agent to "fix" code that is not broken.
 */
object Flaky {
    private val TRANSIENT = listOf(
        "connection reset", "connect timed out", "read timed out", "sockettimeoutexception", "etimedout", "econnreset", "eai_again",
        "could not resolve host", "could not get '", "could not download", "502 bad gateway", "503 service", "service unavailable",
        "tls handshake timeout", "the runner has received a shutdown signal", "lost communication with the server",
        "socket hang up", "npm err! network", "timeout waiting for emulator", "device offline", "emulator: error",
        "gradle build daemon disappeared", "could not connect to the gradle daemon", "rate limit exceeded",
    )

    fun isTransient(log: String): Boolean {
        val l = log.lowercase()
        return TRANSIENT.any { l.contains(it) }
    }

    /** Names of failed tests in a CI log (Gradle/JUnit, Jest/Vitest, pytest). */
    fun failedTests(log: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (raw in log.lines()) {
            val line = raw.trim()
            Regex("^([\\w.$]+) > (.+?) FAILED$").find(line)?.let { out += "${it.groupValues[1].substringAfterLast('.')}.${it.groupValues[2].trim()}" }
            Regex("^FAILED ([\\w./-]+::[\\w\\[\\]-]+)").find(line)?.let { out += it.groupValues[1] }
            Regex("^[✕×] (.+?)( \\(\\d+ ?m?s\\))?$").find(line)?.let { out += it.groupValues[1].trim() }
        }
        return out
    }

    /** Failed only because of tests that flipped before: worth one re-run. */
    fun onlyKnownFlaky(log: String, known: Set<String>): Boolean {
        val failed = failedTests(log)
        return failed.isNotEmpty() && known.containsAll(failed)
    }

    /** Tests that flipped (failed, then passed on the re-run without a code change), per repo. */
    class Store(private val file: File) {
        @Synchronized fun known(repo: String): Set<String> = runCatching {
            JSONObject(file.readText()).optJSONObject(repo.lowercase())?.keys()?.asSequence()?.toSet()
        }.getOrNull().orEmpty()

        @Synchronized fun record(repo: String, tests: Set<String>) {
            if (tests.isEmpty()) return
            val o = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
            val r = o.optJSONObject(repo.lowercase()) ?: JSONObject()
            tests.forEach { r.put(it, r.optInt(it) + 1) }
            o.put(repo.lowercase(), r)
            file.parentFile?.mkdirs(); file.writeText(o.toString())
        }

        @Synchronized fun all(): Map<String, Map<String, Int>> = runCatching {
            val o = JSONObject(file.readText())
            o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { r -> r.keys().asSequence().associateWith { r.getInt(it) } } }
        }.getOrDefault(emptyMap())
    }
}
