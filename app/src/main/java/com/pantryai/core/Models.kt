package com.pantryai.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().take(12)

private fun JSONObject.optStr(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
private fun JSONObject.optDbl(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null

data class PantryItem(
    val id: String = newId(),
    val ingredientId: String?,
    val name: String,
    val amount: Double,
    val unit: String,
    val size: String? = null,
    val category: String = "other",
    val addedAt: Long = System.currentTimeMillis(),
) {
    val qty get() = Qty(amount, unit, size)
    fun toJson() = JSONObject().apply {
        put("id", id); put("ing", ingredientId ?: JSONObject.NULL); put("name", name); put("amount", amount)
        put("unit", unit); put("size", size ?: JSONObject.NULL); put("cat", category); put("added", addedAt)
    }
    companion object {
        fun fromJson(o: JSONObject) = PantryItem(
            o.getString("id"), o.optStr("ing"), o.getString("name"), o.getDouble("amount"), o.getString("unit"),
            o.optStr("size"), o.optString("cat", "other"), o.optLong("added", 0),
        )
    }
}

data class RecipeIngredient(
    val raw: String,
    val name: String,
    val ingredientId: String?,
    /** null = "to taste" / unquantified */
    val amount: Double?,
    val unit: String,
    val size: String? = null,
    val note: String = "",
    /** How the ingredient was resolved: exact, plural, typo, ai, user... */
    val via: String = "",
    val optional: Boolean = false,
) {
    val qty get() = amount?.let { Qty(it, unit, size) }
    fun toJson() = JSONObject().apply {
        put("raw", raw); put("name", name); put("ing", ingredientId ?: JSONObject.NULL)
        put("amount", amount ?: JSONObject.NULL); put("unit", unit); put("size", size ?: JSONObject.NULL)
        put("note", note); put("via", via); put("opt", optional)
    }
    companion object {
        fun fromJson(o: JSONObject) = RecipeIngredient(
            o.optString("raw"), o.getString("name"), o.optStr("ing"), o.optDbl("amount"), o.optString("unit", "pc"),
            o.optStr("size"), o.optString("note"), o.optString("via"), o.optBoolean("opt", false),
        )
    }
}

data class Recipe(
    val id: String = newId(),
    val title: String,
    val servings: Int,
    val ingredients: List<RecipeIngredient>,
    val steps: String = "",
    val source: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val timesMade: Int = 0,
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("title", title); put("servings", servings)
        put("ingredients", JSONArray(ingredients.map { it.toJson() }))
        put("steps", steps); put("source", source); put("created", createdAt); put("made", timesMade)
    }
    companion object {
        fun fromJson(o: JSONObject): Recipe {
            val arr = o.getJSONArray("ingredients")
            return Recipe(
                o.getString("id"), o.getString("title"), o.optInt("servings", 2),
                (0 until arr.length()).map { RecipeIngredient.fromJson(arr.getJSONObject(it)) },
                o.optString("steps"), o.optString("source"), o.optLong("created", 0), o.optInt("made", 0),
            )
        }
    }
}

data class ShoppingItem(
    val id: String = newId(),
    val name: String,
    val ingredientId: String?,
    val amount: Double?,
    val unit: String,
    val size: String? = null,
    val checked: Boolean = false,
    val note: String = "",
    val category: String = "other",
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("ing", ingredientId ?: JSONObject.NULL)
        put("amount", amount ?: JSONObject.NULL); put("unit", unit); put("size", size ?: JSONObject.NULL)
        put("checked", checked); put("note", note); put("cat", category)
    }
    companion object {
        fun fromJson(o: JSONObject) = ShoppingItem(
            o.getString("id"), o.getString("name"), o.optStr("ing"), o.optDbl("amount"), o.optString("unit", "pc"),
            o.optStr("size"), o.optBoolean("checked"), o.optString("note"), o.optString("cat", "other"),
        )
    }
}

data class AppData(
    val pantry: List<PantryItem> = emptyList(),
    val recipes: List<Recipe> = emptyList(),
    val shopping: List<ShoppingItem> = emptyList(),
    val learnedIngredients: List<Ingredient> = emptyList(),
    val learnedAliases: Map<String, String> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1)
        put("pantry", JSONArray(pantry.map { it.toJson() }))
        put("recipes", JSONArray(recipes.map { it.toJson() }))
        put("shopping", JSONArray(shopping.map { it.toJson() }))
        put("learned", JSONArray(learnedIngredients.map { it.toJson() }))
        put("aliases", JSONObject(learnedAliases as Map<*, *>))
    }
    companion object {
        fun fromJson(o: JSONObject): AppData {
            fun <T> list(k: String, f: (JSONObject) -> T): List<T> {
                val a = o.optJSONArray(k) ?: return emptyList()
                return (0 until a.length()).mapNotNull { runCatching { f(a.getJSONObject(it)) }.getOrNull() }
            }
            val al = o.optJSONObject("aliases")
            return AppData(
                list("pantry", PantryItem::fromJson), list("recipes", Recipe::fromJson), list("shopping", ShoppingItem::fromJson),
                list("learned", Ingredient::fromJson),
                al?.keys()?.asSequence()?.associateWith { al.getString(it) } ?: emptyMap(),
            )
        }
    }
}
