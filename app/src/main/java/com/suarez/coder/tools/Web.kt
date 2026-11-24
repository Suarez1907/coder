package com.suarez.coder.tools

import com.suarez.coder.llm.Http
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

class Web {
    private val ua = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    suspend fun search(query: String, max: Int = 8): String {
        val results = ArrayList<Triple<String, String, String>>()
        val form = FormBody.Builder().add("q", query).build()
        val r = Http.execute(Request.Builder().url("https://html.duckduckgo.com/html/").header("User-Agent", ua).post(form).build())
        if (r.code in 200..299) {
            val doc = Jsoup.parse(r.body)
            for (el in doc.select("div.result")) {
                val a = el.selectFirst("a.result__a") ?: continue
                val url = unwrap(a.attr("href"))
                if (url.contains("duckduckgo.com/y.js")) continue // ads
                results += Triple(a.text(), url, el.selectFirst(".result__snippet")?.text() ?: "")
                if (results.size >= max) break
            }
        }
        if (results.isEmpty()) {
            val lite = Http.execute(
                Request.Builder().url("https://lite.duckduckgo.com/lite/?q=" + URLEncoder.encode(query, "UTF-8"))
                    .header("User-Agent", ua).get().build()
            )
            if (lite.code in 200..299) {
                val doc = Jsoup.parse(lite.body)
                val snippets = doc.select("td.result-snippet").map { it.text() }
                doc.select("a.result-link").forEachIndexed { i, a ->
                    if (results.size < max) results += Triple(a.text(), unwrap(a.attr("href")), snippets.getOrNull(i) ?: "")
                }
            }
        }
        if (results.isEmpty()) return "Sonuç bulunamadı (arama servisi yanıt vermemiş olabilir). Bilinen bir adresi web_fetch ile doğrudan açmayı dene."
        return results.mapIndexed { i, (title, url, snip) -> "${i + 1}. $title\n   $url\n   $snip" }.joinToString("\n")
    }

    private fun unwrap(href: String): String {
        val h = if (href.startsWith("//")) "https:$href" else href
        val idx = h.indexOf("uddg=")
        if (idx < 0) return h
        val enc = h.substring(idx + 5).substringBefore('&')
        return URLDecoder.decode(enc, "UTF-8")
    }

    private val noRedirects = Http.client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun fetch(url: String, start: Int = 0, maxChars: Int = 20_000, query: String? = null): String {
        var u = if (url.startsWith("http")) url else "https://$url"
        // No requests into the phone's own network, not even through a redirect (SSRF).
        var r: Http.Result
        var hops = 0
        while (true) {
            val target = u
            if (kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.suarez.coder.security.Guard.isPrivateUrl(target) }) throw ToolException("Yerel ağ adreslerine erişim engellendi: $u")
            r = Http.execute(Request.Builder().url(u).header("User-Agent", ua).get().build(), noRedirects)
            val next = r.location?.takeIf { r.code in 300..399 } ?: break
            if (++hops > 5) throw ToolException("Çok fazla yönlendirme: $url")
            u = java.net.URI(u).resolve(next).toString()
        }
        val type = r.contentType ?: ""
        val text = if (type.contains("html") || r.body.trimStart().startsWith("<!") || r.body.trimStart().startsWith("<html", true)) {
            val doc = Jsoup.parse(r.body, u)
            doc.select("script,style,noscript,svg,iframe,nav,footer,header form").remove()
            doc.select("br,p,div,li,h1,h2,h3,h4,h5,h6,tr,pre,section,article,blockquote").forEach { it.prepend("\\n") }
            val title = doc.title()
            val body = doc.body()?.text()?.replace("\\n", "\n") ?: ""
            val cleaned = body.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            (if (title.isNotBlank()) "# $title\n\n" else "") + cleaned
        } else r.body
        if (!query.isNullOrBlank() && start == 0 && text.length > 6_000) {
            val focused = WebFocus.select(text, query)
            return "HTTP ${r.code} $u (sorguya göre seçilmiş bölümler, sayfa ${text.length} karakter; tamamı için query olmadan start ile oku)\n\n$focused"
        }
        val s = start.coerceIn(0, text.length)
        val e = (s + maxChars).coerceAtMost(text.length)
        val more = if (e < text.length) "\n\n... (devamı var: toplam ${text.length} karakter; devam için start=$e kullan)" else ""
        return "HTTP ${r.code} $u\n\n" + text.substring(s, e) + more
    }
}
