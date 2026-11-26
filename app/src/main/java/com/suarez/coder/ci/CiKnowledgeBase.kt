package com.suarez.coder.ci

import com.suarez.coder.agent.ErrorSig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * CI dersleri: kırmızı koşulardan çıkarılan kalıplar ("belirti → sebep → çözüm"), repo bazında. FixMemory bir hatanın
 * son çözüm metnini tutar; burada ayrıca kaç kez görüldüğü ve hangi sınıfta olduğu tutulur, böylece ajana ham log
 * yerine tek satırlık ipucu verilir.
 */
class CiKnowledgeBase(private val file: File) {

    /** Hatanın sınıfı: ajanın nereye bakacağını belirler. */
    enum class Cause(val tr: String) {
        INFRA("altyapı"), COMPILE("derleme"), DEPENDENCY("bağımlılık"), TEST("test"), LINT("stil"), UNKNOWN("bilinmeyen");

        companion object {
            fun of(signature: String): Cause {
                val s = signature.lowercase()
                return when {
                    Flaky.isTransient(s) -> INFRA
                    listOf("unresolved reference", "type mismatch", "cannot find symbol", "e: file", "compilation error",
                        "error ts", "not assignable", "expecting", "redeclaration", "overrides nothing").any { s.contains(it) } -> COMPILE
                    listOf("could not resolve", "could not find", "dependency", "no matching version", "npm err!",
                        "unresolved import", "module not found", "package does not exist").any { s.contains(it) } -> DEPENDENCY
                    listOf("failed", "assertion", "expected:", "test", "junit").any { s.contains(it) } -> TEST
                    listOf("ktlint", "detekt", "lint", "style").any { s.contains(it) } -> LINT
                    else -> UNKNOWN
                }
            }
        }
    }

    data class Lesson(val repo: String, val key: String, val symptom: String, val cause: Cause, val fix: String, val hits: Int, val lastAt: Long)

    @Synchronized fun all(): List<Lesson> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Lesson(
                o.optString("repo"), o.optString("key"), o.optString("symptom"),
                runCatching { Cause.valueOf(o.optString("cause")) }.getOrDefault(Cause.UNKNOWN),
                o.optString("fix"), o.optInt("hits", 1), o.optLong("at")
            )
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<Lesson>) {
        file.parentFile?.mkdirs()
        val a = JSONArray()
        list.forEach { l ->
            a.put(
                JSONObject().put("repo", l.repo).put("key", l.key).put("symptom", l.symptom)
                    .put("cause", l.cause.name).put("fix", l.fix).put("hits", l.hits).put("at", l.lastAt)
            )
        }
        file.writeText(a.toString())
    }

    /** Aynı belirti tekrar geldiyse çözümü günceller ve sayacı artırır; yoksa yeni ders ekler. */
    @Synchronized fun record(repo: String, signature: String, fix: String, at: Long = System.currentTimeMillis()): Lesson {
        val key = ErrorSig.normalize(signature).take(200)
        val cause = Cause.of(signature)
        val old = all().firstOrNull { it.repo.equals(repo, true) && (it.key == key || ErrorSig.similar(it.symptom, signature)) }
        val lesson = Lesson(repo, key, signature.lineSequence().firstOrNull()?.take(300).orEmpty(), cause, fix.take(800), (old?.hits ?: 0) + 1, at)
        val list = (all().filterNot { it.repo.equals(repo, true) && (it.key == key || ErrorSig.similar(it.symptom, signature)) } + lesson)
        save(list.sortedByDescending { it.lastAt }.take(MAX).sortedBy { it.lastAt })
        return lesson
    }

    /** Aynı repodan benzer dersler önce, sonra başka repolardaki ortak araç zinciri hataları. */
    fun lookup(repo: String, signature: String, k: Int = 2): List<Lesson> {
        val all = all().filter { ErrorSig.similar(it.symptom, signature) || it.key == ErrorSig.normalize(signature) }
        return (all.filter { it.repo.equals(repo, true) } + all.filterNot { it.repo.equals(repo, true) })
            .sortedByDescending { it.hits * 1000 + it.lastAt / 1_000_000 }.take(k)
    }

    fun hint(found: List<Lesson>): String? = if (found.isEmpty()) null else
        "CI KNOWLEDGE: this failed before; what fixed it:\n" +
            found.joinToString("\n") { "- (${it.repo}, ${it.cause.tr}, ${it.hits}x) ${it.fix.take(400)}" } +
            "\nVerify the cause matches this run before reusing the fix."

    /** Kullanıcıya/notes'a gösterilecek özet. */
    fun markdown(repo: String? = null, limit: Int = 20): String {
        val list = all().filter { repo == null || it.repo.equals(repo, true) }.sortedByDescending { it.lastAt }.take(limit)
        if (list.isEmpty()) return ""
        return list.joinToString("\n") { "- [${it.cause.tr}] ${it.repo}: ${it.symptom.take(100)} → ${it.fix.take(200)} (${it.hits}x)" }
    }

    @Synchronized fun forget(repo: String, signature: String): Boolean {
        val before = all()
        val after = before.filterNot { it.repo.equals(repo, true) && ErrorSig.similar(it.symptom, signature) }
        if (after.size == before.size) return false
        save(after); return true
    }

    companion object { const val MAX = 300 }
}
