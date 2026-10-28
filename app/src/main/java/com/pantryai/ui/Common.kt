@file:OptIn(ExperimentalMaterial3Api::class)

package com.pantryai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pantryai.ai.ModelState
import com.pantryai.core.*
import com.pantryai.data.Repository

fun cap(s: String): String = com.pantryai.core.Text.capitalize(s)
fun normText(s: String): String = com.pantryai.core.Text.norm(s)

fun fmtQty(amount: Double?, unit: String, size: String?, ing: Ingredient?): String =
    if (amount == null) "to taste" else Convert.format(Qty(amount, unit, size), ing)

fun parseAmount(s: String): Double? = IngredientParser.num(s.trim().replace("½", " 1/2").replace("¼", " 1/4").replace("¾", " 3/4").trim())

fun amountText(a: Double?): String = a?.let { Convert.fmtNumber(it).replace("½", ".5").replace("¼", ".25").replace("¾", ".75").replace("⅓", ".33").replace("⅔", ".67").let { s -> if (s.startsWith(".")) "0$s" else s } } ?: ""

@Composable
fun AiChip(repo: Repository, onClick: () -> Unit) {
    val state by repo.models.state.collectAsState()
    val (txt, on) = when (state) {
        is ModelState.Installed -> (if (repo.models.enabled) "On-device AI" else "AI paused") to repo.models.enabled
        is ModelState.Downloading -> "Downloading AI…" to false
        else -> "AI off" to false
    }
    AssistChip(
        onClick = onClick,
        label = { Text(txt, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = {
            Box(
                Modifier.size(8.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
            )
        },
    )
}

/** Small badge that shows how an ingredient was understood. */
@Composable
fun ViaBadge(via: String) {
    val (label, color) = when (via) {
        "ai", "ai-new" -> "✦ AI" to MaterialTheme.colorScheme.primary
        "typo" -> "typo fixed" to MaterialTheme.colorScheme.secondary
        "guess" -> "guess?" to MaterialTheme.colorScheme.tertiary
        "unknown" -> "new item" to MaterialTheme.colorScheme.tertiary
        "learned" -> "learned" to MaterialTheme.colorScheme.primary
        "context" -> "✦ context" to MaterialTheme.colorScheme.primary
        "user" -> "you" to MaterialTheme.colorScheme.secondary
        else -> return
    }
    Text(
        label, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun EmptyState(emoji: String, title: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 48.sp)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

fun unitOptions(ing: Ingredient?): List<String> {
    val base = mutableListOf("g", "kg", "ml", "l", "pc", "tsp", "tbsp", "cup")
    ing?.units?.keys?.forEach { if (it !in base) base += it }
    ing?.buyUnit?.let { if (it !in base) base += it }
    listOf("can", "pack", "bunch", "jar").forEach { if (it !in base) base += it }
    return base
}

@Composable
fun Dropdown(label: String, options: List<String>, display: (String) -> String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.height(56.dp).padding(top = 6.dp)) {
            Text(label, maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(display(o)) }, onClick = { onPick(o); open = false }) }
        }
    }
}

/** amount field + unit dropdown + size dropdown (eggs). */
@Composable
fun QuantityEditor(
    amount: String, unit: String, size: String?, ing: Ingredient?,
    onAmount: (String) -> Unit, onUnit: (String) -> Unit, onSize: (String?) -> Unit,
    isError: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = amount, onValueChange = onAmount, label = { Text("Amount") }, singleLine = true, isError = isError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(110.dp),
        )
        Dropdown(Units.label(unit, 2.0), unitOptions(ing), { Units.label(it, 2.0) }, onUnit)
        if (ing?.sizes?.isNotEmpty() == true || (ing?.pieceGrams != null && unit == "pc")) {
            val sizes = if (ing.sizes.isNotEmpty()) ing.sizes.keys.toList() else listOf("S", "M", "L")
            Dropdown(size ?: "size", listOf("–") + sizes, { it }, { onSize(if (it == "–") null else it) })
        }
    }
}

/** Search the knowledge base and pick (or create) an ingredient. Choosing one teaches the app the name. */
@Composable
fun IngredientPicker(repo: Repository, initial: String, onDismiss: () -> Unit, onPick: (Ingredient) -> Unit) {
    var q by remember { mutableStateOf(initial) }
    val results = remember(q) { repo.kb.search(q, 30) }
    var newCat by remember { mutableStateOf("other") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What is “$initial”?") },
        text = {
            Column {
                OutlinedTextField(
                    q, { q = it }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
                    placeholder = { Text("Search ingredients (NL/EN)") }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(results, key = { it.id }) { ing ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(ing) }.padding(vertical = 10.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(Categories.emoji(ing.category), fontSize = 20.sp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(cap(ing.en), style = MaterialTheme.typography.bodyLarge)
                                if (!ing.nl.equals(ing.en, true)) Text(ing.nl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item {
                        if (q.isNotBlank()) {
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            Text("Not listed? Add “$q” as a new ingredient:", style = MaterialTheme.typography.bodySmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Dropdown(Categories.emoji(newCat) + " " + Categories.label(newCat), Categories.order, { Categories.emoji(it) + " " + Categories.label(it) }, { newCat = it })
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = { onPick(repo.createIngredient(q, newCat)) }, modifier = Modifier.padding(top = 6.dp)) { Text("Add") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun BusyOverlay(message: String) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
        Card(shape = RoundedCornerShape(20.dp)) {
            Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
                Text(message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(max = 240.dp))
            }
        }
    }
}
