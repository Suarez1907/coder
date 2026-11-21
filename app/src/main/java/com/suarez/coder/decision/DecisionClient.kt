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

data class DecisionResult(
    val answers: Map<String, DecisionAnswer>,
    val inputTokens: Long,
    val latencyMs: Long,
    /** Real USD cost when the API reports it (OpenRouter usage.cost). */
    val costUsd: Double? = null,
    /** Decision model that answered, e.g. gemini-decision-1 or typesafe/jev-1.14. */
    val model: String? = null,
)

/** Where closed-set routing questions are sent. */
enum class DecisionProvider(val label: String) {
    GEMINI("Google (Gemini Decisions)"),
    OPENROUTER("OpenRouter (Jev / Decisions)"),
}

class DecisionException(message: String) : Exception(message)

/**
 * Client for the Decisions APIs: Gemini (`POST v1beta/decisions`, gemini-decision-1) and OpenRouter
 * (`POST api/alpha/decisions`, e.g. typesafe/jev-1.14). Both score closed-set questions in one pass and bill
 * (almost) only input tokens. The request shapes match except that OpenRouter calls the input `state`.
 */
class DecisionClient(
    private val apiKey: String,
    val model: String = DEFAULT_MODEL,
    private val timeoutMs: Long = 2_000,
    val provider: DecisionProvider = DecisionProvider.GEMINI,
    /** Visible for tests. */
    private val url: String = if (provider == DecisionProvider.GEMINI) URL else OPENROUTER_URL,
) {
    companion object {
        const val DEFAULT_MODEL = "gemini-decision-1"
        const val OPENROUTER_DEFAULT_MODEL = "typesafe/jev-1.14"
        private const val URL = "https://generativelanguage.googleapis.com/v1beta/decisions"
        const val OPENROUTER_URL = "https://openrouter.ai/api/alpha/decisions"
        private val KEY_RE = Regex("^[A-Za-z0-9_-]{1,64}$")

        /**
         * Jev answers quickly only while the state stays small (about 32 KB); longer text switches it to a slow
         * long-context mode. Request first, then the file list, then recent chat: the tail is cut, never the request.
         */
        const val MAX_STATE_CHARS = 24_000

        fun compactState(input: String): String =
            if (input.length <= MAX_STATE_CHARS) input
            else input.take(MAX_STATE_CHARS - 40).substringBeforeLast('\n') + "\n…(kısaltıldı)"

        fun buildBody(model: String, input: String, questions: List<DecisionQuestion>, provider: DecisionProvider = DecisionProvider.GEMINI): JSONObject {
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
            return if (provider == DecisionProvider.OPENROUTER)
                JSONObject().put("model", model).put("state", compactState(input)).put("questions", qs)
            else JSONObject().put("model", model).put("input", input).put("questions", qs)
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
            val usage = json.optJSONObject("usage")
            // Gemini: total_input_tokens; OpenRouter: input_tokens and the real cost in USD.
            val tokens = usage?.let { u -> if (u.has("total_input_tokens")) u.optLong("total_input_tokens") else u.optLong("input_tokens") } ?: 0L
            val cost = usage?.takeIf { it.has("cost") && !it.isNull("cost") }?.optDouble("cost")?.takeIf { !it.isNaN() && it >= 0 }
            return DecisionResult(answers, tokens, latencyMs, cost, json.optString("model").ifBlank { null })
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
        if (apiKey.isBlank()) throw DecisionException("${provider.label}: API anahtarı yok")
        val body = buildBody(model, input, questions, provider).toString()
        val builder = Request.Builder().url(url).post(body.toRequestBody(Http.JSON))
        val req = when (provider) {
            DecisionProvider.GEMINI -> builder.header("x-goog-api-key", apiKey)
            DecisionProvider.OPENROUTER -> builder.header("Authorization", "Bearer $apiKey").header("X-Title", "Coder")
        }.build()
        val start = System.nanoTime()
        val res = withTimeout(timeoutMs + 250) { Http.execute(req, client) }
        val ms = (System.nanoTime() - start) / 1_000_000
        if (res.code == 429) throw DecisionException("kota aşıldı (429)")
        if (res.code !in 200..299) throw DecisionException("HTTP ${res.code}: ${errorMessage(res.body)}")
        val result = parse(res.body, ms)
        return result.copy(model = result.model ?: model)
    }
}

/**
 * Tries decision providers in order; a quota, HTTP, network or timeout failure moves to the next one at once.
 * When all fail the last error is thrown, and the caller falls back to the cheap classifier and local rules.
 */
class DecisionChain(private val steps: List<Pair<String, suspend (String, List<DecisionQuestion>) -> DecisionResult>>) {
    /** Failures of earlier providers in the last call (shown in the route info). */
    var skipped: List<String> = emptyList()
        private set

    suspend fun decide(input: String, questions: List<DecisionQuestion>): DecisionResult {
        if (steps.isEmpty()) throw DecisionException("karar sağlayıcısı yok (API anahtarı girilmemiş)")
        val errors = ArrayList<String>()
        for ((label, step) in steps) {
            try {
                return step(input, questions).also { skipped = errors.toList() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                errors += "$label: zaman aşımı"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "$label: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        skipped = errors
        throw DecisionException(errors.joinToString("; "))
    }
}
