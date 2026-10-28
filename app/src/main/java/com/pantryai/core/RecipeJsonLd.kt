package com.pantryai.core

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class ScrapedRecipe(val title: String, val servings: Int?, val ingredients: List<String>, val steps: String)

/** Finds a schema.org Recipe in a web page (JSON-LD), which almost every recipe site publishes. */
object RecipeJsonLd {
    private val scriptRe = Regex("<script[^>]*application/ld\\+json[^>]*>(.*?)</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val tagRe = Regex("<[^>]+>")

    fun decodeEntities(s: String): String = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#039;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&frac12;", "½").replace("&frac14;", "¼")
        .replace("&frac34;", "¾").replace("&eacute;", "é").replace("&euml;", "ë").replace("&iuml;", "ï").replace("&egrave;", "è")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: "" }

    fun clean(s: String) = decodeEntities(tagRe.replace(s, " ")).replace(Regex("\\s+"), " ").trim()

    fun extract(html: String): ScrapedRecipe? {
        for (m in scriptRe.findAll(html)) {
            val json = m.groupValues[1].trim()
            val v = runCatching { JSONTokener(json).nextValue() }.getOrNull() ?: continue
            findRecipe(v)?.let { return toRecipe(it) }
        }
        return null
    }

    private fun isRecipe(o: JSONObject): Boolean {
        val t = o.opt("@type") ?: return false
        return when (t) {
            is String -> t.equals("Recipe", true)
            is JSONArray -> (0 until t.length()).any { t.optString(it).equals("Recipe", true) }
            else -> false
        }
    }

    private fun findRecipe(v: Any?): JSONObject? = when (v) {
        is JSONObject -> when {
            isRecipe(v) -> v
            else -> findRecipe(v.opt("@graph")) ?: findRecipe(v.opt("mainEntity"))
        }
        is JSONArray -> (0 until v.length()).asSequence().mapNotNull { findRecipe(v.opt(it)) }.firstOrNull()
        else -> null
    }

    private fun toRecipe(o: JSONObject): ScrapedRecipe {
        val title = clean(o.optString("name", "Recipe"))
        val ings = mutableListOf<String>()
        (o.opt("recipeIngredient") ?: o.opt("ingredients"))?.let { v ->
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) clean(v.optString(i)).takeIf { it.isNotEmpty() }?.let { ings += it }
                is String -> ings += v.split('\n').map { clean(it) }.filter { it.isNotEmpty() }
            }
        }
        val servings = when (val y = o.opt("recipeYield")) {
            is Number -> y.toInt()
            is String -> Regex("\\d+").find(y)?.value?.toIntOrNull()
            is JSONArray -> (0 until y.length()).firstNotNullOfOrNull { Regex("\\d+").find(y.optString(it))?.value?.toIntOrNull() }
            else -> null
        }
        val steps = mutableListOf<String>()
        fun walk(v: Any?) {
            when (v) {
                is String -> v.split('\n').map { clean(it) }.filter { it.isNotEmpty() }.forEach { steps += it }
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
                is JSONObject -> when {
                    v.has("itemListElement") -> walk(v.opt("itemListElement"))
                    v.has("text") -> walk(v.optString("text"))
                    v.has("name") -> walk(v.optString("name"))
                }
            }
        }
        walk(o.opt("recipeInstructions"))
        return ScrapedRecipe(title, servings, ings, steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"))
    }

    /** Fallback: visible text of a page for the AI extractor. */
    fun visibleText(html: String): String {
        var t = html.replace(Regex("<(script|style|noscript|svg)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
        t = t.replace(Regex("<(br|/p|/li|/h\\d|/div|/tr)[^>]*>", RegexOption.IGNORE_CASE), "\n")
        t = decodeEntities(tagRe.replace(t, " "))
        return t.lines().map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    fun title(html: String): String? = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        .find(html)?.groupValues?.get(1)?.let { clean(it) }?.substringBefore(" | ")?.substringBefore(" - ")?.takeIf { it.isNotBlank() }
}
