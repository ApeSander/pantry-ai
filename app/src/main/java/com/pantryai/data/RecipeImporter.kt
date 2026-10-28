package com.pantryai.data

import com.pantryai.core.RecipeJsonLd
import com.pantryai.core.ScrapedRecipe
import java.net.HttpURLConnection
import java.net.URL

object RecipeImporter {
    data class Page(val html: String, val finalUrl: String)

    fun fetch(url: String): Page {
        var current = url.trim().let { if (!it.startsWith("http")) "https://$it" else it }
        repeat(5) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36")
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            c.setRequestProperty("Accept-Language", "nl-NL,nl;q=0.9,en;q=0.8")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: error("Redirect without location")
                current = URL(URL(current), loc).toString()
                c.disconnect()
                return@repeat
            }
            if (code !in 200..299) error("The site answered with HTTP $code")
            val bytes = c.inputStream.use { it.readNBytesCompat(4_000_000) }
            c.disconnect()
            return Page(String(bytes, Charsets.UTF_8), current)
        }
        error("Too many redirects")
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16384)
        while (out.size() < max) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Structured data if the site has it, otherwise null (caller falls back to AI on page text). */
    fun structured(page: Page): ScrapedRecipe? = RecipeJsonLd.extract(page.html)
}
