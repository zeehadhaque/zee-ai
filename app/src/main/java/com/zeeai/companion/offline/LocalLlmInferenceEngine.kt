package com.zeeai.companion.offline

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors

class LocalLlmInferenceEngine(
    private val context: Context,
    private val modelStore: OfflineModelStoreManager? = null
) {

    companion object {
        private const val TAG = "ZeeOfflineLLM"
        private const val PREFS_NAME = "zee_llm_crash_guard_v2"
        private const val KEY_CRASH_SENTINEL = "loading_sentinel_model_path"
        private const val KEY_FORCE_CPU_PREFIX = "force_cpu_"
        private const val MIN_VALID_MODEL_BYTES = 50L * 1024L * 1024L // অন্তত 50 MB
        private const val GENERATION_TIMEOUT_MS = 120_000L
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ডেডিকেটেড সিঙ্গেল ব্যাকগ্রাউন্ড থ্রেড — Main/UI থ্রেড কখনো ব্লক বা ANR হবে না
    private val llmDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Zee-Offline-LLM-Worker").apply {
            priority = Thread.NORM_PRIORITY
            isDaemon = true
        }
    }.asCoroutineDispatcher()

    private val engineMutex = Mutex()
    private var llmInference: LlmInference? = null
    private var loadedModelPath: String? = null
    private var activeBackend: LlmInference.Backend = LlmInference.Backend.CPU

    fun findFirstInstalledMediaPipeModelId(): String? {
        val store = modelStore ?: return null
        return store.modelsState.value
            .firstOrNull { state ->
                state.installed &&
                    state.spec.type == OfflineModelType.LLM &&
                    (state.spec.fileName.lowercase().endsWith(".task") || state.spec.fileName.lowercase().endsWith(".bin"))
            }
            ?.spec
            ?.id
    }

    fun findFirstInstalledModelFile(): File? {
        val modelId = findFirstInstalledMediaPipeModelId() ?: return null
        return modelStore?.getLocalModelFile(modelId)
    }

    /**
     * মডেল ফাইল আসলেই ভ্যালিড বাইনারি (.task / .bin) কি না যাচাই করে।
     * Corrupted বা HTML error পেজ লোড করলে MediaPipe C++ লেভেলে SIGABRT ক্র্যাশ করে।
     */
    fun validateModelFile(file: File): Result<Unit> {
        if (!file.exists() || !file.isFile) {
            return Result.failure(IllegalStateException("অফলাইন মডেল ফাইলটি পাওয়া যায়নি। Models ট্যাব থেকে ডাউনলোড করো।"))
        }
        val sizeBytes = file.length()
        if (sizeBytes < MIN_VALID_MODEL_BYTES) {
            val mb = sizeBytes / (1024 * 1024)
            return Result.failure(
                IllegalStateException("মডেল ফাইলটি অসম্পূর্ণ ($mb MB)। দয়া করে Delete করে আবার ডাউনলোড করো।")
            )
        }
        val lowerName = file.name.lowercase()
        if (lowerName.endsWith(".gguf")) {
            return Result.failure(
                IllegalStateException("এই ফাইলটি .gguf ফরম্যাটের, কিন্তু অফলাইন ইঞ্জিন MediaPipe (.task / .bin) সাপোর্ট করে। Gemma 3 1B বা Gemma 3n (.task) ব্যবহার করো।")
            )
        }

        return runCatching {
            val header = ByteArray(128)
            RandomAccessFile(file, "r").use { raf -> raf.read(header) }
            val text = String(header, Charsets.UTF_8).trimStart().lowercase()
            if (text.startsWith("<!doctype") || text.startsWith("<html") || text.startsWith("{") || text.startsWith("error")) {
                error("ডাউনলোড হওয়া ফাইলটি আসল মডেল নয় (সার্ভারের HTML/JSON এরর সেভ হয়েছে)। Delete করে আবার নামাও।")
            }
        }
    }

    /**
     * ফোনের বর্তমান ফ্রি RAM চেক করে বড় মডেল লোড করার আগে সতর্ক করে
     */
    private fun getAvailableRamMb(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024L * 1024L)
    }

    suspend fun ensureLoaded(modelPath: String): Result<String> = ensureModelLoaded(modelPath)

    /**
     * Background Pre-Warm: App open বা Offline toggle হওয়ার সাথে সাথে ব্যাকগ্রাউন্ডে মডেল লোড করে রাখা
     */
    fun prewarmModel(modelPath: String?) {
        if (modelPath.isNullOrBlank()) return
        CoroutineScope(llmDispatcher).launch {
            runCatching { ensureModelLoaded(modelPath) }
        }
    }

    /**
     * Native Crash Guard সহ নিরাপদ মডেল লোডার
     */
    suspend fun ensureModelLoaded(modelPath: String): Result<String> = withContext(llmDispatcher) {
        engineMutex.withLock {
            if (llmInference != null && loadedModelPath == modelPath) {
                return@withLock Result.success("Ready (${activeBackend.name})")
            }

            val file = File(modelPath)
            val valid = validateModelFile(file)
            if (valid.isFailure) {
                return@withLock Result.failure(valid.exceptionOrNull()!!)
            }

            val modelSizeMb = file.length() / (1024L * 1024L)
            val availRamMb = getAvailableRamMb()
            Log.i(TAG, "Loading model=${file.name} (${modelSizeMb}MB), availRam=${availRamMb}MB")

            // আগের বার এই মডেল লোড করতে গিয়ে যদি native crash (SIGSEGV/OOM) হয়ে থাকে
            val previousCrashPath = prefs.getString(KEY_CRASH_SENTINEL, null)
            if (previousCrashPath == modelPath) {
                Log.w(TAG, "Native crash sentinel triggered for ${file.name}. Locking safe CPU mode.")
                prefs.edit()
                    .putBoolean(KEY_FORCE_CPU_PREFIX + file.name, true)
                    .remove(KEY_CRASH_SENTINEL)
                    .commit()
            }

            closeInternal()

            // ১. ফোর্স CPU লক করা থাকলে (আগেরবার ক্র্যাশ সেনটিনেল ট্রিগার হলে) CPU-তে লোড করো
            // অন্যথায় ১২জিবি ফোনে সম্পূর্ণ GPU প্রাধান্য দেওয়া হবে (Xiaomi misreported RAM অগ্রাহ্য করে)
            val forceCpu = prefs.getBoolean(KEY_FORCE_CPU_PREFIX + file.name, false)

            val backendsToTry = if (forceCpu) {
                listOf(LlmInference.Backend.CPU)
            } else {
                listOf(LlmInference.Backend.GPU, LlmInference.Backend.CPU)
            }

            // বড় মডেলের জন্য কনটেক্সট টোকেন সেফলি কমানো যাতে Xiaomi OOM কিল না করে
            val safeMaxTokens = when {
                modelSizeMb >= 2400L -> 768
                modelSizeMb >= 1400L -> 1024
                else -> 1280
            }

            var lastError: Throwable? = null
            for (backend in backendsToTry) {
                try {
                    // ডিস্কে সিঙ্ক্রোনাস ক্র্যাশ সেনটিনেল লিখে রাখো
                    prefs.edit().putString(KEY_CRASH_SENTINEL, modelPath).commit()

                    val options = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelPath)
                        .setMaxTokens(safeMaxTokens)
                        .setMaxTopK(48)
                        .setPreferredBackend(backend)
                        .build()

                    val created = LlmInference.createFromOptions(context, options)

                    // ক্র্যাশ ছাড়াই সফল — সেনটিনেল মুছে ফেলো
                    prefs.edit().remove(KEY_CRASH_SENTINEL).commit()

                    llmInference = created
                    loadedModelPath = modelPath
                    activeBackend = backend
                    return@withLock Result.success("Offline Engine Ready (${backend.name}, ${modelSizeMb}MB)")
                } catch (ce: CancellationException) {
                    prefs.edit().remove(KEY_CRASH_SENTINEL).commit()
                    throw ce
                } catch (t: Throwable) {
                    prefs.edit().remove(KEY_CRASH_SENTINEL).commit()
                    Log.e(TAG, "Model load failed on ${backend.name}: ${t.message}", t)
                    lastError = t
                    if (backend == LlmInference.Backend.GPU) {
                        prefs.edit().putBoolean(KEY_FORCE_CPU_PREFIX + file.name, true).apply()
                    }
                }
            }

            Result.failure(
                IllegalStateException(
                    "মডেল লোড করা যায়নি (${lastError?.message ?: "Runtime mismatch"})। Models ট্যাব থেকে 'Gemma 3 1B Instruct (.task)' ডাউনলোড করলে দ্রুত ও ক্র্যাশ ছাড়াই চলবে।"
                )
            )
        }
    }

    /**
     * স্ট্রিমিং অফলাইন জেনারেশন — কখনো অ্যাপ ক্র্যাশ করবে না
     */
    suspend fun generateStreaming(
        modelPath: String,
        prompt: String,
        temperature: Float = 0.84f,
        onPartialResult: (String) -> Unit
    ): Result<String> = withContext(llmDispatcher) {
        val loaded = ensureModelLoaded(modelPath)
        if (loaded.isFailure) {
            return@withContext Result.failure(loaded.exceptionOrNull()!!)
        }

        engineMutex.withLock {
            val engine = llmInference ?: return@withContext Result.failure(
                IllegalStateException("অফলাইন ইঞ্জিন রেডি নয়।")
            )

            return@withLock try {
                val output = StringBuilder()
                val doneSignal = CompletableDeferred<Unit>()

                val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTemperature(temperature)
                    .setTopK(40)
                    .setTopP(0.92f)
                    .build()

                try {
                    withTimeout(GENERATION_TIMEOUT_MS) {
                        LlmInferenceSession.createFromOptions(engine, sessionOptions).use { session ->
                            session.addQueryChunk(prompt)
                            var lastEmitTime = 0L
                            session.generateResponseAsync { partial, done ->
                                if (!partial.isNullOrEmpty()) {
                                    output.append(partial)
                                    val now = System.currentTimeMillis()
                                    if (now - lastEmitTime >= 45L || done) {
                                        lastEmitTime = now
                                        val snapshot = output.toString()
                                        CoroutineScope(Dispatchers.Main).launch {
                                            onPartialResult(snapshot)
                                        }
                                    }
                                }
                                if (done && !doneSignal.isCompleted) {
                                    val finalSnapshot = output.toString()
                                    CoroutineScope(Dispatchers.Main).launch {
                                        onPartialResult(finalSnapshot)
                                    }
                                    doneSignal.complete(Unit)
                                }
                            }
                            doneSignal.await()
                        }
                    }
                } catch (sessionErr: Throwable) {
                    if (output.isEmpty()) {
                        // Direct generation fallback
                        val resp = engine.generateResponse(prompt)
                        output.append(resp)
                        onPartialResult(resp)
                    } else {
                        throw sessionErr
                    }
                }

                Result.success(output.toString().trim())
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "Offline generation error: ${t.message}", t)
                Result.failure(t)
            }
        }
    }

    fun streamResponse(
        modelId: String,
        systemInstruction: String,
        userPrompt: String,
        onToken: (token: String, done: Boolean) -> Unit,
        onError: (error: String) -> Unit
    ) {
        val file = modelStore?.getLocalModelFile(modelId)
        if (file == null || !file.exists()) {
            onError("Model file not found on disk: $modelId")
            return
        }

        val fullPrompt = buildString {
            if (systemInstruction.isNotBlank()) {
                append("<start_of_turn>system\n").append(systemInstruction.trim()).append("<end_of_turn>\n")
            }
            append("<start_of_turn>user\n").append(userPrompt.trim()).append("<end_of_turn>\n<start_of_turn>model\n")
        }

        val scope = CoroutineScope(llmDispatcher)
        scope.launch {
            var previousLen = 0
            val result = generateStreaming(
                modelPath = file.absolutePath,
                prompt = fullPrompt,
                temperature = 0.8f,
                onPartialResult = { accumulated ->
                    if (accumulated.length > previousLen) {
                        val newChunk = accumulated.substring(previousLen)
                        previousLen = accumulated.length
                        onToken(newChunk, false)
                    }
                }
            )
            result.fold(
                onSuccess = {
                    onToken("", true)
                },
                onFailure = { err ->
                    onError(err.message ?: "Unknown offline LLM error")
                }
            )
        }
    }

    fun resetCrashLock(modelFileName: String) {
        prefs.edit()
            .remove(KEY_FORCE_CPU_PREFIX + modelFileName)
            .remove(KEY_CRASH_SENTINEL)
            .apply()
    }

    fun cancelActiveGeneration() {
        // Safe cancellation hook
    }

    fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        runCatching { llmInference?.close() }
        llmInference = null
        loadedModelPath = null
    }
}
