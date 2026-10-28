package com.pantryai.data

import android.content.Context
import com.pantryai.ai.LocalLlm
import com.pantryai.ai.ModelManager
import com.pantryai.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/** Single source of truth. Everything is kept in one JSON file; writes are debounced. */
class Repository(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val file = File(context.filesDir, "pantry.json")
    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> = _data

    val kb: Knowledge
    val llm = LocalLlm(context)
    val models = ModelManager(context, llm, scope)
    val engine: SmartEngine
    val planner: Planner

    init {
        val base = Knowledge.parse(context.assets.open("ingredients.json").bufferedReader().use { it.readText() })
        kb = Knowledge(base)
        val loaded = runCatching { if (file.exists()) AppData.fromJson(JSONObject(file.readText())) else null }.getOrNull()
        if (loaded != null) {
            loaded.learnedIngredients.forEach { kb.add(it) }
            loaded.learnedAliases.forEach { (a, id) -> kb.learnAlias(a, id) }
            _data.value = loaded
        }
        engine = SmartEngine(
            kb, llm,
            onLearnAlias = { alias, id -> update { it.copy(learnedAliases = it.learnedAliases + (Text.norm(alias) to id)) } },
            onLearnIngredient = { ing -> update { d -> d.copy(learnedIngredients = d.learnedIngredients.filter { it.id != ing.id } + ing) } },
        )
        planner = Planner(kb)
        models.init()
        startAutosave()
    }

    @OptIn(FlowPreview::class)
    private fun startAutosave() {
        scope.launch(Dispatchers.IO) {
            _data.drop(1).debounce(400).collect { d -> save(d) }
        }
    }

    private fun save(d: AppData) {
        val tmp = File(file.parentFile, "pantry.json.tmp")
        tmp.writeText(d.toJson().toString())
        tmp.renameTo(file)
    }

    @Synchronized
    fun update(f: (AppData) -> AppData) { _data.value = f(_data.value) }

    // ----- pantry -----
    fun addPantry(items: List<PantryItem>) = update { d -> d.copy(pantry = items.fold(d.pantry) { acc, it -> planner.addToPantry(acc, it) }) }
    fun updatePantry(item: PantryItem) = update { d -> d.copy(pantry = d.pantry.map { if (it.id == item.id) item else it }) }
    fun deletePantry(id: String) = update { d -> d.copy(pantry = d.pantry.filter { it.id != id }) }

    fun pantryItemFor(name: String, ing: Ingredient?, amount: Double, unit: String, size: String?): PantryItem =
        PantryItem(
            ingredientId = ing?.id, name = ing?.en?.let { Text.capitalize(it) } ?: Text.capitalize(name.trim()),
            amount = amount, unit = unit, size = size, category = ing?.category ?: "other",
        )

    // ----- recipes -----
    fun saveRecipe(r: Recipe) = update { d ->
        val exists = d.recipes.any { it.id == r.id }
        d.copy(recipes = if (exists) d.recipes.map { if (it.id == r.id) r else it } else listOf(r) + d.recipes)
    }
    fun deleteRecipe(id: String) = update { d -> d.copy(recipes = d.recipes.filter { it.id != id }) }

    fun markMade(recipe: Recipe, deductions: List<Deduction>) = update { d ->
        d.copy(
            pantry = planner.applyDeductions(d.pantry, deductions),
            recipes = d.recipes.map { if (it.id == recipe.id) it.copy(timesMade = it.timesMade + 1) else it },
        )
    }

    // ----- shopping -----
    fun setShopping(items: List<ShoppingItem>) = update { it.copy(shopping = items) }
    fun addShopping(items: List<ShoppingItem>) = update { d ->
        // merge same ingredient+unit
        var list = d.shopping
        for (n in items) {
            val idx = list.indexOfFirst { !it.checked && it.unit == n.unit && it.size == n.size && (if (n.ingredientId != null) it.ingredientId == n.ingredientId else Text.norm(it.name) == Text.norm(n.name)) }
            list = if (idx >= 0 && n.amount != null) list.toMutableList().also { l -> l[idx] = l[idx].copy(amount = (l[idx].amount ?: 0.0) + n.amount, note = listOf(l[idx].note, n.note).filter { s -> s.isNotBlank() }.distinct().joinToString("; ")) }
            else list + n
        }
        d.copy(shopping = list)
    }
    fun toggleShopping(id: String) = update { d -> d.copy(shopping = d.shopping.map { if (it.id == id) it.copy(checked = !it.checked) else it }) }
    fun deleteShopping(id: String) = update { d -> d.copy(shopping = d.shopping.filter { it.id != id }) }
    fun clearCheckedShopping() = update { d -> d.copy(shopping = d.shopping.filter { !it.checked }) }

    /** Move bought (checked) items with an amount into the pantry. */
    fun checkedToPantry(): Int {
        var n = 0
        update { d ->
            val bought = d.shopping.filter { it.checked && it.amount != null }
            n = bought.size
            val items = bought.map { s -> pantryItemFor(s.name, kb.get(s.ingredientId), s.amount!!, s.unit, s.size) }
            d.copy(pantry = items.fold(d.pantry) { acc, it -> planner.addToPantry(acc, it) }, shopping = d.shopping.filter { !it.checked })
        }
        return n
    }

    // ----- learning -----
    fun correct(rawName: String, ingredient: Ingredient) {
        kb.learnAlias(rawName, ingredient.id)
        update { it.copy(learnedAliases = it.learnedAliases + (Text.norm(rawName) to ingredient.id)) }
    }

    fun forgetAlias(alias: String) {
        kb.forgetAlias(alias)
        update { it.copy(learnedAliases = it.learnedAliases - alias) }
    }

    /** Create a brand-new ingredient the user typed that the KB doesn't know. */
    fun createIngredient(name: String, category: String): Ingredient {
        val clean = Text.norm(name)
        val ing = Ingredient(id = Knowledge.slug(clean), en = clean, nl = clean, category = category, base = "g", learned = true)
        kb.get(ing.id)?.let { return it }
        kb.add(ing)
        update { d -> d.copy(learnedIngredients = d.learnedIngredients + ing) }
        return ing
    }

    fun removeLearnedIngredient(id: String) {
        kb.removeLearned(id)
        update { d -> d.copy(learnedIngredients = d.learnedIngredients.filter { it.id != id }, learnedAliases = d.learnedAliases.filterValues { it != id }) }
    }
}
