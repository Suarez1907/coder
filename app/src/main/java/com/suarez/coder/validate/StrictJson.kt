package com.suarez.coder.validate

/**
 * Strict RFC 8259 JSON syntax check (Android's org.json accepts trailing commas, unquoted keys and trailing text,
 * which real tools like npm reject). Returns an error message or null.
 */
object StrictJson {
    private class P(val s: String) { var i = 0 }

    fun error(text: String): String? {
        val p = P(text)
        return try {
            ws(p); value(p); ws(p)
            if (p.i != p.s.length) fail(p, "beklenmeyen içerik") else null
        } catch (e: IllegalStateException) {
            e.message
        }
    }

    private fun fail(p: P, what: String): Nothing {
        val line = p.s.substring(0, p.i.coerceAtMost(p.s.length)).count { it == '\n' } + 1
        throw IllegalStateException("JSON geçersiz ($line. satır): $what")
    }

    private fun ws(p: P) { while (p.i < p.s.length && p.s[p.i] in " \t\r\n") p.i++ }

    private fun peek(p: P) = if (p.i < p.s.length) p.s[p.i] else '\u0000'

    private fun value(p: P) {
        when (val c = peek(p)) {
            '{' -> obj(p)
            '[' -> arr(p)
            '"' -> str(p)
            't' -> lit(p, "true")
            'f' -> lit(p, "false")
            'n' -> lit(p, "null")
            else -> if (c == '-' || c.isDigit()) num(p) else fail(p, "değer bekleniyordu")
        }
    }

    private fun lit(p: P, w: String) { if (p.s.startsWith(w, p.i)) p.i += w.length else fail(p, "'$w' bekleniyordu") }

    private fun obj(p: P) {
        p.i++; ws(p)
        if (peek(p) == '}') { p.i++; return }
        while (true) {
            ws(p)
            if (peek(p) != '"') fail(p, "tırnaklı anahtar bekleniyordu (sondaki virgül olabilir)")
            str(p); ws(p)
            if (peek(p) != ':') fail(p, "':' bekleniyordu")
            p.i++; ws(p); value(p); ws(p)
            when (peek(p)) {
                ',' -> p.i++
                '}' -> { p.i++; return }
                else -> fail(p, "',' veya '}' bekleniyordu")
            }
        }
    }

    private fun arr(p: P) {
        p.i++; ws(p)
        if (peek(p) == ']') { p.i++; return }
        while (true) {
            ws(p)
            if (peek(p) == ']') fail(p, "sondaki virgül")
            value(p); ws(p)
            when (peek(p)) {
                ',' -> p.i++
                ']' -> { p.i++; return }
                else -> fail(p, "',' veya ']' bekleniyordu")
            }
        }
    }

    private fun str(p: P) {
        p.i++
        while (p.i < p.s.length) {
            val c = p.s[p.i]
            when {
                c == '"' -> { p.i++; return }
                c == '\\' -> {
                    val n = p.s.getOrNull(p.i + 1) ?: fail(p, "yarım kaçış dizisi")
                    if (n == 'u') {
                        if (p.i + 6 > p.s.length || !p.s.substring(p.i + 2, p.i + 6).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) fail(p, "geçersiz \\u")
                        p.i += 6
                    } else if (n in "\"\\/bfnrt") p.i += 2 else fail(p, "geçersiz kaçış dizisi")
                }
                c < ' ' -> fail(p, "dizgi içinde kontrol karakteri")
                else -> p.i++
            }
        }
        fail(p, "kapanmayan dizgi")
    }

    private fun num(p: P) {
        val m = Regex("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?").find(p.s, p.i)
        if (m == null || m.range.first != p.i) fail(p, "geçersiz sayı")
        p.i = m.range.last + 1
    }
}
