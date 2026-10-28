package com.pantryai.core

/** One product line from a receipt, before matching against the knowledge base. */
data class ReceiptLine(
    val raw: String,
    /** Cleaned product text, e.g. "Halfvolle Melk" */
    val product: String,
    /** How many packages were bought (from "2 X 1,29" or a leading "2"). */
    val count: Int,
    /** Package size if the receipt states it ("500G", "1L", "10ST"); null if unclear. */
    val packAmount: Double?,
    val packUnit: String?,
    /** Weighed items ("0,754 kg") are absolute amounts, not per package. */
    val weighed: Boolean,
    val price: Double?,
)

object ReceiptParser {
    private val skip = Regex(
        "(totaal|subtotaal|omschrijving|bedrag|aantal|sub totaal|te betalen|betaald|pinnen|pin\\b|pinbetaling|contant|wisselgeld|retour|btw|b\\.t\\.w|" +
            "bonnummer|kassa|kassabon|filiaal|datum|tijd|bedankt|tot ziens|klant|bonuskaart|airmiles|air miles|statiegeld|" +
            "emballage|korting|uw voordeel|voordeel|aantal artikelen|artikelen|terminal|merchant|autorisatie|kaartnummer|" +
            "maestro|v pay|vpay|mastercard|visa|contactloos|transactie|leesmethode|www\\.|\\.nl\\b|\\.com\\b|telefoon|tel\\.|" +
            "openingstijden|bezoek|kvk|btw-nr|spaarzegel|zegels|koopzegels|bonus box|bonusbox|persoonlijke bonus|actie|" +
            "eur\\b|euro\\b|saldo|change|total|subtotal|cash|card|thank you|receipt|vat\\b|sum\\b|bon\\b|sticker|" +
            "plastic tas|draagtas|tasje|boodschappentas|\\btas\\b|statiegeldbon|lege flessen|loyalty|prijs per kg)",
        RegexOption.IGNORE_CASE,
    )
    private val priceAtEnd = Regex("\\s(-?\\d{1,4}[.,]\\d{2})\\s*[A-Za-z*]{0,2}\\s*$")
    private val multiplierOnly = Regex("^\\s*(\\d+)\\s*[xX×*]\\s*(\\d+[.,]\\d{2})\\s*(\\d+[.,]\\d{2})?\\s*[A-Z]?\\s*$")
    private val leadingCount = Regex("^\\s*(\\d{1,2})\\s*[xX×]?\\s+(?=[A-Za-z])")
    private val inlineMult = Regex("\\s(\\d{1,2})\\s*[xX×]\\s*(\\d+[.,]\\d{2})")
    private val weightLine = Regex("(\\d+[.,]\\d{1,3})\\s*(kg|KG|Kg)\\b")
    private val sizeRe = Regex(
        "(\\d+(?:[.,]\\d+)?)\\s*(KG|KILO|GRAM|GR|G|LITER|LTR|L|ML|CL|ST|STK|STUKS|PCS|PC|STUK|ROL|X)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val packOf = Regex("\\b(\\d{1,2})\\s*[-]?\\s*(PACK|PAK|PK|TRAY)\\b", RegexOption.IGNORE_CASE)
    private val brands = listOf(
        "albert heijn", "ah biologisch", "ah basic", "ah excellent", "ah terra", "ah", "jumbo", "jumbo's", "plus", "lidl", "aldi",
        "dirk", "g'woon", "gwoon", "perfekt", "euroshopper", "coop", "spar", "vomar", "hoogvliet", "deka", "dekamarkt", "poiesz",
        "milbona", "chef select", "freshona", "combino", "bonus", "biologisch", "bio", "1 de beste", "1 de beste", "ekoplaza",
        "picnic", "crisp", "boni", "nettorama", "ah 1 de", "huismerk", "lekker", "ah snack", "ah kip",
    ).sortedByDescending { it.length }
    /** Common Dutch receipt abbreviations -> full words. */
    private val abbreviations = mapOf(
        "hv" to "halfvolle", "halfv" to "halfvolle", "vl" to "volle", "mg" to "magere", "volk" to "volkoren", "vk" to "volkoren",
        "bl" to "blokjes", "kipf" to "kipfilet", "kipfil" to "kipfilet", "rundg" to "rundergehakt", "gehkt" to "gehakt",
        "hoh" to "half om half", "aardapp" to "aardappelen", "aardap" to "aardappelen", "krop" to "kropsla", "champ" to "champignons",
        "tom" to "tomaten", "trostom" to "trostomaten", "kom" to "komkommer", "pap" to "paprika", "sinaasap" to "sinaasappels",
        "gesn" to "gesneden", "gerasp" to "geraspte", "jb" to "jong belegen", "bel" to "belegen", "gem" to "gemalen",
        "yogh" to "yoghurt", "yoghrt" to "yoghurt", "rmboter" to "roomboter", "rmbot" to "roomboter", "slagr" to "slagroom",
        "eier" to "eieren", "scharr" to "scharrel", "brd" to "brood", "zalmf" to "zalmfilet", "spagh" to "spaghetti",
        "uien" to "uien", "knofl" to "knoflook", "pr" to "prei", "bloemk" to "bloemkool", "broc" to "broccoli",
        "pindak" to "pindakaas", "kaasplk" to "kaasplakken", "ontb" to "ontbijt", "olijfol" to "olijfolie",
    )

    fun price(s: String): Double? = s.replace(',', '.').toDoubleOrNull()

    /** Strip store brands, abbreviations and package sizes from a product line. */
    fun cleanProduct(s: String): String {
        var t = " " + s.lowercase().replace(Regex("[*#]+"), " ") + " "
        for (b in brands) t = t.replace(" $b ", " ")
        t = sizeRe.replace(t, " ")
        t = packOf.replace(t, " ")
        t = t.replace(Regex("\\d+%"), " ")
        t = t.replace(Regex("[^\\p{L}' ]"), " ")
        val words = t.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { abbreviations[it] ?: it }
        return words.joinToString(" ").trim()
    }

    fun parse(lines: List<String>): List<ReceiptLine> {
        val out = mutableListOf<ReceiptLine>()
        var started = false
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            // "2 X 1,29  2,58" on its own line applies to the previous product.
            val multOnly = multiplierOnly.find(line)
            if (multOnly != null) {
                val n = multOnly.groupValues[1].toInt()
                if (out.isNotEmpty() && n in 1..50) { val last = out.removeAt(out.lastIndex); out += last.copy(count = n) }
                continue
            }
            // "0,754 kg x 1,99 /kg" weight line for the previous product
            val wOnly = weightLine.find(line)
            if (wOnly != null && out.isNotEmpty() && cleanProduct(line).replace(Regex("\\b(kg|eur|x|per)\\b"), "").isBlank()) {
                val kg = price(wOnly.groupValues[1]) ?: continue
                val last = out.removeAt(out.lastIndex)
                out += last.copy(packAmount = kg * 1000, packUnit = "g", weighed = true, count = 1)
                continue
            }
            if (skip.containsMatchIn(line)) {
                // Everything after the total is payment info.
                if (started && Regex("^(totaal|total|te betalen|subtotaal)", RegexOption.IGNORE_CASE).containsMatchIn(line)) break
                continue
            }
            val pm = priceAtEnd.find(" $line")
            val price = pm?.let { price(it.groupValues[1]) }
            if (price != null && price < 0) continue // discount line
            var body = if (pm != null) (" $line").substring(0, pm.range.first).trim() else line
            if (pm == null && !body.any { it.isLetter() }) continue
            if (body.count { it.isLetter() } < 3) continue

            var count = 1
            inlineMult.find(" $body")?.let { m ->
                count = m.groupValues[1].toInt()
                body = (" $body").replace(m.value, " ").trim()
            }
            leadingCount.find(body)?.let { m ->
                val n = m.groupValues[1].toInt()
                // "10 EIEREN" is a pack size, not 10 packs; only accept small multipliers when followed by more text
                if (n in 1..9) { count = n; body = body.substring(m.range.last + 1).trim() }
            }

            var packAmount: Double? = null
            var packUnit: String? = null
            var weighed = false
            weightLine.find(body)?.let { w ->
                packAmount = (price(w.groupValues[1]) ?: 0.0) * 1000; packUnit = "g"; weighed = true
            }
            if (packAmount == null) {
                sizeRe.findAll(body).lastOrNull()?.let { m ->
                    val a = price(m.groupValues[1]) ?: return@let
                    val u = when (m.groupValues[2].uppercase()) {
                        "KG", "KILO" -> "kg"; "G", "GR", "GRAM" -> "g"; "L", "LTR", "LITER" -> "l"
                        "ML" -> "ml"; "CL" -> "cl"; "ROL" -> "pc"; else -> "pc"
                    }
                    // "2X" inside a name is a multipack count
                    if (m.groupValues[2].equals("X", true)) { count *= a.toInt().coerceIn(1, 24) } else { packAmount = a; packUnit = u }
                }
            }
            if (packAmount == null) packOf.find(body)?.let { m -> packAmount = m.groupValues[1].toDouble(); packUnit = "pc" }

            val product = cleanProduct(body)
            if (product.length < 2) continue
            started = true
            out += ReceiptLine(line, Text.titleCase(product), count, packAmount, packUnit, weighed, price)
        }
        return out
    }
}
