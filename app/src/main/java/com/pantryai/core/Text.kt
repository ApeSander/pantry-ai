package com.pantryai.core

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

object Text {
    private val diacritics = Regex("\\p{Mn}+")
    private val nonWord = Regex("[^a-z0-9%' ]+")
    private val spaces = Regex("\\s+")

    /** Lowercase, strip accents and punctuation, collapse whitespace. "Crème Fraîche!" -> "creme fraiche" */
    fun norm(s: String): String {
        var t = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        t = diacritics.replace(t, "")
        t = t.replace('-', ' ').replace('/', ' ').replace('_', ' ')
        t = nonWord.replace(t, " ")
        t = t.replace("'", "")
        return spaces.replace(t, " ").trim()
    }

    fun tokens(s: String): List<String> = norm(s).split(' ').filter { it.isNotEmpty() }

    /** Optimal string alignment distance (Damerau-Levenshtein with adjacent transpositions). */
    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val n = a.length
        val m = b.length
        var prev2 = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = min(v, prev2[j - 2] + 1)
                }
                cur[j] = v
            }
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    /** 0..1 similarity based on edit distance. Short strings are punished harder for typos. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val len = max(a.length, b.length)
        if (len == 0) return 1.0
        val d = editDistance(a, b)
        // Allow ~1 typo per 5 chars before similarity collapses.
        return (1.0 - d.toDouble() / len).coerceAtLeast(0.0)
    }

    /** Naive singularisation for English + Dutch so "tomatoes", "tomaten", "eggs" line up. */
    fun stem(word: String): String {
        val w = word
        if (w.length <= 3) return w
        return when {
            w.endsWith("ies") && w.length > 4 -> w.dropLast(3) + "y"
            w.endsWith("oes") -> w.dropLast(2)
            w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes") -> w.dropLast(2)
            w.endsWith("'s") -> w.dropLast(2)
            w.endsWith("eren") && w.length > 5 -> w.dropLast(4) // eieren -> ei
            w.endsWith("en") && w.length > 5 -> w.dropLast(2)
            w.endsWith("ss") -> w
            w.endsWith("s") && !w.endsWith("us") && !w.endsWith("is") -> w.dropLast(1)
            else -> w
        }
    }

    fun stemPhrase(s: String): String = tokens(s).joinToString(" ") { stem(it) }

    fun titleCase(s: String): String = s.lowercase().split(' ').joinToString(" ") { w ->
        w.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    fun capitalize(s: String): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
