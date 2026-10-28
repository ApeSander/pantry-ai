package com.pantryai.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.pantryai.core.LlmBackend
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Small language model running fully on the phone through MediaPipe LLM Inference.
 * No internet, no account: the model file lives in the app's storage.
 */
class LocalLlm(private val context: Context) : LlmBackend {
    private val lock = ReentrantLock()
    private var engine: LlmInference? = null
    private var loadedPath: String? = null
    var enabled: Boolean = true
    var lastError: String? = null
        private set
    var lastLatencyMs: Long = 0
        private set

    @Volatile var modelPath: String? = null
    @Volatile var maxTokens: Int = 1280
    /** Some .task bundles already apply their chat template; this can be switched off in AI settings. */
    @Volatile var applyTemplate: Boolean = true

    override val ready: Boolean
        get() = enabled && modelPath?.let { File(it).exists() } == true

    val loaded: Boolean get() = engine != null

    private enum class Family { QWEN, GEMMA, LLAMA, PHI, OTHER }

    private fun family(path: String): Family {
        val n = File(path).name.lowercase()
        return when {
            "qwen" in n -> Family.QWEN
            "gemma" in n -> Family.GEMMA
            "llama" in n -> Family.LLAMA
            "phi" in n -> Family.PHI
            else -> Family.OTHER
        }
    }

    private fun template(system: String, user: String, f: Family): String = when (f) {
        Family.GEMMA -> "<start_of_turn>user\n$system\n\n$user<end_of_turn>\n<start_of_turn>model\n"
        Family.LLAMA -> "<|start_header_id|>system<|end_header_id|>\n\n$system<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n$user<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
        Family.PHI -> "<|system|>\n$system<|end|>\n<|user|>\n$user<|end|>\n<|assistant|>\n"
        else -> "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
    }

    /** Load (or reload) the model. Takes a few seconds the first time. */
    fun ensureLoaded(): Boolean = lock.withLock {
        val path = modelPath ?: return false
        if (engine != null && loadedPath == path) return true
        engine?.close(); engine = null
        return try {
            val opts = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(maxTokens)
                .build()
            engine = LlmInference.createFromOptions(context, opts)
            loadedPath = path
            lastError = null
            true
        } catch (t: Throwable) {
            Log.e("LocalLlm", "load failed", t)
            lastError = t.message ?: t.javaClass.simpleName
            false
        }
    }

    fun unload() = lock.withLock { engine?.close(); engine = null; loadedPath = null }

    override fun complete(system: String, user: String): String? {
        if (!ready) return null
        return lock.withLock {
            if (!ensureLoaded()) return@withLock null
            val llm = engine ?: return@withLock null
            val path = loadedPath ?: return@withLock null
            val prompt = if (applyTemplate) template(system, user, family(path)) else "$system\n\n$user\n"
            val t0 = System.currentTimeMillis()
            var session: LlmInferenceSession? = null
            try {
                val sOpts = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(1)
                    .setTemperature(0.0f)
                    .setRandomSeed(7)
                    .build()
                session = LlmInferenceSession.createFromOptions(llm, sOpts)
                session.addQueryChunk(prompt)
                val out = session.generateResponse()
                lastLatencyMs = System.currentTimeMillis() - t0
                cleanup(out)
            } catch (t: Throwable) {
                Log.e("LocalLlm", "generate failed", t)
                lastError = t.message ?: t.javaClass.simpleName
                // A broken session can poison the engine; reload next time.
                runCatching { engine?.close() }; engine = null; loadedPath = null
                null
            } finally {
                runCatching { session?.close() }
            }
        }
    }

    private fun cleanup(s: String): String = s
        .replace("<|im_end|>", "").replace("<end_of_turn>", "").replace("<|eot_id|>", "").replace("<|end|>", "")
        .replace("<|endoftext|>", "").trim()
}
