@file:OptIn(ExperimentalMaterial3Api::class)

package com.pantryai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pantryai.core.*
import com.pantryai.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ShoppingScreen(repo: Repository, onToast: (String) -> Unit, onOpenAi: () -> Unit) {
    val data by repo.data.collectAsState()
    val scope = rememberCoroutineScope()
    var planOpen by remember { mutableStateOf(false) }
    var newItem by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val checked = data.shopping.count { it.checked }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Shopping list") },
                actions = {
                    AiChip(repo, onOpenAi)
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Clear ticked items") }, onClick = { menu = false; repo.clearCheckedShopping() })
                        DropdownMenuItem(text = { Text("Clear whole list") }, onClick = { menu = false; repo.setShopping(emptyList()) })
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { planOpen = true }, icon = { Icon(Icons.Default.List, null) }, text = { Text("Plan from recipes") })
        },
        bottomBar = {
            if (checked > 0) Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = { val n = repo.checkedToPantry(); onToast("Moved $n ${if (n == 1) "item" else "items"} to your pantry") },
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp).height(52.dp),
                ) { Icon(Icons.Default.Done, null); Spacer(Modifier.width(8.dp)); Text("Bought $checked — add to pantry") }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newItem, { newItem = it }, placeholder = { Text("Add item, e.g. 2 pakken melk") }, singleLine = true, modifier = Modifier.weight(1f))
                    IconButton(enabled = newItem.isNotBlank(), onClick = {
                        val line = newItem; newItem = ""
                        scope.launch {
                            val ri = withContext(Dispatchers.Default) { repo.engine.fromParsed(IngredientParser.parse(line)) }
                            val ing = repo.kb.get(ri.ingredientId)
                            val hasAmount = Regex("\\d").containsMatchIn(line)
                            repo.addShopping(listOf(ShoppingItem(name = cap(ri.name), ingredientId = ri.ingredientId, amount = if (hasAmount) ri.amount else null, unit = ri.unit, size = ri.size, category = ing?.category ?: "other")))
                        }
                    }) { Icon(Icons.Default.Add, "Add") }
                }
            }
            if (data.shopping.isEmpty()) item {
                EmptyState("🛒", "Nothing to buy", "Pick one or more recipes and the number of portions — I'll work out what's missing from your pantry.")
            }
            val groups = data.shopping.groupBy { it.category }.toSortedMap(compareBy { Categories.rank(it) })
            groups.forEach { (cat, list) ->
                item(key = "h$cat") { SectionHeader("${Categories.emoji(cat)}  ${Categories.label(cat)}") }
                items(list.sortedBy { it.checked }, key = { it.id }) { s ->
                    val ing = repo.kb.get(s.ingredientId)
                    ListItem(
                        leadingContent = { Checkbox(s.checked, { repo.toggleShopping(s.id) }) },
                        headlineContent = {
                            Text(
                                buildString { if (s.amount != null) { append(fmtQty(s.amount, s.unit, s.size, ing)); append("  ") }; append(s.name) },
                                textDecoration = if (s.checked) TextDecoration.LineThrough else null,
                                fontWeight = FontWeight.Medium,
                            )
                        },
                        supportingContent = if (s.note.isNotBlank()) ({ Text(s.note, maxLines = 1) }) else null,
                        trailingContent = { IconButton(onClick = { repo.deleteShopping(s.id) }) { Icon(Icons.Default.Close, "Remove") } },
                        modifier = Modifier.animateItem().clickable { repo.toggleShopping(s.id) },
                    )
                }
            }
        }
    }

    if (planOpen) PlanDialog(repo, onDismiss = { planOpen = false }) { selection, replace ->
        val needs = repo.planner.shoppingList(selection, repo.data.value.pantry)
        val items = needs.map { ShoppingItem(name = cap(it.name), ingredientId = it.ingredientId, amount = it.amount, unit = it.unit, size = it.size, note = it.note, category = it.category) }
        if (replace) repo.setShopping(items) else repo.addShopping(items)
        planOpen = false
        onToast(if (items.isEmpty()) "You already have everything for this!" else "${items.size} items on your list")
    }
}

@Composable
private fun PlanDialog(repo: Repository, onDismiss: () -> Unit, onGenerate: (List<Pair<Recipe, Int>>, Boolean) -> Unit) {
    val data by repo.data.collectAsState()
    val portions = remember { mutableStateMapOf<String, Int>() }
    var replace by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What are you cooking?") },
        text = {
            if (data.recipes.isEmpty()) Text("Add some recipes first.")
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(data.recipes, key = { it.id }) { r ->
                    val p = portions[r.id]
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(p != null, { on -> if (on) portions[r.id] = r.servings else portions.remove(r.id) })
                        Text(r.title, modifier = Modifier.weight(1f), maxLines = 2)
                        if (p != null) {
                            IconButton(onClick = { if (p > 1) portions[r.id] = p - 1 }, modifier = Modifier.size(32.dp)) { Text("–", fontSize = 18.sp) }
                            Text("$p", fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp), maxLines = 1)
                            IconButton(onClick = { portions[r.id] = p + 1 }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) }
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Switch(replace, { replace = it })
                        Spacer(Modifier.width(8.dp))
                        Text(if (replace) "Replace current list" else "Add to current list", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = portions.isNotEmpty(), onClick = {
                val sel = data.recipes.filter { it.id in portions }.map { it to portions[it.id]!! }
                onGenerate(sel, replace)
            }) { Text("Make list") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
