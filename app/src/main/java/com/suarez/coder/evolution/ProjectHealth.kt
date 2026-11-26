package com.suarez.coder.evolution

import com.suarez.coder.llm.Http
import com.suarez.coder.tools.GitHub
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * Weekly health check of the user's repositories: last CI result on the default branch, open PRs, days since the
 * last push, and outdated build tools/dependencies (Gradle wrapper, Kotlin, npm packages a major version behind).
 * Report only: version bumps are not pushed automatically because lockfiles and plugin compatibility make blind
 * upgrades break builds; the agent can do them on request.
 */
class ProjectHealth(private val gh: GitHub, private val npmLatest: suspend (String) -> String? = ::npmLatestVersion) {
    data class RepoHealth(
        val repo: String,
        val ci: String?,
        val openPrs: Int,
        val daysSincePush: Long?,
        val outdated: List<String>,
        val error: String? = null,
    ) {
        val ok get() = error == null && ci != "failure" && outdated.isEmpty()
        fun line(): String = error?.let { "$repo: kontrol edilemedi ($it)" } ?: buildList {
            add("CI " + (ci ?: "yok"))
            if (openPrs > 0) add("$openPrs açık PR")
            daysSincePush?.let { add("son push $it gün önce") }
            if (outdated.isNotEmpty()) add("eski: " + outdated.joinToString(", "))
        }.joinToString(" · ", prefix = "$repo: ")

        fun toJson(): JSONObject = JSONObject().put("repo", repo).put("ci", ci ?: JSONObject.NULL).put("prs", openPrs)
            .put("days", daysSincePush ?: JSONObject.NULL).put("outdated", JSONArray(outdated)).put("error", error ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject) = RepoHealth(
            o.optString("repo"), o.optString("ci").takeIf { !o.isNull("ci") && it.isNotBlank() }, o.optInt("prs"),
            if (o.isNull("days")) null else o.optLong("days"),
            o.optJSONArray("outdated")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
            o.optString("error").takeIf { !o.isNull("error") && it.isNotBlank() },
        )

        fun lineFromJson(o: JSONObject): String = fromJson(o).let { (if (it.ok) "" else "! ") + it.line() }

        private val SEMVER = Regex("(\\d+)\\.(\\d+)(?:\\.(\\d+))?")

        fun major(v: String): Int? = SEMVER.find(v)?.groupValues?.get(1)?.toIntOrNull()

        /** "x.y.z" parts for ordering; missing parts are 0. */
        fun parts(v: String): List<Int> = SEMVER.find(v)?.groupValues?.drop(1)?.map { it.toIntOrNull() ?: 0 } ?: emptyList()

        fun isBehind(current: String, latest: String): Boolean {
            val a = parts(current); val b = parts(latest)
            if (a.isEmpty() || b.isEmpty()) return false
            for (i in 0 until 3) { val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }; if (x != y) return x < y }
            return false
        }

        fun gradleWrapperVersion(props: String): String? = Regex("gradle-(\\d+\\.\\d+(?:\\.\\d+)?)-(bin|all)\\.zip").find(props)?.groupValues?.get(1)

        /** Kotlin version from a version catalog or a Gradle build file. */
        fun kotlinVersion(text: String): String? =
            Regex("(?m)^\\s*kotlin\\s*=\\s*\"(\\d+\\.\\d+\\.\\d+)\"").find(text)?.groupValues?.get(1)
                ?: Regex("org\\.jetbrains\\.kotlin[.\\w]*\"\\)?\\s*version\\s*\"(\\d+\\.\\d+\\.\\d+)\"").find(text)?.groupValues?.get(1)

        /** (name, version range) of dependencies and devDependencies. */
        fun npmDeps(packageJson: String): List<Pair<String, String>> = runCatching {
            val o = JSONObject(packageJson)
            listOf("dependencies", "devDependencies").flatMap { k ->
                o.optJSONObject(k)?.let { d -> d.keys().asSequence().map { it to d.optString(it) }.toList() }.orEmpty()
            }
        }.getOrDefault(emptyList())

        suspend fun npmLatestVersion(name: String): String? = runCatching {
            val r = Http.execute(Request.Builder().url("https://registry.npmjs.org/${name.replace("/", "%2F")}/latest").build())
            if (r.code in 200..299) JSONObject(r.body).optString("version").ifBlank { null } else null
        }.getOrNull()
    }

    suspend fun check(repo: String): RepoHealth = try {
        val info = JSONObject(gh.raw("GET", "/repos/$repo", null).substringAfter('\n'))
        val branch = info.optString("default_branch").ifBlank { "main" }
        val days = runCatching { Duration.between(Instant.parse(info.optString("pushed_at")), Instant.now()).toDays() }.getOrNull()
        val runs = runCatching { JSONObject(gh.raw("GET", "/repos/$repo/actions/runs?branch=$branch&per_page=1", null).substringAfter('\n')) }.getOrNull()
        val ci = runs?.optJSONArray("workflow_runs")?.optJSONObject(0)?.let { r -> r.optString("conclusion").takeIf { !r.isNull("conclusion") && it.isNotBlank() } ?: r.optString("status") }
        val prs = runCatching { gh.openPullRequests(repo).size }.getOrDefault(0)
        val outdated = ArrayList<String>()
        gh.readFileRaw(repo, "gradle/wrapper/gradle-wrapper.properties", branch)?.let { gradleWrapperVersion(it) }?.let { cur ->
            latestRelease("gradle/gradle")?.let { latest -> if (major(cur) != null && (major(latest) ?: 0) > major(cur)!!) outdated += "Gradle $cur → $latest" }
        }
        val kotlinSrc = gh.readFileRaw(repo, "gradle/libs.versions.toml", branch) ?: gh.readFileRaw(repo, "build.gradle.kts", branch)
        kotlinSrc?.let { kotlinVersion(it) }?.let { cur ->
            latestRelease("JetBrains/kotlin")?.let { latest -> if (isBehind(cur, latest) && parts(cur).take(2) != parts(latest).take(2)) outdated += "Kotlin $cur → $latest" }
        }
        gh.readFileRaw(repo, "package.json", branch)?.let { pj ->
            for ((name, range) in npmDeps(pj).take(15)) {
                val cur = major(range) ?: continue
                val latest = npmLatest(name) ?: continue
                if ((major(latest) ?: cur) > cur) outdated += "$name $range → $latest"
            }
        }
        RepoHealth(repo, ci, prs, days, outdated.take(8))
    } catch (e: Exception) {
        RepoHealth(repo, null, 0, null, emptyList(), e.message?.take(120))
    }

    private suspend fun latestRelease(repo: String): String? = runCatching {
        JSONObject(gh.raw("GET", "/repos/$repo/releases/latest", null).substringAfter('\n')).optString("tag_name").removePrefix("v").ifBlank { null }
    }.getOrNull()
}
