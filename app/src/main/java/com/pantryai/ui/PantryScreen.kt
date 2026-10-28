@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.pantryai.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pantryai.core.*
import com.pantryai.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PantryScreen(repo: Repository, onScanCamera: () -> Unit, onScanGallery: () -> Unit, onOpenAi: () -> Unit) {
    val data by repo.data.collectAsState()
    var query by remember { mutableStateOf("") }
    var sheet by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PantryItem?>(null) }

    val filtered = data.pantry.filter { query.isBlank() || normText(it.name).contains(normText(query)) || repo.kb.get(it.ingredientId)?.nl?.let { nl -> normText(nl).contains(normText(query)) } == true }
    val groups = filtered.groupBy { it.category }.toSortedMap(compareBy { Categories.rank(it) })

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Pantry") },
                actions = { AiChip(repo, onOpenAi); Spacer(Modifier.width(8.dp)) },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { sheet = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add food") })
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (data.pantry.isNotEmpty()) item {
                OutlinedTextField(
                    query, { query = it }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
                    placeholder = { Text("Search ${data.pantry.size} items") }, shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (data.pantry.isEmpty()) item {
                EmptyState("🫙", "Your pantry is empty", "Scan a receipt or type what you have, like “1 bol knoflook” or “10 large eggs”.")
            }
            groups.forEach { (cat, items) ->
                item(key = "h_$cat") { SectionHeader("${Categories.emoji(cat)}  ${Categories.label(cat)}") }
                items(items.sortedBy { it.name.lowercase() }, key = { it.id }) { p ->
                    val ing = repo.kb.get(p.ingredientId)
                    val nl = ing?.nl?.takeIf { !it.equals(p.name, true) && !it.equals(ing.en, true) }
                    ListItem(
                        headlineContent = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = if (nl != null) ({ Text(nl) }) else null,
                        trailingContent = { Text(fmtQty(p.amount, p.unit, p.size, ing), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) },
                        modifier = Modifier.animateItem().clickable { editing = p },
                    )
                }
            }
        }
    }

    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }) {
        Column(Modifier.padding(bottom = 32.dp)) {
            Text("Add to pantry", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            SheetRow("📷", "Scan a receipt", "Take a photo — items, amounts and sizes are read for you") { sheet = false; onScanCamera() }
            SheetRow("🖼️", "Receipt from gallery", "Use a photo or screenshot you already have") { sheet = false; onScanGallery() }
            SheetRow("⌨️", "Type it", "“500 g basmati”, “2 bosjes koriander”, “6 large eggs”") { sheet = false; addOpen = true }
        }
    }
    if (addOpen) AddItemDialog(repo, onDismiss = { addOpen = false })
    editing?.let { p -> EditItemDialog(repo, p, onDismiss = { editing = null }) }
}

@Composable
private fun SheetRow(emoji: String, title: String, sub: String, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Text(emoji, fontSize = 26.sp) },
        headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(sub) },
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    )
}

/** One text field that understands "2 large eggs", "1 bol knoflook", "500g basmati". */
@Composable
fun AddItemDialog(repo: Repository, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var ing by remember { mutableStateOf<Ingredient?>(null) }
    var via by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("pc") }
    var size by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var touchedQty by remember { mutableStateOf(false) }

    LaunchedEffect(text) {
        if (text.isBlank()) { ing = null; name = ""; return@LaunchedEffect }
        val p = IngredientParser.parse(text)
        val res = withContext(Dispatchers.Default) { repo.engine.resolve(p.name, p.unit, allowAi = false) }
        ing = res.ingredient; via = res.via; name = p.name
        if (!touchedQty) {
            amount = amountText(p.amount)
            unit = if (res.ingredient?.pieceUnit != null && p.unit == "pc") res.ingredient!!.buyUnit ?: p.unit else p.unit
            size = p.size
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add food") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(text, { text = it; touchedQty = false }, label = { Text("What did you get?") }, placeholder = { Text("e.g. 1 kg basmati rijst") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (text.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (ing != null) Categories.emoji(ing!!.category) else "❔", fontSize = 22.sp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ing?.en?.let { cap(it) } ?: cap(name), style = MaterialTheme.typography.titleMedium)
                            Text(if (ing != null) "Recognised as ${ing!!.en} / ${ing!!.nl}" else "Not recognised yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ViaBadge(via)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { picker = true }) { Text("Change") }
                        if ((ing == null || via == "guess") && repo.llm.ready) TextButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                val r = withContext(Dispatchers.Default) { repo.engine.resolve(name, unit) }
                                ing = r.ingredient; via = r.via; busy = false
                            }
                        }) { Text(if (busy) "Thinking…" else "✦ Ask AI") }
                    }
                    QuantityEditor(amount, unit, size, ing, { amount = it; touchedQty = true }, { unit = it; touchedQty = true }, { size = it; touchedQty = true }, isError = parseAmount(amount) == null)
                    if (parseAmount(amount) == null) Text("How much? Enter an amount.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(enabled = text.isNotBlank() && parseAmount(amount) != null, onClick = {
                val a = parseAmount(amount) ?: return@Button
                val finalIng = ing
                repo.addPantry(listOf(repo.pantryItemFor(name, finalIng, a, unit, size)))
                onDismiss()
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (picker) IngredientPicker(repo, name, onDismiss = { picker = false }) { picked ->
        ing = picked; via = "user"; picker = false
        repo.correct(name, picked)
    }
}

@Composable
fun EditItemDialog(repo: Repository, item: PantryItem, onDismiss: () -> Unit) {
    val ing = repo.kb.get(item.ingredientId)
    var amount by remember { mutableStateOf(amountText(item.amount)) }
    var unit by remember { mutableStateOf(item.unit) }
    var size by remember { mutableStateOf(item.size) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ing?.let { Text("${Categories.emoji(it.category)} ${it.en} · ${it.nl}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                QuantityEditor(amount, unit, size, ing, { amount = it }, { newUnit ->
                    // Converting the unit converts the amount too: 1 bulb -> 10 cloves
                    val a = parseAmount(amount)
                    val conv = a?.let { Convert.convert(Qty(it, unit, size), newUnit, size, ing) }
                    if (conv != null) amount = amountText(conv)
                    unit = newUnit
                }, { size = it })
                Text("Changing the unit converts the amount for you.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(enabled = parseAmount(amount) != null, onClick = {
                val a = parseAmount(amount)!!
                if (a <= 0) repo.deletePantry(item.id) else repo.updatePantry(item.copy(amount = a, unit = unit, size = size))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { repo.deletePantry(item.id); onDismiss() }) { Text("Used up", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
