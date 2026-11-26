package com.suarez.coder.security

import com.suarez.coder.llm.Http
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant

/** Credentials in a change: never committed. */
object Secrets {
    data class Finding(val path: String, val line: Int, val kind: String)

    private val PATTERNS = listOf(
        "GitHub token" to Regex("\\b(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36,}\\b|\\bgithub_pat_[A-Za-z0-9_]{50,}\\b"),
        "AWS erişim anahtarı" to Regex("\\bAKIA[0-9A-Z]{16}\\b"),
        "Google API anahtarı" to Regex("\\bAIza[0-9A-Za-z_\\-]{35}\\b"),
        "Anthropic anahtarı" to Regex("\\bsk-ant-[A-Za-z0-9_\\-]{32,}"),
        "OpenAI/OpenRouter anahtarı" to Regex("\\bsk-(or-v1-|proj-)?[A-Za-z0-9_\\-]{32,}"),
        "Slack token" to Regex("\\bxox[baprs]-[A-Za-z0-9-]{10,}"),
        "Stripe anahtarı" to Regex("\\b(sk|rk)_live_[A-Za-z0-9]{20,}"),
        "Özel anahtar" to Regex("-----BEGIN (RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY-----"),
    )

    /** Lines in [after] that were not in [before] (count-aware). */
    fun addedLines(before: String?, after: String): List<Pair<Int, String>> {
        val left = HashMap<String, Int>()
        before?.lines()?.forEach { left.merge(it, 1, Int::plus) }
        return after.lines().withIndex().mapNotNull { (i, l) ->
            val n = left[l] ?: 0
            if (n > 0) { left[l] = n - 1; null } else (i + 1) to l
        }
    }

    /** [ownKeys]: the user's own API keys and token (an exact copy is the most certain leak). */
    fun scan(path: String, before: String?, after: String, ownKeys: List<String>): List<Finding> {
        val keys = ownKeys.filter { it.length >= 12 }
        return addedLines(before, after).mapNotNull { (n, l) ->
            when {
                keys.any { l.contains(it) } -> Finding(path, n, "senin API anahtarın / token'ın")
                else -> PATTERNS.firstOrNull { it.second.containsMatchIn(l) }?.let { Finding(path, n, it.first) }
            }
        }.take(5)
    }
}

/** Dependencies a change adds (npm, PyPI, Gradle/Maven). */
object Deps {
    data class Dep(val eco: String, val name: String, val version: String?)

    private fun clean(v: String?) = v?.trim()?.removePrefix("^")?.removePrefix("~")?.removePrefix("=")?.removePrefix("v")
        ?.takeIf { Regex("^\\d+(\\.\\d+)*([.-][\\w.]+)?$").matches(it) }

    private fun npm(text: String?): Map<String, String?> = runCatching {
        val o = JSONObject(text ?: return emptyMap())
        listOf("dependencies", "devDependencies", "peerDependencies", "optionalDependencies").flatMap { k ->
            o.optJSONObject(k)?.let { d -> d.keys().asSequence().map { it to d.optString(it) }.toList() }.orEmpty()
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun pip(text: String?): Map<String, String?> = (text ?: "").lines().mapNotNull { raw ->
        val l = raw.substringBefore('#').trim()
        if (l.isEmpty() || l.startsWith("-")) return@mapNotNull null
        val m = Regex("^([A-Za-z0-9][A-Za-z0-9._-]*)(\\[[^]]*])?\\s*(==|>=|~=|<=|>|<)?\\s*([\\w.]+)?").find(l) ?: return@mapNotNull null
        m.groupValues[1].lowercase().replace('_', '-') to m.groupValues[4].ifBlank { null }
    }.toMap()

    private fun gradle(text: String?): Map<String, String?> =
        Regex("[\"']([\\w.-]+):([\\w.-]+):([\\w.+-]+)[\"']").findAll(text ?: "").associate { "${it.groupValues[1]}:${it.groupValues[2]}" to it.groupValues[3] }

    private fun catalog(text: String?): Map<String, String?> {
        val t = text ?: return emptyMap()
        val versions = Regex("(?m)^\\s*([\\w.-]+)\\s*=\\s*\"([^\"]+)\"").findAll(t.substringAfter("[versions]", "").substringBefore("\n[")).associate { it.groupValues[1] to it.groupValues[2] }
        val libs = t.substringAfter("[libraries]", "").substringBefore("\n[")
        val out = HashMap<String, String?>()
        for (line in libs.lines()) {
            Regex("module\\s*=\\s*\"([\\w.-]+):([\\w.-]+)\"").find(line)?.let { m ->
                val v = Regex("version\\.ref\\s*=\\s*\"([\\w.-]+)\"").find(line)?.groupValues?.get(1)?.let { versions[it] }
                    ?: Regex("version\\s*=\\s*\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                out["${m.groupValues[1]}:${m.groupValues[2]}"] = v
            }
            Regex("=\\s*\"([\\w.-]+):([\\w.-]+):([^\"]+)\"").find(line)?.let { m -> out["${m.groupValues[1]}:${m.groupValues[2]}"] = m.groupValues[3] }
        }
        return out
    }

    fun added(path: String, before: String?, after: String): List<Dep> {
        val file = path.substringAfterLast('/')
        val (eco, parse) = when {
            file == "package.json" -> "npm" to ::npm
            file.startsWith("requirements") && file.endsWith(".txt") -> "PyPI" to ::pip
            file == "libs.versions.toml" -> "Maven" to ::catalog
            file.endsWith(".gradle") || file.endsWith(".gradle.kts") -> "Maven" to ::gradle
            else -> return emptyList()
        }
        val old = parse(before)
        return parse(after).filterKeys { it !in old }.map { (n, v) -> Dep(eco, n, clean(v)) }
    }

    private val POPULAR = mapOf(
        "npm" to listOf("react", "react-dom", "lodash", "express", "axios", "vue", "next", "typescript", "webpack", "vite", "jest", "eslint",
            "prettier", "moment", "dayjs", "chalk", "commander", "debug", "uuid", "dotenv", "cors", "body-parser", "mongoose", "redux",
            "svelte", "three", "phaser", "socket.io", "jquery", "underscore", "async", "request", "yargs", "inquirer", "ws", "nodemon",
            "tailwindcss", "postcss", "sass", "rxjs", "zod", "joi", "bcrypt", "jsonwebtoken", "passport", "sequelize", "mysql2", "redis",
            "ioredis", "graphql", "styled-components", "framer-motion", "gsap", "remotion", "playwright", "puppeteer", "cheerio",
            "node-fetch", "form-data", "cross-env", "concurrently", "vitest", "electron", "colors", "minimist", "semver", "glob"),
        "PyPI" to listOf("requests", "numpy", "pandas", "scipy", "matplotlib", "flask", "django", "fastapi", "pydantic", "sqlalchemy",
            "pytest", "boto3", "urllib3", "setuptools", "pyyaml", "pillow", "beautifulsoup4", "selenium", "tensorflow", "torch",
            "scikit-learn", "opencv-python", "jinja2", "click", "rich", "httpx", "aiohttp", "celery", "psycopg2", "pymongo", "openai",
            "anthropic", "transformers", "manim", "pygame", "uvicorn", "gunicorn", "black", "ruff", "mypy", "tqdm", "python-dotenv",
            "cryptography", "paramiko", "colorama", "certifi", "idna", "charset-normalizer"),
    )

    /** Damerau-Levenshtein distance (adjacent transpositions count as one edit). */
    fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }

    /** The popular package this name imitates (typosquatting), or null. */
    fun lookalike(eco: String, name: String): String? {
        val n = name.lowercase()
        val list = POPULAR[eco] ?: return null
        if (n in list || n.length < 4) return null
        val limit = if (n.length <= 6) 1 else 2
        return list.firstOrNull { p -> kotlin.math.abs(p.length - n.length) <= limit && distance(n, p) in 1..limit }
    }
}

/** What a package registry and the OSV database say about a dependency (null = could not tell). */
class RegistryChecker {
    data class Verdict(val dep: Deps.Dep, val exists: Boolean?, val ageDays: Long?, val weeklyDownloads: Long?, val vulns: List<String>)

    companion object {
        @Volatile var NPM = "https://registry.npmjs.org"
        @Volatile var NPM_DOWNLOADS = "https://api.npmjs.org"
        @Volatile var PYPI = "https://pypi.org"
        @Volatile var MAVEN_SEARCH = "https://search.maven.org"
        @Volatile var GOOGLE_MAVEN = "https://dl.google.com/android/maven2"
        @Volatile var OSV = "https://api.osv.dev"
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }

    private suspend fun get(url: String): Http.Result? = withTimeoutOrNull(10_000) { runCatching { Http.execute(Request.Builder().url(url).build()) }.getOrNull() }

    private fun days(iso: String?): Long? = runCatching { Duration.between(Instant.parse(iso), Instant.now()).toDays() }.getOrNull()

    suspend fun check(d: Deps.Dep): Verdict = coroutineScope {
        val vulns = async { osv(d) }
        val (exists, age, dl) = when (d.eco) {
            "npm" -> {
                val r = get("$NPM/${d.name.replace("/", "%2F")}")
                when (r?.code) {
                    404 -> Triple(false, null, null)
                    200 -> {
                        val age = days(runCatching { JSONObject(r.body).optJSONObject("time")?.optString("created") }.getOrNull())
                        val dls = get("$NPM_DOWNLOADS/downloads/point/last-week/${d.name}")?.takeIf { it.code == 200 }
                            ?.let { runCatching { JSONObject(it.body).optLong("downloads") }.getOrNull() }
                        Triple(true, age, dls)
                    }
                    else -> Triple(null, null, null)
                }
            }
            "PyPI" -> {
                val r = get("$PYPI/pypi/${enc(d.name)}/json")
                when (r?.code) {
                    404 -> Triple(false, null, null)
                    200 -> {
                        val first = runCatching {
                            val rel = JSONObject(r.body).getJSONObject("releases")
                            rel.keys().asSequence().flatMap { k -> rel.getJSONArray(k).let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("upload_time_iso_8601") } } }
                                .filter { it.isNotBlank() }.minOrNull()
                        }.getOrNull()
                        Triple(true, days(first), null)
                    }
                    else -> Triple(null, null, null)
                }
            }
            "Maven" -> {
                val (g, a) = d.name.split(':').let { it[0] to it.getOrElse(1) { "" } }
                val found = get("$MAVEN_SEARCH/solrsearch/select?q=${enc("g:\"$g\" AND a:\"$a\"")}&rows=1&wt=json")?.takeIf { it.code == 200 }
                    ?.let { runCatching { JSONObject(it.body).getJSONObject("response").getInt("numFound") > 0 }.getOrNull() }
                val google = if (found == true) true else get("$GOOGLE_MAVEN/${g.replace('.', '/')}/$a/maven-metadata.xml")?.let { it.code == 200 }
                // Not in Central or Google Maven may still be JitPack/private: unknown, not "missing".
                Triple(if (found == true || google == true) true else null, null, null)
            }
            else -> Triple(null, null, null)
        }
        Verdict(d, exists, age, dl, vulns.await())
    }

    private suspend fun osv(d: Deps.Dep): List<String> {
        val v = d.version ?: return emptyList()
        val body = JSONObject().put("package", JSONObject().put("name", d.name).put("ecosystem", d.eco)).put("version", v)
        val r = withTimeoutOrNull(10_000) {
            runCatching { Http.execute(Request.Builder().url("$OSV/v1/query").post(body.toString().toRequestBody(Http.JSON)).build()) }.getOrNull()
        } ?: return emptyList()
        if (r.code != 200) return emptyList()
        return runCatching { JSONObject(r.body).optJSONArray("vulns") ?: JSONArray() }.getOrDefault(JSONArray())
            .let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("id") }.filter { it.isNotBlank() } }.take(5)
    }
}

/** Turns the checks into "block" (with reasons) and "warn". */
object SupplyChainPolicy {
    data class Outcome(val block: List<String>, val warn: List<String>)

    fun evaluate(verdicts: List<RegistryChecker.Verdict>): Outcome {
        val block = ArrayList<String>(); val warn = ArrayList<String>()
        for (v in verdicts) {
            val n = "${v.dep.name} (${v.dep.eco})"
            // A look-alike name is suspicious only if the package is not itself old or widely used (preact vs react).
            val established = v.exists == true && ((v.weeklyDownloads ?: 0) >= 50_000 || (v.ageDays ?: 0) >= 730)
            if (!established) Deps.lookalike(v.dep.eco, v.dep.name)?.let { block += "$n ünlü '$it' paketine çok benziyor (typosquatting şüphesi)" }
            when (v.exists) {
                false -> block += "$n kayıt defterinde YOK: model paket adını uydurmuş olabilir (slopsquatting riski)"
                null -> if (v.dep.eco == "Maven") warn += "$n Maven Central/Google Maven'da bulunamadı (JitPack veya özel depo olabilir)"
                true -> Unit
            }
            if (v.vulns.isNotEmpty()) warn += "$n ${v.dep.version} sürümünde bilinen açıklar: ${v.vulns.joinToString()} (daha yeni sürüm kullan)"
            if (v.exists == true && (v.ageDays ?: Long.MAX_VALUE) < 30 && (v.weeklyDownloads ?: Long.MAX_VALUE) < 1_000) {
                warn += "$n çok yeni (${v.ageDays} gün) ve az kullanılıyor: gerçekten bu paket mi isteniyor?"
            }
        }
        return Outcome(block, warn)
    }
}
