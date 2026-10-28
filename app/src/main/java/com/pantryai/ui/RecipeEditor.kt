@file:OptIn(ExperimentalMaterial3Api::class)

package com.pantryai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pantryai.core.*
import com.pantryai.data.RecipeImporter
import com.pantryai.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RecipeEditorScreen(repo: Repository, editId: String?, sharedText: String?, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val existing = editId?.let { id -> repo.data.value.recipes.firstOrNull { it.id == id } }
    var mode by remember { mutableIntStateOf(if (sharedText != null && !sharedText.trim().startsWith("http") && sharedText.contains('\n')) 1 else 0) }
    var link by remember { mutableStateOf(sharedText?.let { Regex("https?://\\S+").find(it)?.value } ?: "") }
    var pasted by remember { mutableStateOf(if (mode == 1) sharedText ?: "" else "") }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var servings by remember { mutableStateOf(existing?.servings?.toString() ?: "2") }
    var steps by remember { mutableStateOf(existing?.steps ?: "") }
    var source by remember { mutableStateOf(existing?.source ?: "") }
    val ings = remember { mutableStateListOf<RecipeIngredient>().also { l -> existing?.ingredients?.let { l.addAll(it) } } }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editIdx by remember { mutableStateOf<Int?>(null) }
    var newLine by remember { mutableStateOf("") }

    fun extract() {
        error = null
        scope.launch {
            try {
                if (mode == 0) {
                    busy = "Opening the recipe page…"
                    val page = withContext(Dispatchers.IO) { RecipeImporter.fetch(link) }
                    source = page.finalUrl
                    val s = RecipeImporter.structured(page)
                    val lines: List<String>
                    if (s != null && s.ingredients.isNotEmpty()) {
                        title = s.title; s.servings?.let { servings = it.toString() }; steps = s.steps
                        lines = s.ingredients
                    } else {
                        busy = if (repo.llm.ready) "No recipe data on this page — asking the on-device AI…" else "Looking for ingredients…"
                        title = RecipeJsonLd.title(page.html) ?: title
                        lines = withContext(Dispatchers.Default) { repo.engine.extractIngredientLines(RecipeJsonLd.visibleText(page.html)) }
                    }
                    val res = withContext(Dispatchers.Default) { repo.engine.recipeIngredients(lines) { busy = it } }
                    ings.clear(); ings.addAll(res)
                } else {
                    busy = "Finding the ingredients…"
                    val firstLine = pasted.lines().firstOrNull { it.isNotBlank() }?.trim()
                    if (title.isBlank() && firstLine != null && !IngredientParser.looksLikeIngredient(firstLine) && firstLine.length < 70) title = firstLine
                    Regex("(\\d+)\\s*(personen|persons|people|servings|porties|pers)", RegexOption.IGNORE_CASE).find(pasted)?.let { servings = it.groupValues[1] }
                    val lines = withContext(Dispatchers.Default) { repo.engine.extractIngredientLines(pasted) }
                    val res = withContext(Dispatchers.Default) { repo.engine.recipeIngredients(lines) { busy = it } }
                    ings.clear(); ings.addAll(res)
                    val stepIdx = pasted.lines().indexOfFirst { Regex("^(bereiding|instructions|method|werkwijze|directions)", RegexOption.IGNORE_CASE).containsMatchIn(it.trim()) }
                    if (stepIdx >= 0 && steps.isBlank()) steps = pasted.lines().drop(stepIdx + 1).joinToString("\n").trim()
                }
                if (ings.isEmpty()) error = "I couldn't find ingredients. Try pasting the ingredient list as text."
            } catch (t: Throwable) {
                error = "Couldn't load that: ${t.message}"
            } finally { busy = null }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (existing != null) "Edit recipe" else "New recipe") },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = {
                        TextButton(enabled = ings.isNotEmpty() && title.isNotBlank(), onClick = {
                            val r = (existing ?: Recipe(title = title, servings = 2, ingredients = emptyList())).copy(
                                title = title.trim(), servings = servings.toIntOrNull()?.coerceIn(1, 100) ?: 2, ingredients = ings.toList(), steps = steps, source = source,
                            )
                            repo.saveRecipe(r); onSaved(r.id)
                        }) { Text("Save", fontWeight = FontWeight.Bold) }
                    },
                )
            },
        ) { pad ->
            LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (existing == null) item {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(mode == 0, { mode = 0 }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("From a link") }
                        SegmentedButton(mode == 1, { mode = 1 }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Paste text") }
                    }
                }
                if (existing == null) item {
                    if (mode == 0) OutlinedTextField(link, { link = it }, label = { Text("Recipe link") }, placeholder = { Text("https://www.ah.nl/allerhande/recept/…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    else OutlinedTextField(pasted, { pasted = it }, label = { Text("Recipe text") }, placeholder = { Text("Paste the whole recipe — messy is fine") }, minLines = 5, maxLines = 12, modifier = Modifier.fillMaxWidth())
                }
                if (existing == null) item {
                    Button(onClick = { extract() }, enabled = busy == null && (if (mode == 0) link.isNotBlank() else pasted.isNotBlank()), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Text(if (repo.llm.ready) "✦ Extract ingredients" else "Extract ingredients")
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
                }
                if (ings.isNotEmpty() || existing != null) {
                    item {
                        OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    item {
                        OutlinedTextField(servings, { servings = it.filter(Char::isDigit).take(3) }, label = { Text("Serves") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(140.dp))
                    }
                    item { SectionHeader("Ingredients — tap to fix", Modifier.padding(start = 0.dp)) }
                    itemsIndexed(ings) { i, ri ->
                        val ing = repo.kb.get(ri.ingredientId)
                        Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), onClick = { editIdx = i }) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(Categories.emoji(ing?.category ?: "other"), fontSize = 20.sp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("${fmtQty(ri.amount, ri.unit, ri.size, ing)}  ${cap(ri.name)}", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, false))
                                        Spacer(Modifier.width(6.dp)); ViaBadge(ri.via)
                                    }
                                    if (ri.raw.isNotBlank() && normText(ri.raw) != normText("${amountText(ri.amount)} ${ri.name}")) Text(ri.raw, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                                IconButton(onClick = { ings.removeAt(i) }) { Icon(Icons.Default.Close, "Remove") }
                            }
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(newLine, { newLine = it }, placeholder = { Text("Add: 2 el sojasaus") }, singleLine = true, modifier = Modifier.weight(1f))
                            IconButton(enabled = newLine.isNotBlank(), onClick = {
                                val line = newLine; newLine = ""
                                scope.launch { val ri = withContext(Dispatchers.Default) { repo.engine.fromParsed(IngredientParser.parse(line)) }; ings.add(ri) }
                            }) { Icon(Icons.Default.Add, "Add") }
                        }
                    }
                    item { SectionHeader("Method (optional)", Modifier.padding(start = 0.dp)) }
                    item { OutlinedTextField(steps, { steps = it }, minLines = 3, modifier = Modifier.fillMaxWidth()) }
                }
            }
        }
        busy?.let { BusyOverlay(it) }
    }

    editIdx?.let { i ->
        if (i < ings.size) IngredientEditDialog(repo, ings[i], onDismiss = { editIdx = null }) { updated -> ings[i] = updated; editIdx = null }
    }
}

@Composable
fun IngredientEditDialog(repo: Repository, ri: RecipeIngredient, onDismiss: () -> Unit, onSave: (RecipeIngredient) -> Unit) {
    var ing by remember { mutableStateOf(repo.kb.get(ri.ingredientId)) }
    var name by remember { mutableStateOf(ri.name) }
    var amount by remember { mutableStateOf(amountText(ri.amount)) }
    var unit by remember { mutableStateOf(ri.unit) }
    var size by remember { mutableStateOf(ri.size) }
    var picker by remember { mutableStateOf(false) }
    var via by remember { mutableStateOf(ri.via) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ingredient") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ri.raw.isNotBlank()) Text("“${ri.raw}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedCard(onClick = { picker = true }) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(Categories.emoji(ing?.category ?: "other"), fontSize = 22.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(cap(ing?.en ?: name), style = MaterialTheme.typography.titleMedium)
                            Text(if (ing != null) "Matches pantry items called “${ing!!.nl}” or “${ing!!.en}”" else "Not linked to a known ingredient", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Default.Edit, "Change")
                    }
                }
                QuantityEditor(amount, unit, size, ing, { amount = it }, { unit = it }, { size = it })
                Text("Leave the amount empty for “to taste”.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(ri.copy(name = ing?.en ?: name, ingredientId = ing?.id, amount = parseAmount(amount), unit = unit, size = size, via = via))
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (picker) IngredientPicker(repo, ri.raw.ifBlank { name }.let { IngredientParser.parse(it).name }, onDismiss = { picker = false }) { picked ->
        repo.correct(IngredientParser.parse(ri.raw.ifBlank { name }).name, picked)
        ing = picked; name = picked.en; via = "user"; picker = false
    }
}
