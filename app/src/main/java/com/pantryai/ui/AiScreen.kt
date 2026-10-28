@file:OptIn(ExperimentalMaterial3Api::class)

package com.pantryai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pantryai.ai.ModelManager
import com.pantryai.ai.ModelState
import com.pantryai.core.*
import com.pantryai.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun AiScreen(repo: Repository) {
    val state by repo.models.state.collectAsState()
    val data by repo.data.collectAsState()
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(repo.models.enabled) }
    var template by remember { mutableStateOf(repo.models.applyTemplate) }
    var customUrl by remember { mutableStateOf("") }
    var testQ by remember { mutableStateOf("Basmati rijst") }
    var testA by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { repo.models.importFrom(uri) }
    }

    Scaffold(topBar = { LargeTopAppBar(title = { Text("On-device AI") }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text(
                    "A small language model runs entirely on your phone — no account, no internet once downloaded. " +
                        "It decodes receipt abbreviations, recognises foods under other names (Basmati = long grain rice, Tomaat = Tomato) " +
                        "and pulls ingredients out of messy recipe text. Unit conversions (garlic → cloves, small → large eggs) " +
                        "use a built-in food database, so numbers are never made up.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        when (val s = state) {
                            is ModelState.None, is ModelState.Failed -> {
                                if (s is ModelState.Failed) Text(s.reason, color = MaterialTheme.colorScheme.error)
                                Text("No model installed", style = MaterialTheme.typography.titleMedium)
                                ModelManager.options.forEach { o ->
                                    Text(o.title, fontWeight = FontWeight.SemiBold)
                                    Text(o.description, style = MaterialTheme.typography.bodySmall)
                                    Button(onClick = { repo.models.download(o.url, o.fileName) }, modifier = Modifier.fillMaxWidth()) { Text("Download (${o.sizeMb} MB, Wi-Fi recommended)") }
                                }
                                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Use a .task model file from my phone") }
                                OutlinedTextField(customUrl, { customUrl = it }, label = { Text("…or a direct download link (.task)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                if (customUrl.startsWith("http")) TextButton(onClick = {
                                    repo.models.download(customUrl.trim(), customUrl.substringAfterLast('/').substringBefore('?').ifBlank { "model.task" })
                                }) { Text("Download from link") }
                            }
                            is ModelState.Downloading -> {
                                Text("Downloading model…", style = MaterialTheme.typography.titleMedium)
                                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                                Text(if (s.totalMb > 0) "${s.doneMb} / ${s.totalMb} MB" else "Starting…", style = MaterialTheme.typography.bodySmall)
                                Text("You can keep using the app; the download continues in the background.", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { repo.models.cancelDownload() }) { Text("Cancel") }
                            }
                            is ModelState.Copying -> {
                                Text("Importing ${s.name}…", style = MaterialTheme.typography.titleMedium)
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                            is ModelState.Installed -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("✦ " + File(s.path).nameWithoutExtension.substringBefore("_"), style = MaterialTheme.typography.titleMedium)
                                        Text("${s.sizeMb} MB · runs on this phone", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(enabled, { enabled = it; repo.models.enabled = it })
                                }
                                repo.llm.lastError?.let { Text("Last error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Use chat template", style = MaterialTheme.typography.bodyMedium)
                                        Text("Turn off only if answers look garbled", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(template, { template = it; repo.models.applyTemplate = it })
                                }
                                TextButton(onClick = { confirmDelete = true }) { Text("Remove model", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
            if (state is ModelState.Installed) item {
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Try it", style = MaterialTheme.typography.titleMedium)
                        Text("Type any food name — Dutch, English, abbreviated or misspelled.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(testQ, { testQ = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(enabled = !testing && testQ.isNotBlank(), onClick = {
                            testing = true; testA = null
                            scope.launch {
                                val t0 = System.currentTimeMillis()
                                val kbOnly = withContext(Dispatchers.Default) { repo.kb.best(testQ) }
                                val info = withContext(Dispatchers.Default) { repo.engine.askWhatIs(testQ) }
                                val res = withContext(Dispatchers.Default) { repo.engine.resolve(testQ) }
                                val ms = System.currentTimeMillis() - t0
                                testA = buildString {
                                    append("Knowledge base: ").append(kbOnly?.let { "${it.ingredient.en} (${"%.0f".format(it.score * 100)}% via ${it.via})" } ?: "no match").append('\n')
                                    append("AI says: ").append(info?.let { "${it.english} · ${it.category}${if (!it.food) " · not food" else ""}" } ?: "(no answer${repo.llm.lastError?.let { e -> ": $e" } ?: ""})").append('\n')
                                    append("Result: ").append(res.ingredient?.let { "${it.en} / ${it.nl}" } ?: "unknown").append(" [${res.via}]").append('\n')
                                    append("${ms} ms")
                                }
                                testing = false
                            }
                        }) { Text(if (testing) "Thinking…" else "Ask") }
                        testA?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item {
                Text("What the app has learned", style = MaterialTheme.typography.titleMedium)
                Text("Names the AI or you linked to an ingredient. Remove anything that's wrong.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (data.learnedAliases.isEmpty()) item { Text("Nothing yet.", style = MaterialTheme.typography.bodyMedium) }
            items(data.learnedAliases.entries.sortedBy { it.key }.toList(), key = { "a" + it.key }) { (alias, id) ->
                val ing = repo.kb.get(id)
                ListItem(
                    headlineContent = { Text("“$alias” → ${ing?.en ?: id}") },
                    supportingContent = if (ing?.learned == true) ({ Text("new ingredient · ${Categories.label(ing.category)}") }) else null,
                    trailingContent = { IconButton(onClick = { repo.forgetAlias(alias) }) { Icon(Icons.Default.Close, "Forget") } },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Remove the model?") },
        text = { Text("The app keeps working with its built-in food knowledge. You can download the model again later.") },
        confirmButton = { TextButton(onClick = { repo.models.delete(); confirmDelete = false }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}
