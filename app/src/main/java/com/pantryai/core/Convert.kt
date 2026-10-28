package com.pantryai.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/** amount + unit + optional size (S/M/L/XL for eggs, or small/large for produce). */
data class Qty(val amount: Double, val unit: String, val size: String? = null)

object Convert {
    /** Generic multipliers for "small onion", "large potato" when the KB has no explicit sizes. */
    private val genericSize = mapOf("S" to 0.7, "M" to 1.0, "L" to 1.4, "XL" to 1.7)

    /** Density fallbacks per category when an ingredient has none (liquids are ~water). */
    private fun density(ing: Ingredient?): Double? = ing?.density ?: when (ing?.category) {
        "drinks", "oils" -> 1.0
        null -> null
        else -> null
    }

    fun pieceGrams(ing: Ingredient?, size: String?): Double? {
        if (ing == null) return null
        if (size != null) {
            ing.sizes[size]?.let { return it }
            val base = ing.pieceGrams ?: return null
            return base * (genericSize[size] ?: 1.0)
        }
        ing.pieceGrams?.let { return it }
        ing.sizes["M"]?.let { return it }
        return null
    }

    /** Grams in one [unit] of [ing]; null if unknown. */
    fun gramsPerUnit(unit: String, ing: Ingredient?, size: String? = null): Double? {
        val def = Units.get(unit)
        if (def != null) return when (def.dim) {
            Dim.MASS -> def.factor
            Dim.VOLUME -> density(ing)?.let { it * def.factor }
            Dim.COUNT -> {
                // A bare count of garlic means cloves, not bulbs.
                val pu = ing?.pieceUnit
                if (pu != null && size == null) ing.units[pu]?.let { it * def.factor }
                else pieceGrams(ing, size)?.let { it * def.factor }
            }
            Dim.NAMED -> null
        }
        ing?.units?.get(unit)?.let { g -> return g * (if (size != null) genericSize[size] ?: 1.0 else 1.0) }
        if (unit == "loaf" || unit == "head" || unit == "ball") pieceGrams(ing, size)?.let { return it }
        return Units.namedFallbackGrams[unit]
    }

    fun toGrams(q: Qty, ing: Ingredient?): Double? = gramsPerUnit(q.unit, ing, q.size)?.let { q.amount * it }

    /**
     * Convert [q] into [toUnit]/[toSize] for ingredient [ing]. Tries, in order:
     * identical unit, same generic dimension (tbsp -> ml), then grams as a hub (garlic clove -> bulb, L egg -> M egg).
     */
    fun convert(q: Qty, toUnit: String, toSize: String?, ing: Ingredient?): Double? {
        if (q.unit == toUnit && q.size == toSize) return q.amount
        val a = Units.get(q.unit)
        val b = Units.get(toUnit)
        if (a != null && b != null && a.dim == b.dim && a.dim != Dim.COUNT) return q.amount * a.factor / b.factor
        if (a != null && b != null && a.dim == Dim.COUNT && b.dim == Dim.COUNT && q.size == toSize) return q.amount * a.factor / b.factor
        // Unsized pieces vs sized pieces of the same thing: treat unsized as "medium".
        if (q.unit == toUnit && (q.size == null || toSize == null) && ing?.sizes.isNullOrEmpty()) return q.amount
        val g = toGrams(q, ing) ?: return null
        val per = gramsPerUnit(toUnit, ing, toSize) ?: return null
        if (per <= 0) return null
        return g / per
    }

    fun isCountable(unit: String): Boolean = Units.dim(unit) == Dim.COUNT || Units.dim(unit) == Dim.NAMED

    /** Round a deduction expressed in a countable pantry unit to something a human would do. */
    fun humanRound(amount: Double, unit: String, ing: Ingredient?): Double {
        if (!isCountable(unit)) return amount
        // Bulbs of garlic are used clove by clove: keep the exact fraction.
        if (unit != "pc" && ing?.pieceUnit != null && ing.pieceUnit != unit) return amount
        val whole = unit == "pc" && (ing?.sizes?.isNotEmpty() == true || ing?.id == "bread_rolls" || ing?.id == "tortilla")
        return if (whole) maxOf(1.0, round(amount)) else round(amount * 4) / 4.0
    }

    /** Round a shopping amount up to something you can actually buy. */
    fun buyRound(amount: Double, unit: String): Double = when {
        isCountable(unit) -> ceil(amount - 1e-6)
        unit == "g" || unit == "ml" -> when {
            amount <= 20 -> ceil(amount)
            amount <= 200 -> ceil(amount / 10) * 10
            else -> ceil(amount / 50) * 50
        }
        else -> ceil(amount * 4 - 1e-6) / 4.0
    }

    /** Pick the unit to aggregate an ingredient in for shopping/availability. */
    fun aggregateUnit(ing: Ingredient?, fallback: String): String {
        if (ing == null) return fallback
        return ing.base
    }

    fun approxEqual(a: Double, b: Double) = abs(a - b) < 1e-6

    fun fmtNumber(x: Double): String {
        if (abs(x - round(x)) < 0.01) return round(x).toLong().toString()
        val fracs = listOf(0.25 to "¼", 0.5 to "½", 0.75 to "¾", 1.0 / 3 to "⅓", 2.0 / 3 to "⅔")
        val whole = floor(x)
        val frac = x - whole
        for ((v, s) in fracs) if (abs(frac - v) < 0.02) return if (whole == 0.0) s else "${whole.toLong()}$s"
        return if (x >= 10) round(x).toLong().toString() else (round(x * 10) / 10).toString()
    }

    /**
     * Human-friendly quantity: "750 g", "1.2 kg", "3 cloves", "0.6 bulb (≈6 cloves)", "2 L eggs".
     */
    fun format(q: Qty, ing: Ingredient?): String {
        var amount = q.amount
        var unit = q.unit
        if (unit == "g" && amount >= 1000) { amount /= 1000; unit = "kg" }
        if (unit == "ml" && amount >= 1000) { amount /= 1000; unit = "l" }
        val sizeTxt = if (q.size != null) " ${q.size}" else ""
        val main = when (unit) {
            "pc" -> fmtNumber(amount) + sizeTxt
            else -> fmtNumber(amount) + " " + Units.label(unit, amount) + sizeTxt
        }
        // Fractional big unit -> also show the small unit (garlic bulb -> cloves).
        if (ing != null && isCountable(unit) && abs(amount - round(amount)) > 0.05) {
            val small = ing.pieceUnit
            if (small != null && small != unit) {
                convert(Qty(amount, unit, q.size), small, null, ing)?.let { s ->
                    return "$main (≈${fmtNumber(round(s))} ${Units.label(small, s)})"
                }
            }
        }
        return main
    }
}
