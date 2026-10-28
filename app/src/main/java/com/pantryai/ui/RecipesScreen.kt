@file:OptIn(ExperimentalMaterial3Api::class)

package com.pantryai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pantryai.core.*
import com.pantryai.data.Repository

@Composable
fun RecipesScreen(repo: Repository, onOpen: (String) -> Unit, onAdd: () -> Unit, onOpenAi: () -> Unit) {
    val data by repo.data.collectAsState()
    Scaffold(
        topBar = { LargeTopAppBar(title = { Text("Recipes") }, actions = { AiChip(repo, onOpenAi); Spacer(Modifier.width(8.dp)) }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add recipe") }) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (data.recipes.isEmpty()) item {
                EmptyState("📖", "No recipes yet", "Paste a link from Allerhande, Leukerecepten or any recipe site, or paste the recipe text. The AI pulls out the ingredients.")
            }
            items(data.recipes, key = { it.id }) { r ->
                val counted = r.ingredients.filter { repo.kb.get(it.ingredientId)?.staple != true }
                val have = counted.count { repo.planner.availability(it, data.pantry).status.let { s -> s == Have.ENOUGH || s == Have.STAPLE } }
                Card(Modifier.fillMaxWidth().clickable { onOpen(r.id) }, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(r.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("👥 ${r.servings}   ·   ${counted.size} ingredients", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.weight(1f))
                            val ratio = if (counted.isEmpty()) 1f else have.toFloat() / counted.size
                            Text(
                                if (have == counted.size) "✓ ready to cook" else "have $have/${counted.size}",
                                color = if (have == counted.size) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge,
                            )
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(progress = { ratio }, modifier = Modifier.size(22.dp), strokeWidth = 3.dp, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun statusColor(h: Have): Color = when (h) {
    Have.ENOUGH, Have.STAPLE -> MaterialTheme.colorScheme.primary
    Have.PARTIAL, Have.UNKNOWN -> MaterialTheme.colorScheme.secondary
    Have.MISSING -> MaterialTheme.colorScheme.tertiary
}

fun statusIcon(h: Have) = when (h) {
    Have.ENOUGH, Have.STAPLE -> "✓"
    Have.PARTIAL -> "◐"
    Have.UNKNOWN -> "?"
    Have.MISSING -> "✗"
}

@Composable
fun RecipeDetailScreen(repo: Repository, id: String, onBack: () -> Unit, onEdit: (String) -> Unit, onToast: (String) -> Unit) {
    val data by repo.data.collectAsState()
    val recipe = data.recipes.firstOrNull { it.id == id } ?: run { LaunchedEffect(Unit) { onBack() }; return }
    var portions by remember(id) { mutableIntStateOf(recipe.servings) }
    var made by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scale = portions.toDouble() / recipe.servings.coerceAtLeast(1)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(recipe.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onEdit(id) })
                        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; confirmDelete = true })
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        val needs = repo.planner.shoppingList(listOf(recipe to portions), data.pantry)
                        repo.addShopping(needs.map { ShoppingItem(name = cap(it.name), ingredientId = it.ingredientId, amount = it.amount, unit = it.unit, size = it.size, note = it.note, category = it.category) })
                        onToast(if (needs.isEmpty()) "You have everything!" else "Added ${needs.size} items to the shopping list")
                    }, modifier = Modifier.weight(1f).height(52.dp)) { Icon(Icons.Default.ShoppingCart, null); Spacer(Modifier.width(6.dp)); Text("Missing → list") }
                    Button(onClick = { made = true }, modifier = Modifier.weight(1f).height(52.dp)) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(6.dp)); Text("I made this") }
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Portions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    FilledTonalIconButton(onClick = { if (portions > 1) portions-- }) { Text("–", fontSize = 20.sp) }
                    Text("$portions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 14.dp))
                    FilledTonalIconButton(onClick = { portions++ }) { Icon(Icons.Default.Add, null) }
                }
                if (recipe.source.startsWith("http")) Text(recipe.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
                SectionHeader("Ingredients")
            }
            items(recipe.ingredients) { ri ->
                val ing = repo.kb.get(ri.ingredientId)
                val av = repo.planner.availability(ri, data.pantry, scale)
                val scaled = ri.amount?.times(scale)
                ListItem(
                    leadingContent = { Text(statusIcon(av.status), color = statusColor(av.status), fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(buildString {
                                append(fmtQty(scaled, ri.unit, ri.size, ing)); append("  "); append(cap(ri.name))
                            }, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(6.dp)); ViaBadge(ri.via)
                        }
                    },
                    supportingContent = { Text(listOfNotNull(ri.note.takeIf { it.isNotBlank() }, av.text).joinToString(" · ")) },
                )
            }
            if (recipe.steps.isNotBlank()) {
                item { SectionHeader("Method") }
                item { Text(recipe.steps, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp)) }
            }
        }
    }

    if (made) MadeDialog(repo, recipe, portions, onDismiss = { made = false }, onDone = { n ->
        made = false
        onToast("Pantry updated — $n ${if (n == 1) "item" else "items"} used")
    })
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete recipe?") },
        confirmButton = { TextButton(onClick = { repo.deleteRecipe(id); confirmDelete = false; onBack() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

/** Preview of what "I made this" removes, with smart conversions shown, so nothing surprising happens. */
@Composable
fun MadeDialog(repo: Repository, recipe: Recipe, portions: Int, onDismiss: () -> Unit, onDone: (Int) -> Unit) {
    val data by repo.data.collectAsState()
    val plans = remember(recipe, portions) { repo.planner.planDeduction(recipe, portions, data.pantry) }
    val skipped = remember { mutableStateListOf<Int>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Used for ${recipe.title}") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                item { Text("$portions ${if (portions == 1) "portion" else "portions"} · untick anything you didn't use", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(plans.indices.toList()) { i ->
                    val p = plans[i]
                    val ing = repo.kb.get(p.recipeIngredient.ingredientId)
                    if (ing?.staple == true) return@items
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        val usable = p.deductions.isNotEmpty()
                        Checkbox(checked = usable && i !in skipped, enabled = usable, onCheckedChange = { if (it) skipped.remove(i) else skipped.add(i) })
                        Column(Modifier.weight(1f).padding(top = 12.dp)) {
                            Text(cap(p.recipeIngredient.name), fontWeight = FontWeight.SemiBold)
                            p.deductions.forEach { d ->
                                Text("−${d.explanation}  ·  ${fmtQty(d.remaining, d.unit, d.size, ing)} left", style = MaterialTheme.typography.bodySmall)
                            }
                            when {
                                p.scaledNeed == null -> Text("no amount given — not deducted", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                p.deductions.isEmpty() -> Text("not in pantry", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                                p.missing > 0 -> Text("short by ${fmtQty(p.missing, p.scaledNeed.unit, p.scaledNeed.size, ing)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                            p.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val deds = plans.filterIndexed { i, _ -> i !in skipped }.flatMap { it.deductions }
                repo.markMade(recipe, deds)
                onDone(deds.map { it.pantryItemId }.distinct().size)
            }) { Text("Update pantry") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
