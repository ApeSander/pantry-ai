package com.pantryai.core

import org.json.JSONArray
import org.json.JSONObject

data class Ingredient(
    val id: String,
    val en: String,
    val nl: String,
    val category: String,
    val aliases: List<String> = emptyList(),
    /** Preferred unit for storing/aggregating: "g", "ml" or "pc". */
    val base: String = "g",
    /** Weight of one piece (medium size) in grams. */
    val pieceGrams: Double? = null,
    /** g per ml. */
    val density: Double? = null,
    /** Named units -> grams, e.g. garlic clove=5, bulb=50. */
    val units: Map<String, Double> = emptyMap(),
    /** Size variants (eggs) -> grams per piece. */
    val sizes: Map<String, Double> = emptyMap(),
    /** What a bare count means ("2 garlic" = 2 cloves). */
    val pieceUnit: String? = null,
    /** Unit to express shopping amounts in (bulb, can, bunch...). */
    val buyUnit: String? = null,
    /** Usually sold per piece, so "1x" on a receipt is unambiguous. */
    val soldPerPiece: Boolean = false,
    /** Never track (tap water). */
    val staple: Boolean = false,
    val learned: Boolean = false,
) {
    fun displayName(lang: String = "en"): String = if (lang == "nl") nl else en

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("en", en); put("nl", nl); put("c", category)
        put("a", JSONArray(aliases)); put("b", base)
        pieceGrams?.let { put("pg", it) }
        density?.let { put("d", it) }
        if (units.isNotEmpty()) put("u", JSONObject(units as Map<*, *>))
        if (sizes.isNotEmpty()) put("s", JSONObject(sizes as Map<*, *>))
        pieceUnit?.let { put("pu", it) }
        buyUnit?.let { put("buy", it) }
        if (soldPerPiece) put("sold", "pc")
        if (staple) put("staple", true)
        if (learned) put("learned", true)
    }

    companion object {
        fun fromJson(o: JSONObject): Ingredient {
            fun dmap(key: String): Map<String, Double> {
                val m = o.optJSONObject(key) ?: return emptyMap()
                return m.keys().asSequence().associateWith { m.getDouble(it) }
            }
            val a = o.optJSONArray("a")
            return Ingredient(
                id = o.getString("id"),
                en = o.getString("en"),
                nl = o.optString("nl", o.getString("en")),
                category = o.optString("c", "other"),
                aliases = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) },
                base = o.optString("b", "g"),
                pieceGrams = if (o.has("pg")) o.getDouble("pg") else null,
                density = if (o.has("d")) o.getDouble("d") else null,
                units = dmap("u"),
                sizes = dmap("s"),
                pieceUnit = o.optString("pu").ifEmpty { null },
                buyUnit = o.optString("buy").ifEmpty { null },
                soldPerPiece = o.optString("sold") == "pc",
                staple = o.optBoolean("staple", false),
                learned = o.optBoolean("learned", false),
            )
        }
    }
}

object Categories {
    val order = listOf("produce", "herbs", "dairy", "meat", "fish", "bakery", "grains", "canned", "sauces", "oils", "baking", "spices", "nuts", "frozen", "snacks", "drinks", "other")
    private val labels = mapOf(
        "produce" to ("🥕" to "Fruit & veg"), "herbs" to ("🌿" to "Fresh herbs"), "dairy" to ("🧀" to "Dairy & eggs"),
        "meat" to ("🥩" to "Meat"), "fish" to ("🐟" to "Fish"), "bakery" to ("🍞" to "Bread"),
        "grains" to ("🍝" to "Pasta, rice & grains"), "canned" to ("🥫" to "Cans & jars"), "sauces" to ("🍯" to "Sauces & spreads"),
        "oils" to ("🫒" to "Oils"), "baking" to ("🧁" to "Baking"), "spices" to ("🧂" to "Herbs & spices"),
        "nuts" to ("🥜" to "Nuts & seeds"), "frozen" to ("❄️" to "Frozen"), "snacks" to ("🍪" to "Snacks"),
        "drinks" to ("🥤" to "Drinks"), "other" to ("📦" to "Other"),
    )
    fun emoji(c: String) = labels[c]?.first ?: "📦"
    fun label(c: String) = labels[c]?.second ?: Text.capitalize(c)
    fun rank(c: String) = order.indexOf(c).let { if (it < 0) order.size else it }
    fun valid(c: String) = c in labels
}

data class Match(val ingredient: Ingredient, val score: Double, val via: String)

/**
 * The food knowledge base: ~230 curated NL/EN ingredients plus everything the app learned
 * (from the on-device model or from user corrections).
 */
class Knowledge(base: List<Ingredient>) {
    private val items = LinkedHashMap<String, Ingredient>()
    /** normalized alias -> ingredient id */
    private val index = HashMap<String, String>()
    /** stemmed alias -> ingredient id */
    private val stemIndex = HashMap<String, String>()
    /** learned exact aliases (user corrections + AI decisions); highest priority */
    private val learnedAliases = HashMap<String, String>()

    init { base.forEach { add(it) } }

    val all: Collection<Ingredient> get() = items.values

    fun get(id: String?): Ingredient? = id?.let { items[it] }

    fun add(ing: Ingredient) {
        items[ing.id] = ing
        for (name in listOf(ing.en, ing.nl) + ing.aliases) {
            val n = Text.norm(name)
            if (n.isEmpty()) continue
            index.putIfAbsent(n, ing.id)
            stemIndex.putIfAbsent(Text.stemPhrase(n), ing.id)
        }
    }

    fun learnAlias(alias: String, id: String) {
        val n = Text.norm(alias)
        if (n.isNotEmpty() && items.containsKey(id)) learnedAliases[n] = id
    }

    fun forgetAlias(alias: String) { learnedAliases.remove(Text.norm(alias)) }

    fun learnedAliasMap(): Map<String, String> = learnedAliases.toMap()

    fun learnedIngredients(): List<Ingredient> = items.values.filter { it.learned }

    fun removeLearned(id: String) {
        val ing = items[id] ?: return
        if (!ing.learned) return
        items.remove(id)
        index.entries.removeIf { it.value == id }
        stemIndex.entries.removeIf { it.value == id }
        learnedAliases.entries.removeIf { it.value == id }
    }

    /** Words that describe preparation or quality rather than the food itself. */
    private val noise = setOf(
        "fresh", "vers", "verse", "chopped", "gehakt", "gehakte", "fijngehakt", "fijngehakte", "fijngesneden", "gesneden",
        "sliced", "diced", "minced", "grated", "geraspt", "geraspte", "large", "small", "medium", "groot", "grote", "klein", "kleine",
        "middelgroot", "middelgrote", "biologisch", "biologische", "bio", "organic", "ripe", "rijp", "rijpe", "finely", "roughly",
        "grof", "grove", "fijn", "fijne", "peeled", "gepeld", "gepelde", "optional", "optioneel", "about", "ongeveer", "approx",
        "of", "van", "de", "het", "een", "the", "a", "an", "and", "en", "for", "voor", "to", "taste", "naar", "smaak", "extra",
        "halved", "gehalveerd", "crushed", "geperst", "geperste", "pressed", "cubed", "in", "blokjes", "stukjes", "pieces",
        "plus", "more", "meer", "some", "wat", "beetje", "handful", "x", "ca", "circa", "cold", "koud", "koude", "warm",
        "room", "temperature", "kamertemperatuur", "softened", "zacht", "zachte", "melted", "gesmolten", "frozen", "diepvries",
        "thawed", "ontdooid", "drained", "uitgelekt", "rinsed", "afgespoeld", "cooked", "gekookt", "gekookte", "raw", "rauw",
        "rauwe", "boneless", "skinless", "zonder", "vel", "bot", "lean", "mager", "magere", "thinly", "thick", "dun", "dunne",
        "dik", "dikke", "halve", "half", "quartered", "kwart", "whole", "hele", "heel", "uncooked", "ongekookt", "dried", "pack",
        "pak", "zak", "bag", "stuks", "st", "kg", "g", "gr", "ml", "l", "ltr", "mix",
    )

    fun clean(name: String): String = Text.tokens(name).filter { it !in noise && it.any { c -> c.isLetter() } }.joinToString(" ")

    /** Best matches for a free-text food name, best first. */
    fun candidates(rawName: String, limit: Int = 5): List<Match> {
        val n = Text.norm(rawName)
        if (n.isEmpty()) return emptyList()
        learnedAliases[n]?.let { id -> items[id]?.let { return listOf(Match(it, 1.0, "learned")) } }
        index[n]?.let { id -> return listOf(Match(items[id]!!, 1.0, "exact")) }
        val stemmed = Text.stemPhrase(n)
        stemIndex[stemmed]?.let { id -> return listOf(Match(items[id]!!, 0.97, "plural")) }

        val cleaned = clean(n)
        if (cleaned.isNotEmpty() && cleaned != n) {
            learnedAliases[cleaned]?.let { id -> items[id]?.let { return listOf(Match(it, 0.97, "learned")) } }
            index[cleaned]?.let { id -> return listOf(Match(items[id]!!, 0.96, "exact")) }
            stemIndex[Text.stemPhrase(cleaned)]?.let { id -> return listOf(Match(items[id]!!, 0.95, "plural")) }
        }

        val scores = HashMap<String, Match>()
        fun offer(id: String, score: Double, via: String) {
            val cur = scores[id]
            if (cur == null || cur.score < score) scores[id] = Match(items[id]!!, score, via)
        }

        val toks = Text.tokens(cleaned.ifEmpty { n })
        val stemToks = toks.map { Text.stem(it) }
        // 1) Phrase containment: "zak basmati rijst" contains "basmati rijst". Longer phrases win.
        for (len in minOf(4, toks.size) downTo 1) {
            for (start in 0..toks.size - len) {
                val phrase = toks.subList(start, start + len).joinToString(" ")
                val sphrase = stemToks.subList(start, start + len).joinToString(" ")
                val id = learnedAliases[phrase] ?: index[phrase] ?: stemIndex[sphrase] ?: continue
                val coverage = len.toDouble() / toks.size
                offer(id, 0.80 + 0.12 * coverage + 0.01 * len, "contains")
            }
        }
        // 2) Dutch compounds: "scharreleieren" ends with "eieren", "kipfiletblokjes" starts with "kipfilet".
        for (t in toks) {
            if (t.length < 6) continue
            for ((alias, id) in index) {
                if (alias.length < 4 || alias.contains(' ')) continue
                if (t != alias && (t.endsWith(alias) || t.startsWith(alias)) && alias.length >= t.length * 0.45) {
                    offer(id, 0.78 + 0.1 * (alias.length.toDouble() / t.length), "compound")
                }
            }
        }
        // 3) Typo tolerance: "tomatos", "basmatti", "knoflok".
        val joined = toks.joinToString(" ")
        for ((alias, id) in index) {
            val lenDiff = kotlin.math.abs(alias.length - joined.length)
            if (lenDiff <= 3) {
                val d = Text.editDistance(alias, joined)
                val s = Text.similarity(alias, joined)
                val score = when {
                    d == 1 && joined.length >= 5 -> 0.9
                    d == 2 && joined.length >= 9 -> 0.87
                    else -> s * 0.93
                }
                if (s >= 0.7) offer(id, score, "typo")
            }
            if (!alias.contains(' ') && alias.length >= 4) {
                for (t in toks) {
                    if (kotlin.math.abs(alias.length - t.length) > 2 || t.length < 4) continue
                    val s = Text.similarity(alias, t)
                    if (s >= 0.75) offer(id, s * 0.85 * (0.6 + 0.4 / toks.size), "typo")
                }
            }
        }
        return scores.values.sortedByDescending { it.score }.take(limit)
    }

    fun best(rawName: String): Match? = candidates(rawName, 1).firstOrNull()

    /**
     * Context-aware disambiguation. "1 tsp paprika" is the spice, "2 paprika" is the vegetable;
     * "peper" with a teaspoon is black pepper, not a chili.
     */
    fun disambiguate(match: Match?, unit: String?): Match? {
        if (match == null) return null
        val spoon = unit != null && unit in Units.spoonish
        if (spoon && match.ingredient.id == "bell_pepper") get("paprika_powder")?.let { return Match(it, match.score, "context") }
        if (spoon && match.ingredient.id == "garlic") get("garlic_powder")?.let { return Match(it, match.score * 0.9, "context") }
        if (spoon && match.ingredient.id == "ginger") get("ginger_powder")?.let { return Match(it, match.score * 0.9, "context") }
        if (spoon && match.ingredient.id == "thyme") get("dried_thyme")?.let { return Match(it, match.score, "context") }
        if (spoon && match.ingredient.id == "cilantro") get("ground_coriander")?.let { return Match(it, match.score * 0.9, "context") }
        return match
    }

    fun search(query: String, limit: Int = 12): List<Ingredient> {
        val q = Text.norm(query)
        if (q.isEmpty()) return items.values.take(limit)
        val starts = items.values.filter { ing -> listOf(ing.en, ing.nl).any { Text.norm(it).startsWith(q) } }
        val rest = candidates(query, limit).map { it.ingredient }
        val contains = items.values.filter { ing -> (listOf(ing.en, ing.nl) + ing.aliases).any { Text.norm(it).contains(q) } }
        return (starts + rest + contains).distinctBy { it.id }.take(limit)
    }

    companion object {
        fun parse(json: String): List<Ingredient> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { Ingredient.fromJson(arr.getJSONObject(it)) }
        }

        fun slug(name: String): String = "x_" + Text.norm(name).replace(' ', '_').take(40)
    }
}
