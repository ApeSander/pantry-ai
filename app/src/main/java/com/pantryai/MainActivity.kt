@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.pantryai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import com.pantryai.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class Route {
    object Tabs : Route()
    data class Receipt(val uri: Uri) : Route()
    data class RecipeDetail(val id: String) : Route()
    data class RecipeEditor(val editId: String? = null, val shared: String? = null) : Route()
}

class MainActivity : ComponentActivity() {
    private val sharedText = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        val repo = (application as PantryApp).repo
        setContent {
            PantryTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(repo = repo, sharedFlow = sharedText)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            sharedText.value = intent.getStringExtra(Intent.EXTRA_TEXT)
        }
    }
}

@Composable
fun AppRoot(repo: com.pantryai.data.Repository, sharedFlow: MutableStateFlow<String?>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val stack = remember { mutableStateListOf<Route>(Route.Tabs) }
    val route = stack.last()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun toast(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }
    fun push(r: Route) { stack.add(r) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    val shared by sharedFlow.collectAsState()
    LaunchedEffect(shared) {
        shared?.let { push(Route.RecipeEditor(shared = it)); sharedFlow.value = null }
    }

    var pendingPhoto by remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = pendingPhoto
        if (ok && u != null) push(Route.Receipt(u))
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) push(Route.Receipt(uri))
    }
    fun scanCamera() {
        val dir = File(context.cacheDir, "receipts").also { it.mkdirs() }
        val f = File(dir, "receipt_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        pendingPhoto = uri
        takePicture.launch(uri)
    }
    fun scanGallery() = pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    BackHandler(enabled = stack.size > 1) { pop() }

    Box(Modifier.fillMaxSize()) {
        when (route) {
            is Route.Tabs -> Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Home, null) }, label = { Text("Pantry") })
                        NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.Favorite, null) }, label = { Text("Recipes") })
                        NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.ShoppingCart, null) }, label = { Text("Shopping") })
                        NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Default.Star, null) }, label = { Text("AI") })
                    }
                },
                snackbarHost = { SnackbarHost(snackbar) },
                contentWindowInsets = WindowInsets(0),
            ) { pad ->
                Box(Modifier.padding(pad).consumeWindowInsets(pad)) {
                    when (tab) {
                        0 -> PantryScreen(repo, ::scanCamera, ::scanGallery, onOpenAi = { tab = 3 })
                        1 -> RecipesScreen(repo, onOpen = { push(Route.RecipeDetail(it)) }, onAdd = { push(Route.RecipeEditor()) }, onOpenAi = { tab = 3 })
                        2 -> ShoppingScreen(repo, ::toast, onOpenAi = { tab = 3 })
                        else -> AiScreen(repo)
                    }
                }
            }
            is Route.Receipt -> ReceiptScreen(repo, route.uri, onDone = { n ->
                pop(); tab = 0; toast("Added $n ${if (n == 1) "item" else "items"} to your pantry")
            }, onBack = ::pop)
            is Route.RecipeDetail -> RecipeDetailScreen(repo, route.id, onBack = ::pop, onEdit = { push(Route.RecipeEditor(editId = it)) }, onToast = ::toast)
            is Route.RecipeEditor -> RecipeEditorScreen(repo, route.editId, route.shared, onBack = ::pop, onSaved = { id ->
                pop(); tab = 1
                if (route.editId == null) push(Route.RecipeDetail(id))
            })
        }
        if (route !is Route.Tabs) SnackbarHost(snackbar, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp))
    }
}

