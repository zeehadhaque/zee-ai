package com.zeeai.companion.offline

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class LocalLlmConfig(
    val maxTokens: Int = 1024,
    val temperature: Float = 0.8f,
    val topK: Int = 40,
    val topP: Float = 0.95f
)

class LocalLlmInferenceEngine(
    private val context: Context,
    private val modelStore: OfflineModelStoreManager
) {
    private val loadMutex = Mutex()

    @Volatile
    private var llmInference: LlmInference? = null

    @Volatile
    private var loadedModelId: String? = null

    @Volatile
    private var activeBackendName: String = "NONE"

    fun getLoadedModelId(): String? = loadedModelId

    fun getActiveBackendName(): String = activeBackendName

    /**
     * Returns the first installed MediaPipe-compatible (.task or .bin) LLM model ID.
     */
    fun findFirstInstalledMediaPipeModelId(): String? {
        return modelStore.modelsState.value
            .firstOrNull { state ->
                state.installed &&
                    state.spec.type == OfflineModelType.LLM &&
                    isMediaPipeModelFile(state.spec.fileName)
            }
            ?.spec
            ?.id
    }

    private fun isMediaPipeModelFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".task") || lower.endsWith(".bin")
    }

    /**
     * Loads the requested downloaded model from OfflineModelStoreManager.
     */
    suspend fun ensureModelLoaded(
        modelId: String,
        maxTokens: Int = 1024
    ): Result<String> = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            if (llmInference != null && loadedModelId == modelId) {
                return@withLock Result.success(modelId)
            }

            val modelFile: File = modelStore.getLocalModelFile(modelId)
                ?: return@withLock Result.failure(
                    IllegalStateException(
                        "Model '$modelId' is not installed. Download it in Models & Control first."
                    )
                )

            if (!isMediaPipeModelFile(modelFile.name)) {
                return@withLock Result.failure(
                    IllegalArgumentException(
                        "File '${modelFile.name}' is not a MediaPipe .task or .bin bundle."
                    )
                )
            }

            releaseLoadedEngineInternal()

            val instance = runCatching {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(maxTokens)
                    .build()
                LlmInference.createFromOptions(context, options)
            }.getOrElse { err ->
                activeBackendName = "NONE"
                return@withLock Result.failure(
                    IllegalStateException(
                        "Failed to initialize local LLM: ${err.message}",
                        err
                    )
                )
            }

            activeBackendName = "CPU/GPU"
            llmInference = instance
            loadedModelId = modelId
            Result.success(modelId)
        }
    }

    /**
     * Generates or streams tokens from the local MediaPipe LLM.
     */
    suspend fun streamResponse(
        modelId: String,
        systemInstruction: String,
        userPrompt: String,
        config: LocalLlmConfig = LocalLlmConfig(),
        onToken: (token: String, done: Boolean) -> Unit,
        onError: (message: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val loadResult = ensureModelLoaded(modelId, config.maxTokens)
        if (loadResult.isFailure) {
            onError(loadResult.exceptionOrNull()?.message ?: "Failed to load local LLM.")
            return@withContext
        }

        val engine = llmInference ?: run {
            onError("Local LLM engine is unavailable.")
            return@withContext
        }

        try {
            val formattedPrompt = buildString {
                if (systemInstruction.isNotBlank()) {
                    append("<start_of_turn>user\n")
                    append("System Instructions:\n")
                    append(systemInstruction.trim())
                    append("\n\nUser Message:\n")
                    append(userPrompt.trim())
                    append("<end_of_turn>\n<start_of_turn>model\n")
                } else {
                    append("<start_of_turn>user\n")
                    append(userPrompt.trim())
                    append("<end_of_turn>\n<start_of_turn>model\n")
                }
            }

            val response = engine.generateResponse(formattedPrompt)
            onToken(response, true)
        } catch (e: Exception) {
            onError(e.message ?: "Local inference failed.")
        }
    }

    fun cancelActiveGeneration() {
        // Safe cancellation hook
    }

    private fun releaseLoadedEngineInternal() {
        cancelActiveGeneration()
        runCatching { llmInference?.close() }
        llmInference = null
        loadedModelId = null
        activeBackendName = "NONE"
    }

    fun close() {
        releaseLoadedEngineInternal()
    }
}
