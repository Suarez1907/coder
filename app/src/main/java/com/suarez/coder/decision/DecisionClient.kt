package com.suarez.coder.decision

import com.suarez.coder.llm.Http
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One independent question for the Gemini Decisions API. Keys must match [A-Za-z0-9_-]{1,64}. */
data class DecisionQuestion(
    val key: String,
    /** "noul" (yes/no) or "choice". */
    val type: String,
    val instructions: String,
    /** Option key → description (choice), or optional "true"/"false" descriptions (noul). */
    val criteria: Map<String, String>? = null,
    /** Ordered rubric levels for "score" questions (level 0 first). */
    val levels: List<String>? = null,
)

data class DecisionAnswer(
    val type: String,
    /** Probability of "yes" for noul questions (0.01–0.99). */
    val noul: Double? = null,
    val choice: String? = null,
    /** Probability-weighted expected level for score questions. */
    val score: Double? = null,
    val probabilities: Map<String, Double> = emptyMap(),
    /** Probability that the top answer is right (0–1). */
    val confidence: Double,
)

data class DecisionResult(val answers: Map<String, DecisionAnswer>, val inputTokens: Long, val latencyMs: Long)

class DecisionException(message: String) : Exception(message)

/**
 * Client for the Gemini Decisions API (`POST v1beta/decisions`, model gemini-decision-1).
 * The model scores closed-set questions in one pass and bills no output tokens.
 */
class DecisionClient(
    private val apiKey: String,
    val model: String = DEFAULT_MODEL,
    private val timeoutMs: Long = 2_000,
) {
    companion object {
        const val DEFAULT_MODEL = "gemini-decision-1"
        private const val URL = "https://generativelanguage.googleapis.com/v1beta/decisions"
        private val KEY_RE = Regex("^[A-Za-z0-9_-]{1,64}$")

        fun buildBody(model: String, input: String, questions: List<DecisionQuestion>): JSONObject {
            require(questions.isNotEmpty() && questions.size <= 1000) { "1-1000 soru gerekli" }
            require(input.isNotBlank()) { "input boş olamaz" }
            val qs = JSONObject()
            for (q in questions) {
                require(KEY_RE.matches(q.key)) { "Geçersiz soru anahtarı: ${q.key}" }
                val o = JSONObject().put("type", q.type).put("instructions", q.instructions)
                q.criteria?.let { c -> o.put("criteria", JSONObject().also { j -> c.forEach { (k, v) -> j.put(k, v) } }) }
                q.levels?.let { l -> o.put("criteria", org.json.JSONArray(l)) }
                qs.put(q.key, o)
            }
            return JSONObject().put("model", model).put("input", input).put("questions", qs)
        }

        fun parse(body: String, latencyMs: Long): DecisionResult {
            val json = JSONObject(body)
            val answersJson = json.optJSONObject("answers") ?: throw DecisionException("Yanıtta 'answers' yok")
            val answers = LinkedHashMap<String, DecisionAnswer>()
            for (key in answersJson.keys()) {
                val a = answersJson.getJSONObject(key)
                val probs = LinkedHashMap<String, Double>()
                a.optJSONObject("probabilities")?.let { p -> p.keys().forEach { k -> probs[k] = p.getDouble(k) } }
                val noul = if (a.has("noul") && !a.isNull("noul")) a.getDouble("noul") else null
                val conf = when {
                    a.has("confidence") -> a.getDouble("confidence")
                    noul != null -> maxOf(noul, 1 - noul)
                    else -> probs.values.maxOrNull() ?: 0.0
                }
                answers[key] = DecisionAnswer(
                    type = a.optString("type"),
                    noul = noul,
                    choice = a.optString("choice").takeIf { a.has("choice") && it.isNotBlank() },
                    score = if (a.has("score") && !a.isNull("score")) a.getDouble("score") else null,
                    probabilities = probs,
                    confidence = conf,
                )
            }
            val tokens = json.optJSONObject("usage")?.optLong("total_input_tokens") ?: 0L
            return DecisionResult(answers, tokens, latencyMs)
        }

        fun errorMessage(body: String): String = runCatching {
            val e = JSONObject(body).getJSONObject("error")
            listOf(e.opt("code")?.toString(), e.optString("status").takeIf { it.isNotBlank() }, e.optString("message"))
                .filterNotNull().filter { it.isNotBlank() }.joinToString(": ")
        }.getOrDefault(body.take(300))
    }

    private val client = Http.client.newBuilder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    suspend fun decide(input: String, questions: List<DecisionQuestion>): DecisionResult {
        if (apiKey.isBlank()) throw DecisionException("Gemini API anahtarı yok")
        val body = buildBody(model, input, questions).toString()
        val req = Request.Builder().url(URL).header("x-goog-api-key", apiKey)
            .post(body.toRequestBody(Http.JSON)).build()
        val start = System.nanoTime()
        val res = withTimeout(timeoutMs + 250) { Http.execute(req, client) }
        val ms = (System.nanoTime() - start) / 1_000_000
        if (res.code == 429) throw DecisionException("kota aşıldı (429)")
        if (res.code !in 200..299) throw DecisionException("HTTP ${res.code}: ${errorMessage(res.body)}")
        return parse(res.body, ms)
    }
}
