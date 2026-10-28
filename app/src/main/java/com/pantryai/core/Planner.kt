package com.pantryai.core

enum class Have { ENOUGH, PARTIAL, MISSING, UNKNOWN, STAPLE }

data class Availability(val status: Have, val have: Qty?, val need: Qty?, val text: String)

data class Deduction(
    val pantryItemId: String,
    val pantryName: String,
    /** amount to subtract, in the pantry item's unit */
    val amount: Double,
    val unit: String,
    val size: String?,
    val remaining: Double,
    val explanation: String,
)

data class DeductionPlan(
    val recipeIngredient: RecipeIngredient,
    val scaledNeed: Qty?,
    val deductions: List<Deduction>,
    /** Shortfall in the recipe's own unit (0 if covered). */
    val missing: Double,
    val problem: String?,
)

data class ShoppingNeed(
    val name: String,
    val ingredientId: String?,
    val amount: Double?,
    val unit: String,
    val size: String?,
    val category: String,
    val note: String,
)

class Planner(private val kb: Knowledge) {

    private fun ing(id: String?) = kb.get(id)

    private fun sameThing(p: PantryItem, r: RecipeIngredient): Boolean {
        if (r.ingredientId != null && p.ingredientId != null) {
            if (p.ingredientId == r.ingredientId) return true
            // Egg yolks/whites come out of whole eggs.
            return false
        }
        return Text.norm(p.name) == Text.norm(r.name)
    }

    /** Amount of [r] covered by pantry, expressed in the recipe's unit. */
    fun availability(r: RecipeIngredient, pantry: List<PantryItem>, scale: Double = 1.0): Availability {
        val i = ing(r.ingredientId)
        if (i?.staple == true) return Availability(Have.STAPLE, null, null, "always available")
        val items = pantry.filter { sameThing(it, r) }
        val need = r.qty?.let { it.copy(amount = it.amount * scale) }
        if (need == null) {
            return if (items.isEmpty()) Availability(Have.MISSING, null, null, "not in pantry")
            else Availability(Have.ENOUGH, items.first().qty, null, "in pantry")
        }
        if (items.isEmpty()) return Availability(Have.MISSING, null, need, "need ${Convert.format(need, i)}")
        var have = 0.0
        var unconvertible = false
        for (p in items) {
            val c = Convert.convert(p.qty, need.unit, need.size, i)
            if (c == null) unconvertible = true else have += c
        }
        val haveQ = Qty(have, need.unit, need.size)
        return when {
            have >= need.amount - 1e-6 -> Availability(Have.ENOUGH, haveQ, need, "have ${Convert.format(haveQ, i)}")
            have > 0 -> Availability(Have.PARTIAL, haveQ, need, "have ${Convert.format(haveQ, i)} of ${Convert.format(need, i)}")
            unconvertible -> Availability(Have.UNKNOWN, items.first().qty, need, "have ${Convert.format(items.first().qty, i)}, need ${Convert.format(need, i)}")
            else -> Availability(Have.MISSING, null, need, "need ${Convert.format(need, i)}")
        }
    }

    /**
     * Work out what "I made this" removes from the pantry. Converts across units and sizes:
     * 2 cloves from a bulb of garlic -> 0.2 bulb; 2 large eggs from medium eggs -> 2 medium (2.3 by weight).
     */
    fun planDeduction(recipe: Recipe, portions: Int, pantry: List<PantryItem>): List<DeductionPlan> {
        val scale = portions.toDouble() / recipe.servings.coerceAtLeast(1)
        val remaining = pantry.associate { it.id to it.amount }.toMutableMap()
        return recipe.ingredients.map { r ->
            val i = ing(r.ingredientId)
            val need = r.qty?.let { it.copy(amount = it.amount * scale) }
            if (i?.staple == true || need == null) {
                return@map DeductionPlan(r, need, emptyList(), 0.0, if (need == null) "no amount, not deducted" else null)
            }
            val items = pantry.filter { sameThing(it, r) }.sortedBy { it.addedAt }
            var left = need.amount
            val deds = mutableListOf<Deduction>()
            var problem: String? = null
            for (p in items) {
                if (left <= 1e-6) break
                val avail = remaining[p.id] ?: continue
                if (avail <= 1e-9) continue
                val inPantryUnit = Convert.convert(Qty(left, need.unit, need.size), p.unit, p.size, i)
                if (inPantryUnit == null) {
                    problem = "can't convert ${Units.label(need.unit, 2.0)} to ${Units.label(p.unit, 2.0)}"
                    continue
                }
                val rounded = Convert.humanRound(inPantryUnit, p.unit, i)
                var take = minOf(rounded, avail)
                if (take <= 0 && inPantryUnit > 0) take = minOf(avail, inPantryUnit)
                val rest = avail - take
                remaining[p.id] = rest
                // If we took the human-rounded amount (2 large eggs -> 2 medium), the need is covered.
                left = if (take >= rounded - 1e-9) 0.0 else left - (Convert.convert(Qty(take, p.unit, p.size), need.unit, need.size, i) ?: left)
                val explanation = buildString {
                    val needTxt = Convert.format(need, i)
                    val takeTxt = Convert.format(Qty(take, p.unit, p.size), i)
                    if (need.unit != p.unit || need.size != p.size) append("$needTxt → $takeTxt") else append(takeTxt)
                }
                deds += Deduction(p.id, p.name, take, p.unit, p.size, maxOf(0.0, rest), explanation)
            }
            DeductionPlan(r, need, deds, maxOf(0.0, left).let { if (it < need.amount * 0.03) 0.0 else it }, problem)
        }
    }

    /** Apply deductions; items that hit (almost) zero are removed. */
    fun applyDeductions(pantry: List<PantryItem>, deductions: List<Deduction>): List<PantryItem> {
        val byId = deductions.groupBy { it.pantryItemId }.mapValues { e -> e.value.sumOf { it.amount } }
        return pantry.mapNotNull { p ->
            val d = byId[p.id] ?: return@mapNotNull p
            val left = p.amount - d
            val tiny = if (Convert.isCountable(p.unit)) 0.05 else maxOf(0.5, p.amount * 0.01)
            if (left <= tiny) null else p.copy(amount = left)
        }
    }

    /** Merge a new item into the pantry, converting into an existing entry's unit when possible. */
    fun addToPantry(pantry: List<PantryItem>, item: PantryItem): List<PantryItem> {
        val i = ing(item.ingredientId)
        val idx = pantry.indexOfFirst { p ->
            (if (item.ingredientId != null) p.ingredientId == item.ingredientId else Text.norm(p.name) == Text.norm(item.name)) &&
                (p.size == item.size || i?.sizes.isNullOrEmpty())
        }
        if (idx >= 0) {
            val p = pantry[idx]
            val conv = Convert.convert(item.qty, p.unit, p.size, i)
            if (conv != null) return pantry.toMutableList().also { it[idx] = p.copy(amount = p.amount + conv) }
        }
        return pantry + item
    }

    /**
     * Shopping list for several recipes with chosen portions. Needs are summed per ingredient
     * (in its base unit when convertible), the pantry is subtracted, and the rest is rounded up
     * into something you can buy (garlic in bulbs, tomatoes in cans).
     */
    fun shoppingList(selection: List<Pair<Recipe, Int>>, pantry: List<PantryItem>): List<ShoppingNeed> {
        data class Acc(val key: String, val name: String, val ing: Ingredient?, var unit: String, var size: String?, var amount: Double, var unquantified: Boolean, val recipes: MutableSet<String>, var countish: Boolean = false)
        val acc = LinkedHashMap<String, Acc>()
        for ((recipe, portions) in selection) {
            val scale = portions.toDouble() / recipe.servings.coerceAtLeast(1)
            for (r in recipe.ingredients) {
                val i = ing(r.ingredientId)
                if (i?.staple == true) continue
                val name = i?.en ?: r.name
                val q = r.qty?.let { it.copy(amount = it.amount * scale) }
                val target = Convert.aggregateUnit(i, q?.unit ?: "pc")
                // Keep sized eggs in pieces of that size; otherwise aggregate in base unit if we can convert.
                val (amt, unit, size) = if (q == null) Triple(0.0, target, null) else {
                    val sizeKeep = if (i?.sizes?.isNotEmpty() == true) q.size else null
                    val c = Convert.convert(q, target, sizeKeep, i)
                    if (c != null) Triple(c, target, sizeKeep) else Triple(q.amount, q.unit, q.size)
                }
                val key = (r.ingredientId ?: Text.norm(r.name)) + "|" + unit + "|" + (size ?: "")
                val a = acc.getOrPut(key) { Acc(key, name, i, unit, size, 0.0, false, linkedSetOf()) }
                a.amount += amt
                if (q == null) a.unquantified = true
                if (q != null && Units.dim(q.unit) == Dim.COUNT) a.countish = true
                a.recipes += recipe.title
            }
        }
        val out = mutableListOf<ShoppingNeed>()
        val pantryLeft = pantry.associate { it.id to it.amount }.toMutableMap()
        for (a in acc.values) {
            val ingId = a.ing?.id
            val matching = pantry.filter { p -> if (ingId != null) p.ingredientId == ingId else Text.norm(p.name) == Text.norm(a.name) }
            val note = "for " + a.recipes.joinToString(", ")
            if (a.amount <= 0.0) {
                if (a.unquantified && matching.isEmpty()) out += ShoppingNeed(a.name, ingId, null, a.unit, null, a.ing?.category ?: "other", "$note (to taste)")
                continue
            }
            var need = a.amount
            for (p in matching) {
                val avail = pantryLeft[p.id] ?: 0.0
                if (avail <= 0 || need <= 0) continue
                val availInUnit = Convert.convert(Qty(avail, p.unit, p.size), a.unit, a.size, a.ing) ?: continue
                val used = minOf(availInUnit, need)
                need -= used
                val usedInPantry = Convert.convert(Qty(used, a.unit, a.size), p.unit, p.size, a.ing) ?: avail
                pantryLeft[p.id] = avail - usedInPantry
            }
            if (need <= a.amount * 0.02 || need < 1e-6) continue
            // Express in a buyable unit.
            var unit = a.unit
            var amount = need
            var size = a.size
            val buy = a.ing?.buyUnit
            if (buy != null) {
                Convert.convert(Qty(need, a.unit, a.size), buy, null, a.ing)?.let { amount = it; unit = buy; size = null }
            } else if (a.ing != null && unit == "g" && a.ing.base == "g" && a.ing.pieceGrams != null && (a.ing.soldPerPiece || a.countish)) {
                amount = need / a.ing.pieceGrams; unit = "pc"
            }
            amount = Convert.buyRound(amount, unit)
            out += ShoppingNeed(a.name, ingId, amount, unit, size, a.ing?.category ?: "other", note)
        }
        return out.sortedWith(compareBy({ Categories.rank(it.category) }, { it.name }))
    }
}
