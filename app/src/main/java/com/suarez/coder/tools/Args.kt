package com.suarez.coder.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * Lenient readers for tool arguments. Models sometimes flatten arrays: a list may arrive as a real array,
 * as a JSON string, as a comma/newline separated string, or as objects with a "path" field.
 */
object Args {
    /** Elements of an array argument (JSONObject or String), also when the array was sent as a JSON string. */
    fun elements(a: JSONObject, key: String): List<Any>? {
        val v = a.opt(key) ?: return null
        val arr = when (v) {
            is JSONArray -> v
            is String -> {
                val s = v.trim()
                when {
                    s.startsWith("[") -> runCatching { JSONArray(s) }.getOrNull()
                    s.startsWith("{") -> runCatching { JSONArray().put(JSONObject(s)) }.getOrNull()
                    s.isNotEmpty() -> JSONArray().put(s)
                    else -> null
                }
            }
            is JSONObject -> JSONArray().put(v)
            else -> null
        } ?: return null
        return (0 until arr.length()).mapNotNull { arr.opt(it)?.takeIf { e -> e != JSONObject.NULL } }
    }

    /** A list of strings (e.g. file paths). */
    fun stringList(a: JSONObject, key: String): List<String>? {
        val els = elements(a, key) ?: return null
        return els.flatMap { e ->
            when (e) {
                is JSONObject -> listOfNotNull((e.optString("path").ifBlank { e.optString("file") }).takeIf { it.isNotBlank() })
                is String -> e.split(Regex("[,\\n]"))
                else -> listOf(e.toString())
            }
        }.map { it.trim().trim('"', '`', '\'').removePrefix("./").removePrefix("/") }.filter { it.isNotEmpty() }.distinct()
    }

    private val noExtensionFiles = setOf("Makefile", "Dockerfile", "LICENSE", "Procfile", "Gemfile", "Rakefile", "gradlew", "Jenkinsfile")

    /** Whether a string plausibly names a file in a repository. */
    fun looksLikePath(p: String): Boolean {
        if (p.isBlank() || p.length > 300 || p.any { it.isWhitespace() }) return false
        val name = p.substringAfterLast('/')
        return p.contains('/') || name.contains('.') || name in noExtensionFiles
    }
}
