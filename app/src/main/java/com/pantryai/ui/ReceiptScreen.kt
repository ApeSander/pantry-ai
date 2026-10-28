@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.pantryai.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pantryai.ai.ReceiptOcr
import com.pantryai.core.*
import com.pantryai.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Editable state for one receipt line in the review list. */
private data class ReviewRow(
    val item: ReceiptItem,
    val ing: Ingredient?,
    val name: String,
    val amount: String,
    val unit: String,
    val size: String?,
    val include: Boolean,
    val answered: Boolean,
) {
    val needsAnswer get() = include && (parseAmount(amount) == null || (item.unclear && !answered))
}

@Composable
fun ReceiptScreen(repo: Repository, uri: Uri, onDone: (added: Int) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Reading the receipt…") }
    var rows by remember { mutableStateOf<List<ReviewRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showRaw by remember { mutableStateOf(false) }
    var rawText by remember { mutableStateOf<List<String>>(emptyList()) }
    var pickerFor by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(uri) {
        try {
            val lines = ReceiptOcr.readRows(context, uri)
            rawText = lines
            status = "Finding products…"
            val parsed = withContext(Dispatchers.Default) { ReceiptParser.parse(lines) }
            if (parsed.isEmpty()) { error = "I couldn't find any products on this photo. Try a sharper, straight photo with good light."; return@LaunchedEffect }
            val items = withContext(Dispatchers.Default) { repo.engine.receiptItems(parsed) { msg -> status = msg } }
            rows = items.map { ReviewRow(it, it.ingredient, it.name, amountText(it.amount), it.unit, it.size, it.include, false) }
        } catch (t: Throwable) {
            error = "Couldn't read the photo: ${t.message}"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receipt") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { if (rawText.isNotEmpty()) TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Items" else "Raw text") } },
            )
        },
        bottomBar = {
            rows?.let { list ->
                val included = list.count { it.include }
                val open = list.count { it.needsAnswer }
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                        if (open > 0) Text(
                            "$open ${if (open == 1) "question" else "questions"} left — tell me how much you bought",
                            color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Button(
                            enabled = open == 0 && included > 0, modifier = Modifier.fillMaxWidth().height(52.dp),
                            onClick = {
                                val items = list.filter { it.include }.map { r ->
                                    if (r.ing != null && r.item.ingredient?.id != r.ing.id) repo.correct(r.item.line.product, r.ing)
                                    repo.pantryItemFor(r.name, r.ing, parseAmount(r.amount)!!, r.unit, r.size)
                                }
                                repo.addPantry(items)
                                onDone(items.size)
                            },
                        ) { Text("Add $included ${if (included == 1) "item" else "items"} to pantry") }
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                error != null -> EmptyState("🧾", "Hmm", error!!)
                showRaw -> LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    items(rawText) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                }
                rows == null -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(status, style = MaterialTheme.typography.bodyLarge)
                    if (!repo.llm.ready) Text("Tip: turn on the on-device AI for better reading of abbreviations.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                }
                else -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val list = rows!!
                    val unclearIdx = list.indices.filter { list[it].item.unclear && list[it].include }
                    if (unclearIdx.isNotEmpty()) item { SectionHeader("❓ Needs your input") }
                    items(unclearIdx, key = { "u" + list[it].item.id }) { i ->
                        ReceiptRowCard(repo, list[i], onChange = { r -> rows = rows!!.toMutableList().also { it[i] = r } }, onPick = { pickerFor = i })
                    }
                    val rest = list.indices.filter { it !in unclearIdx }
                    if (rest.isNotEmpty()) item { SectionHeader("✓ Read from the receipt") }
                    items(rest, key = { "r" + list[it].item.id }) { i ->
                        ReceiptRowCard(repo, list[i], onChange = { r -> rows = rows!!.toMutableList().also { it[i] = r } }, onPick = { pickerFor = i })
                    }
                }
            }
        }
    }

    pickerFor?.let { i ->
        val r = rows!![i]
        IngredientPicker(repo, r.item.line.product, onDismiss = { pickerFor = null }) { picked ->
            rows = rows!!.toMutableList().also { it[i] = r.copy(ing = picked, name = cap(picked.en)) }
            pickerFor = null
        }
    }
}

@Composable
private fun ReceiptRowCard(repo: Repository, r: ReviewRow, onChange: (ReviewRow) -> Unit, onPick: () -> Unit) {
    val askMode = r.item.unclear && r.include
    val colors = if (askMode && !r.answered) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    Card(colors = colors, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = r.include, onCheckedChange = { onChange(r.copy(include = it)) })
                Text(Categories.emoji(r.ing?.category ?: "other"), fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(r.item.line.raw, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                ViaBadge(r.item.via)
            }
            if (!r.item.isFood && !r.include) Text("Looks like a non-food item — skipped.", style = MaterialTheme.typography.bodySmall)
            if (r.include) {
                if (askMode && !r.answered) {
                    Text("How much ${r.name.lowercase()} did you buy?", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repo.engine.suggestions(r.ing).forEach { q ->
                            SuggestionChip(onClick = {
                                onChange(r.copy(amount = amountText(q.amount), unit = q.unit, size = q.size ?: r.size, answered = true))
                            }, label = { Text(Convert.format(q, r.ing)) })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QuantityEditor(
                        r.amount, r.unit, r.size, r.ing,
                        onAmount = { onChange(r.copy(amount = it, answered = parseAmount(it) != null)) },
                        onUnit = { onChange(r.copy(unit = it, answered = r.answered || parseAmount(r.amount) != null)) },
                        onSize = { onChange(r.copy(size = it)) },
                        isError = r.needsAnswer,
                    )
                }
                TextButton(onClick = onPick, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(if (r.ing != null) "Not ${r.ing.en}? Change" else "Pick what this is")
                }
            }
        }
    }
}
