package com.pantryai.core

/** Anything that can complete a prompt. On the phone this is the on-device model (MediaPipe). */
interface LlmBackend {
    val ready: Boolean
    /** Returns the model's raw answer, or null if unavailable/failed. Blocking. */
    fun complete(system: String, user: String): String?
}

object NoLlm : LlmBackend {
    override val ready = false
    override fun complete(system: String, user: String): String? = null
}

data class Resolution(
    val ingredient: Ingredient?,
    val score: Double,
    /** exact, plural, typo, contains, compound, learned, ai, ai-new, guess, unknown, context */
    val via: String,
)

data class ReceiptItem(
    val id: String = newId(),
    val line: ReceiptLine,
    val name: String,
    val ingredient: Ingredient?,
    val amount: Double?,
    val unit: String,
    val size: String?,
    val include: Boolean,
    /** True when the quantity can't be read from the receipt: the user is asked. */
    val unclear: Boolean,
    val via: String,
    val isFood: Boolean = true,
)

/**
 * The "smart" layer. Deterministic knowledge first (fast, never hallucinates numbers),
 * the on-device language model for the fuzzy parts, and everything the model decides is
 * remembered so the same question is never asked twice.
 */
class SmartEngine(
    private val kb: Knowledge,
    var llm: LlmBackend,
    private val onLearnAlias: (alias: String, ingredientId: String) -> Unit = { _, _ -> },
    private val onLearnIngredient: (Ingredient) -> Unit = {},
) {
    private val categories = Categories.order.joinToString(", ")
    private val system = "You are a precise food and grocery expert. Follow the answer format exactly. Be brief."

    // ---------- ingredient identity ----------

    fun resolve(name: String, unit: String? = null, allowAi: Boolean = true): Resolution {
        val cands = kb.candidates(name, 5)
        val best = kb.disambiguate(cands.firstOrNull(), unit)
        if (best != null && best.score >= 0.86) return Resolution(best.ingredient, best.score, best.via)

        if (allowAi && llm.ready) {
            // 1) Let the model choose among close candidates: is "zilvervlies basmatti" the same as brown rice?
            val shortlist = cands.filter { it.score >= 0.45 }.take(4).map { it.ingredient }
            if (shortlist.isNotEmpty()) {
                val choice = askSameAs(name, shortlist)
                if (choice != null) {
                    onLearnAlias(name, choice.id)
                    kb.learnAlias(name, choice.id)
                    return Resolution(kb.disambiguate(Match(choice, 0.9, "ai"), unit)!!.ingredient, 0.9, "ai")
                }
            }
            // 2) Unknown: ask the model what it is, then try the knowledge base again with that name.
            val info = askWhatIs(name)
            if (info != null) {
                val again = kb.disambiguate(kb.best(info.english), unit)
                if (again != null && again.score >= 0.9) {
                    onLearnAlias(name, again.ingredient.id)
                    kb.learnAlias(name, again.ingredient.id)
                    return Resolution(again.ingredient, 0.88, "ai")
                }
                val clean = kb.clean(info.english).ifEmpty { info.english }
                val newIng = Ingredient(
                    id = Knowledge.slug(clean), en = clean, nl = kb.clean(name).ifEmpty { name.lowercase() },
                    category = info.category, aliases = listOf(name.lowercase()),
                    base = if (info.category == "drinks") "ml" else "g", learned = true,
                )
                val existing = kb.get(newIng.id)
                val ing = existing ?: newIng.also { kb.add(it); onLearnIngredient(it) }
                onLearnAlias(name, ing.id)
                kb.learnAlias(name, ing.id)
                return Resolution(ing, 0.8, "ai-new")
            }
        }
        if (best != null && best.score >= 0.62) return Resolution(best.ingredient, best.score, "guess")
        return Resolution(null, 0.0, "unknown")
    }

    private fun label(i: Ingredient) = if (i.en.equals(i.nl, true)) i.en else "${i.en} (${i.nl})"

    fun askSameAs(name: String, options: List<Ingredient>): Ingredient? {
        val prompt = buildString {
            append("A shopping/recipe item is called \"").append(name).append("\".\n")
            append("Which ingredient is it the same food as? Different names or languages (Dutch/English) for the same food count as the same.\n")
            options.forEachIndexed { i, o -> append(i + 1).append(". ").append(label(o)).append('\n') }
            append("0. none of these, it is a different food\n")
            append("Answer with the number only.")
        }
        val out = llm.complete(system, prompt) ?: return null
        val n = Regex("\\d").find(out)?.value?.toIntOrNull() ?: return null
        return options.getOrNull(n - 1)
    }

    data class WhatIs(val english: String, val category: String, val food: Boolean)

    fun askWhatIs(name: String): WhatIs? {
        val prompt = """
            Item: "$name" (may be Dutch, abbreviated or a brand name).
            Answer in exactly this format:
            english: <short generic English food name, no brand>
            category: <one of: $categories>
            food: <yes or no>
        """.trimIndent()
        val out = llm.complete(system, prompt) ?: return null
        return parseWhatIs(out)
    }

    fun parseWhatIs(out: String): WhatIs? {
        fun field(k: String) = Regex("(?im)^\\s*\\**$k\\**\\s*[:=]\\s*(.+)$").find(out)?.groupValues?.get(1)?.trim()?.trim('"', '*', '.', ' ')
        val en = field("english")?.takeIf { it.isNotBlank() && it.length < 60 } ?: return null
        val cat = field("category")?.lowercase()?.let { c -> Categories.order.firstOrNull { c.contains(it) } } ?: "other"
        val food = field("food")?.lowercase()?.startsWith("n") != true
        return WhatIs(en.lowercase(), cat, food)
    }

    // ---------- recipes ----------

    /** Ingredient lines -> resolved recipe ingredients. */
    fun recipeIngredients(lines: List<String>, progress: (String) -> Unit = {}): List<RecipeIngredient> {
        return lines.map { it.trim() }.filter { it.isNotEmpty() }.mapIndexed { idx, line ->
            progress("Understanding ingredient ${idx + 1} of ${lines.size}…")
            fromParsed(IngredientParser.parse(line))
        }
    }

    fun fromParsed(p: ParsedLine): RecipeIngredient {
        val res = resolve(p.name, p.unit)
        val ing = res.ingredient
        var unit = p.unit
        val amount = p.amount
        // "3 garlic" means cloves; "2 lemons, juiced" stays pieces.
        if (ing != null && unit == "pc" && ing.pieceUnit != null) unit = ing.pieceUnit
        return RecipeIngredient(
            raw = p.raw, name = ing?.en ?: Text.capitalize(Text.norm(p.name)), ingredientId = ing?.id,
            amount = amount, unit = unit, size = if (ing != null && (ing.sizes.isNotEmpty() || ing.pieceGrams != null)) p.size else null,
            note = p.note, via = res.via, optional = p.optional,
        )
    }

    /**
     * Free text (copied from a website, Instagram caption, message from a friend) -> ingredient lines.
     * Uses structure first; the model only when the text doesn't look like a list.
     */
    fun extractIngredientLines(text: String): List<String> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val headerIdx = lines.indexOfFirst { Regex("^(ingredients?|ingrediënten|ingredienten|benodigdheden|wat heb je nodig|je hebt nodig)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val stepIdx = lines.indexOfFirst { Regex("^(instructions?|method|directions|preparation|bereiding|bereidingswijze|werkwijze|zo maak je het|steps?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val section = if (headerIdx >= 0) lines.subList(headerIdx + 1, if (stepIdx > headerIdx) stepIdx else lines.size) else lines
        val heuristic = section.filter { IngredientParser.looksLikeIngredient(it) }
        if (heuristic.size >= 2 && (headerIdx >= 0 || heuristic.size >= section.size / 3)) return heuristic
        if (!llm.ready) return heuristic.ifEmpty { section.filter { it.length < 80 } }
        // Ask the model in small chunks (the on-device model has a short context window).
        val chunks = text.chunked(1400)
        val out = mutableListOf<String>()
        for (chunk in chunks.take(4)) {
            val prompt = "Extract ONLY the ingredients from this recipe text. Write one ingredient per line as: amount unit ingredient. " +
                "Keep the original language. Write NONE if there are no ingredients.\n\nText:\n$chunk\n\nIngredients:"
            val res = llm.complete(system, prompt) ?: continue
            out += res.lines().map { it.trim().trimStart('-', '*', '•', ' ').replace(Regex("^\\d+[.)]\\s+(?=\\D)"), "") }
                .filter { it.isNotEmpty() && !it.equals("none", true) && it.length < 100 && !it.endsWith(":") }
        }
        return out.distinct().ifEmpty { heuristic }
    }

    // ---------- receipts ----------

    private val sizeToken = Regex("\\b(xl|l|m|s|large|medium|small|groot|middel|klein)\\b", RegexOption.IGNORE_CASE)

    fun receiptItems(lines: List<ReceiptLine>, progress: (String) -> Unit = {}): List<ReceiptItem> {
        // First pass: knowledge base only.
        val first = lines.map { it to resolve(it.product, null, allowAi = false) }
        // Lines the knowledge base isn't sure about: let the model decode the abbreviations in one batch.
        val unsure = first.filter { it.second.score < 0.86 }.map { it.first }
        val decoded = HashMap<ReceiptLine, String?>()
        if (unsure.isNotEmpty() && llm.ready) {
            unsure.chunked(6).forEachIndexed { ci, chunk ->
                progress("Decoding abbreviations with on-device AI (${minOf((ci + 1) * 6, unsure.size)}/${unsure.size})…")
                val answers = decodeReceiptLines(chunk.map { it.raw.replace(Regex("\\s+-?\\d+[.,]\\d{2}\\s*\\S?$"), "") })
                chunk.forEachIndexed { i, l -> decoded[l] = answers.getOrNull(i) }
            }
        }
        return first.mapIndexed { idx, (line, res0) ->
            progress("Matching item ${idx + 1} of ${lines.size}…")
            var res = res0
            var isFood = true
            var displayName = line.product
            if (line in decoded) {
                val d = decoded[line]
                if (d == null) {
                    // model said SKIP -> probably not food
                    isFood = false
                } else {
                    val r2 = resolve(d, null)
                    if (r2.ingredient != null && r2.score >= res.score) {
                        res = Resolution(r2.ingredient, r2.score, "ai")
                        onLearnAlias(line.product, r2.ingredient.id); kb.learnAlias(line.product, r2.ingredient.id)
                    }
                    displayName = Text.capitalize(d)
                }
            } else if (res.score < 0.62 && llm.ready) {
                res = resolve(line.product, null)
            }
            val ing = res.ingredient
            val size = if (ing != null && ing.sizes.isNotEmpty()) sizeToken.findAll(" " + line.raw + " ").map { it.value.uppercase() }
                .map { when (it) { "LARGE", "GROOT" -> "L"; "MEDIUM", "MIDDEL" -> "M"; "SMALL", "KLEIN" -> "S"; else -> it } }
                .firstOrNull { it in ing.sizes } else null
            val (amount, unit, unclear) = quantityFor(line, ing)
            ReceiptItem(
                line = line, name = ing?.en?.let { Text.capitalize(it) } ?: displayName, ingredient = ing,
                amount = amount, unit = unit, size = size, include = isFood && !(ing?.staple ?: false), unclear = unclear,
                via = res.via, isFood = isFood,
            )
        }
    }

    /** One batched prompt: abbreviated Dutch receipt lines -> plain product names (or null = not food). */
    fun decodeReceiptLines(raw: List<String>): List<String?> {
        val prompt = buildString {
            append("These lines are from a Dutch supermarket receipt and are abbreviated. ")
            append("For each line write the product as a short generic English food name, without brand or package size. ")
            append("Write SKIP if it is not food or drink (e.g. soap, bags, deposit).\n\n")
            raw.forEachIndexed { i, l -> append(i + 1).append(". ").append(l).append('\n') }
            append("\nAnswer with the same numbering, one per line:\n1.")
        }
        val out = llm.complete(system, prompt) ?: return raw.map { it }
        return parseNumbered("1." + out, raw.size).mapIndexed { i, a ->
            when {
                a == null -> raw[i]
                a.uppercase().startsWith("SKIP") -> null
                else -> a
            }
        }
    }

    fun parseNumbered(out: String, n: Int): List<String?> {
        val res = arrayOfNulls<String>(n)
        for (m in Regex("(?m)^\\s*(\\d+)\\s*[.):-]\\s*(.+)$").findAll(out)) {
            val i = m.groupValues[1].toInt() - 1
            if (i in 0 until n && res[i] == null) res[i] = m.groupValues[2].trim().replace(Regex("^\\d+\\s*[.)]\\s*"), "").trim('"', '*').trim()
        }
        return res.toList()
    }

    /** (amount, unit, unclear) for a receipt line. Unclear means: ask the user. */
    fun quantityFor(line: ReceiptLine, ing: Ingredient?): Triple<Double?, String, Boolean> {
        if (line.packAmount != null && line.packUnit != null) {
            var unit = line.packUnit
            var amount = line.packAmount * (if (line.weighed) 1 else line.count)
            if (unit == "kg") { amount *= 1000; unit = "g" }
            if (unit == "l") { amount *= 1000; unit = "ml" }
            if (unit == "cl") { amount *= 10; unit = "ml" }
            // "KNOFLOOK 3ST" = 3 bulbs, not 3 cloves
            if (unit == "pc" && ing?.pieceUnit != null && ing.buyUnit != null) unit = ing.buyUnit
            return Triple(amount, unit, false)
        }
        if (ing != null && ing.soldPerPiece) {
            val unit = ing.buyUnit ?: "pc"
            return Triple(line.count.toDouble(), unit, false)
        }
        // Package with a standard size: "AH TOMATENBLOKJES" -> 1 can
        if (ing != null && ing.buyUnit != null && ing.buyUnit in setOf("can", "sachet", "bulb", "bunch", "bottle")) {
            return Triple(line.count.toDouble(), ing.buyUnit, false)
        }
        return Triple(null, ing?.base ?: "pc", true)
    }

    /** Quick answers to "how much did you buy?" */
    fun suggestions(ing: Ingredient?): List<Qty> {
        val out = mutableListOf<Qty>()
        if (ing == null) return listOf(Qty(1.0, "pc"), Qty(250.0, "g"), Qty(500.0, "g"), Qty(1000.0, "g"), Qty(1000.0, "ml"))
        ing.buyUnit?.let { out += Qty(1.0, it); out += Qty(2.0, it) }
        if (ing.sizes.isNotEmpty()) { out += Qty(6.0, "pc", "M"); out += Qty(10.0, "pc", "M"); out += Qty(12.0, "pc", "L") }
        when (ing.base) {
            "ml" -> { out += Qty(500.0, "ml"); out += Qty(1000.0, "ml"); out += Qty(1500.0, "ml") }
            "pc" -> if (ing.sizes.isEmpty()) { out += Qty(1.0, "pc"); out += Qty(4.0, "pc"); out += Qty(6.0, "pc") }
            else -> {
                if (ing.pieceGrams != null && ing.category in setOf("produce", "bakery")) { out += Qty(1.0, "pc"); out += Qty(3.0, "pc"); out += Qty(6.0, "pc") }
                if (ing.category in setOf("spices", "herbs")) { out += Qty(25.0, "g"); out += Qty(50.0, "g") }
                out += Qty(250.0, "g"); out += Qty(500.0, "g"); out += Qty(1000.0, "g")
            }
        }
        return out.distinct().take(6)
    }
}
