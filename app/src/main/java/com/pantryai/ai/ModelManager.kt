package com.pantryai.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ModelOption(val title: String, val description: String, val url: String, val fileName: String, val sizeMb: Int)

sealed class ModelState {
    object None : ModelState()
    data class Downloading(val progress: Float, val doneMb: Int, val totalMb: Int) : ModelState()
    data class Copying(val name: String) : ModelState()
    data class Installed(val path: String, val sizeMb: Int) : ModelState()
    data class Failed(val reason: String) : ModelState()
}

/** Downloads/imports the on-device model and keeps track of which one is active. */
class ModelManager(private val context: Context, private val llm: LocalLlm, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<ModelState>(ModelState.None)
    val state: StateFlow<ModelState> = _state
    private var poll: Job? = null

    companion object {
        val options = listOf(
            ModelOption(
                "Qwen2.5 0.5B Instruct (recommended)",
                "Small and fast (~1–3 s per question on a recent phone). Apache-2.0, works offline after download.",
                "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
                "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task", 547,
            ),
        )
    }

    val modelDir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").also { it.mkdirs() }
    val downloadedFile: (String) -> File = { name -> File(modelDir, name) }

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) { prefs.edit { putBoolean("enabled", v) }; llm.enabled = v }

    var applyTemplate: Boolean
        get() = prefs.getBoolean("template", true)
        set(v) { prefs.edit { putBoolean("template", v) }; llm.applyTemplate = v }

    var maxTokens: Int
        get() = prefs.getInt("maxTokens", 1280)
        set(v) { prefs.edit { putInt("maxTokens", v) }; llm.maxTokens = v; llm.unload() }

    fun init() {
        llm.enabled = enabled
        llm.applyTemplate = applyTemplate
        llm.maxTokens = maxTokens
        val path = prefs.getString("path", null)
        if (path != null && File(path).exists()) {
            llm.modelPath = path
            _state.value = ModelState.Installed(path, (File(path).length() / 1_000_000).toInt())
        } else {
            // Was a download still running when the app closed?
            val id = prefs.getLong("downloadId", -1)
            if (id >= 0) watch(id, prefs.getString("downloadFile", null) ?: return)
        }
    }

    fun download(url: String, fileName: String) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val target = File(modelDir, fileName)
        if (target.exists()) target.delete()
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("Pantry AI model")
            .setDescription(fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, "models/$fileName")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val id = dm.enqueue(req)
        prefs.edit { putLong("downloadId", id); putString("downloadFile", target.absolutePath) }
        watch(id, target.absolutePath)
    }

    private fun watch(id: Long, path: String) {
        poll?.cancel()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        _state.value = ModelState.Downloading(0f, 0, 0)
        poll = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val c = dm.query(DownloadManager.Query().setFilterById(id))
                if (c == null || !c.moveToFirst()) { c?.close(); _state.value = ModelState.Failed("Download was cancelled"); clearDownload(); return@launch }
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                c.close()
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> { clearDownload(); activate(path); return@launch }
                    DownloadManager.STATUS_FAILED -> { clearDownload(); _state.value = ModelState.Failed("Download failed (code $reason)"); return@launch }
                    else -> _state.value = ModelState.Downloading(
                        if (total > 0) done.toFloat() / total else 0f, (done / 1_000_000).toInt(), (total / 1_000_000).toInt(),
                    )
                }
                delay(700)
            }
        }
    }

    fun cancelDownload() {
        val id = prefs.getLong("downloadId", -1)
        if (id >= 0) (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id)
        poll?.cancel(); clearDownload()
        _state.value = ModelState.None
    }

    private fun clearDownload() = prefs.edit { remove("downloadId"); remove("downloadFile") }

    fun activate(path: String) {
        prefs.edit { putString("path", path) }
        llm.unload()
        llm.modelPath = path
        _state.value = ModelState.Installed(path, (File(path).length() / 1_000_000).toInt())
    }

    /** Import a .task / .bin model the user already has on the phone. */
    suspend fun importFrom(uri: Uri) {
        val name = withContext(Dispatchers.IO) {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } ?: "custom-model.task"
        _state.value = ModelState.Copying(name)
        try {
            val target = File(modelDir, name)
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it, 1 shl 20) } }
            }
            activate(target.absolutePath)
        } catch (t: Throwable) {
            _state.value = ModelState.Failed("Couldn't copy the model: ${t.message}")
        }
    }

    fun delete() {
        llm.unload()
        prefs.getString("path", null)?.let { File(it).delete() }
        prefs.edit { remove("path") }
        llm.modelPath = null
        _state.value = ModelState.None
    }
}
