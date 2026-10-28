package com.pantryai.core

data class ParsedLine(
    val raw: String,
    val amount: Double?,
    val unit: String,
    val size: String?,
    val name: String,
    val note: String,
    val optional: Boolean,
)

/**
 * Deterministic parser for ingredient lines in English and Dutch.
 * "2 1/2 cups (300 g) all-purpose flour, sifted" -> 300 g "all-purpose flour" (note: sifted)
 * "3 teentjes knoflook, geperst" -> 3 clove "knoflook"
 * "1 blik tomatenblokjes (400 g)" -> 400 g "tomatenblokjes"
 * "2 large eggs" -> 2 pc size L "eggs"
 */
object IngredientParser {
    private val unicodeFractions = mapOf(
        '½' to "1/2", '⅓' to "1/3", '⅔' to "2/3", '¼' to "1/4", '¾' to "3/4", '⅕' to "1/5",
        '⅛' to "1/8", '⅜' to "3/8", '⅝' to "5/8", '⅞' to "7/8", '⅙' to "1/6",
    )

    private val sizeWords = mapOf(
        "small" to "S", "klein" to "S", "kleine" to "S", "kleintje" to "S",
        "medium" to "M", "middelgroot" to "M", "middelgrote" to "M", "middel" to "M", "normale" to "M",
        "large" to "L", "big" to "L", "groot" to "L", "grote" to "L", "flinke" to "L", "dikke" to "L",
        "extra large" to "XL", "extra-large" to "XL", "xl" to "XL", "jumbo" to "XL", "extra grote" to "XL", "zeer grote" to "XL",
    )

    private val wordNumbers = mapOf(
        "a" to 1.0, "an" to 1.0, "one" to 1.0, "een" to 1.0, "één" to 1.0, "two" to 2.0, "twee" to 2.0,
        "three" to 3.0, "drie" to 3.0, "four" to 4.0, "vier" to 4.0, "five" to 5.0, "vijf" to 5.0,
        "six" to 6.0, "zes" to 6.0, "half" to 0.5, "halve" to 0.5, "a half" to 0.5, "a couple" to 2.0,
        "couple" to 2.0, "paar" to 2.0, "dozen" to 12.0, "anderhalf" to 1.5, "anderhalve" to 1.5,
        "seven" to 7.0, "zeven" to 7.0, "eight" to 8.0, "acht" to 8.0, "ten" to 10.0, "tien" to 10.0, "twaalf" to 12.0, "twelve" to 12.0,
    )

    private val toTaste = Regex(
        "\\b(to taste|naar smaak|optional|optioneel|as needed|indien nodig|for serving|om te serveren|voor erbij|for garnish|ter garnering|garnish|snufje|pinch of|some|wat|beetje)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val numberRe = Regex("^(\\d+\\s+\\d+/\\d+|\\d+/\\d+|\\d+(?:[.,]\\d+)?)(\\s*(?:-|–|to|tot|a|à|of)\\s*(\\d+(?:[.,]\\d+)?|\\d+/\\d+))?")
    private val glued = Regex("^(\\d+(?:[.,]\\d+)?)([a-zA-Z]+)\\b(.*)$")
    private val parenQty = Regex("\\(([^)]*)\\)")
    private val bullet = Regex("^[\\s•·*▢☐□◦‣⁃●○✓✔-]+")

    fun num(s: String): Double? {
        val t = s.trim().replace(',', '.')
        if (t.contains(' ') && t.contains('/')) {
            val (w, f) = t.split(Regex("\\s+"), 2)
            return (w.toDoubleOrNull() ?: return null) + (num(f) ?: return null)
        }
        if (t.contains('/')) {
            val (a, b) = t.split('/', limit = 2)
            val bb = b.toDoubleOrNull() ?: return null
            if (bb == 0.0) return null
            return (a.toDoubleOrNull() ?: return null) / bb
        }
        return t.toDoubleOrNull()
    }

    private fun normalizeFractions(s: String): String {
        val sb = StringBuilder()
        for ((i, c) in s.withIndex()) {
            val f = unicodeFractions[c]
            if (f != null) {
                if (i > 0 && s[i - 1].isDigit()) sb.append(' ')
                sb.append(f)
            } else sb.append(c)
        }
        return sb.toString().replace('⁄', '/')
    }

    /** Pull a leading quantity "2", "1 1/2", "2-3", "1,5" -> (amount, rest). Ranges take the upper bound. */
    private fun leadingAmount(s: String): Pair<Double?, String> {
        val t = s.trimStart()
        val m = numberRe.find(t)
        if (m != null) {
            val a = num(m.groupValues[1])
            val b = m.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let { num(it) }
            val amount = if (b != null && a != null && b > a) b else a
            return amount to t.substring(m.range.last + 1)
        }
        val lower = t.lowercase()
        for ((w, v) in wordNumbers.entries.sortedByDescending { it.key.length }) {
            if (lower.startsWith("$w ")) return v to t.substring(w.length)
        }
        return null to t
    }

    private fun takeUnit(s: String): Pair<String?, String> {
        val t = s.trimStart().trimStart('.')
        val words = t.split(Regex("\\s+"))
        if (words.isEmpty()) return null to t
        // two-word units first ("fl oz", "flinke scheut")
        if (words.size >= 2) {
            val two = (words[0] + " " + words[1]).lowercase().trimEnd('.', ',')
            Units.parse(two)?.let { return it to words.drop(2).joinToString(" ") }
        }
        val one = words[0].lowercase().trimEnd('.', ',', ':')
        Units.parse(one)?.let { return it to words.drop(1).joinToString(" ") }
        return null to t
    }

    private fun takeSize(s: String): Pair<String?, String> {
        val t = s.trimStart()
        val lower = t.lowercase()
        for ((w, v) in sizeWords.entries.sortedByDescending { it.key.length }) {
            if (lower.startsWith("$w ")) return v to t.substring(w.length).trimStart()
        }
        return null to t
    }

    fun parse(rawLine: String): ParsedLine {
        val raw = rawLine.trim()
        var s = bullet.replace(normalizeFractions(raw), "").trim()
        val optional = toTaste.containsMatchIn(s)

        // Parentheses: keep a mass/volume inside as an alternative quantity: "1 can (400 g)".
        var alt: Triple<Double, String, String>? = null
        val notes = mutableListOf<String>()
        for (m in parenQty.findAll(s)) {
            val inner = m.groupValues[1].trim()
            val (a, rest) = leadingAmount(inner)
            if (a != null) {
                val g = glued.find(inner)
                val (u, _) = if (g != null) Units.parse(g.groupValues[2])?.let { it to g.groupValues[3] } ?: takeUnit(rest) else takeUnit(rest)
                if (u != null && Units.dim(u).let { it == Dim.MASS || it == Dim.VOLUME }) {
                    alt = Triple(if (g != null) num(g.groupValues[1]) ?: a else a, u, inner); continue
                }
            }
            if (inner.isNotEmpty()) notes += inner
        }
        s = parenQty.replace(s, " ").replace(Regex("\\s+"), " ").trim()

        // Prep notes after a comma: "knoflook, fijngehakt"
        val comma = s.indexOf(',')
        if (comma > 0) {
            val after = s.substring(comma + 1).trim()
            if (after.isNotEmpty()) notes += after
            s = s.substring(0, comma).trim()
        }

        // Glued numbers "200g", "1kg", "2el"
        glued.find(s)?.let { g ->
            if (Units.parse(g.groupValues[2]) != null) s = g.groupValues[1] + " " + g.groupValues[2] + g.groupValues[3]
        }

        var (amount, rest) = leadingAmount(s)
        // "2 x 400 g" multipliers
        val mult = Regex("^\\s*[x×]\\s*(\\d+(?:[.,]\\d+)?)").find(rest)
        if (amount != null && mult != null) {
            amount *= num(mult.groupValues[1]) ?: 1.0
            rest = rest.substring(mult.range.last + 1)
        }
        var (size, r2) = takeSize(rest)
        var (unit, r3) = takeUnit(r2)
        if (size == null) { val (sz, r4) = takeSize(r3); size = sz; r3 = r4 }
        // "of"/"van" after unit: "2 cups of flour", "1 blik van 400 g"
        var name = r3.trim().replace(Regex("^(of|van|de|het)\\s+", RegexOption.IGNORE_CASE), "").trim()

        // If what we took as a unit was really the food ("1 brood", "2 tomaten")
        if (name.isEmpty() && unit != null) { name = r2.trim(); unit = null }

        // Words as amounts after the unit ("a pinch of salt")
        if (amount == null && unit != null && unit !in setOf("pinch", "dash", "splash")) amount = 1.0
        if (amount == null && unit in setOf("pinch", "dash", "splash")) amount = 1.0

        var finalUnit = unit ?: "pc"
        var finalAmount = amount

        // Prefer an explicit mass/volume from parentheses for package-ish units: "1 can (400 g) tomatoes"
        alt?.let { (a, u, _) ->
            if (unit == null || Units.dim(finalUnit) == Dim.NAMED || Units.dim(finalUnit) == Dim.COUNT || unit == "cup") {
                finalAmount = a * (if (unit != null && Units.dim(unit) == Dim.NAMED) (amount ?: 1.0) else 1.0)
                finalUnit = u
            }
        }

        // Clean trailing junk like "for the sauce:" etc.
        name = name.replace(Regex("\\s+(for|voor)\\s+(the|de|het)\\s+.*$", RegexOption.IGNORE_CASE), "").trim().trimEnd(':', '.', ';')
        if (name.isEmpty()) name = raw

        return ParsedLine(raw, finalAmount, finalUnit, size, name, notes.joinToString(", "), optional && finalAmount == null || optional && unit == "pinch")
    }

    /** Heuristic: does this text line look like an ingredient line? */
    fun looksLikeIngredient(line: String): Boolean {
        val t = normalizeFractions(line.trim()).let { bullet.replace(it, "") }.trim()
        if (t.length < 2 || t.length > 120) return false
        if (t.endsWith(":")) return false
        if (Regex("^\\d+[.)]\\s").containsMatchIn(t) && t.split(' ').size > 10) return false // numbered step
        val (a, rest) = leadingAmount(t)
        if (a != null && rest.isNotBlank()) return true
        val words = t.split(' ').size
        return words <= 5 && toTaste.containsMatchIn(t)
    }
}
