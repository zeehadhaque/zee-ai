package com.zeeai.companion.offline

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

enum class OfflineModelType {
    LLM,
    STT,
    TTS,
    WAKE_WORD
}

data class OfflineModelSpec(
    val id: String,
    val name: String,
    val type: OfflineModelType,
    val language: String,
    val approxSizeMb: Int,
    val minRamGb: Int,
    val fileName: String,
    val downloadUrl: String
)

data class ModelDownloadState(
    val spec: OfflineModelSpec,
    val installed: Boolean,
    val installedBytes: Long,
    val isDownloading: Boolean = false,
    val progressPercent: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val error: String? = null
)

class OfflineModelStoreManager(private val context: Context) {

    private val modelsDir: File = File(context.filesDir, "zee_offline_models").apply {
        if (!exists()) mkdirs()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val activeJobs = mutableMapOf<String, Job>()

    // Verified active, direct Hugging Face & Edge model URLs (Free & Zero-Gate direct downloads)
    val catalog: List<OfflineModelSpec> = listOf(
        // Google Gemma Family
        OfflineModelSpec(
            id = "gemma-2-2b-it-gguf",
            name = "Google Gemma 2 2B Instruct (Flagship Local)",
            type = OfflineModelType.LLM,
            language = "Multilingual (EN / BN / HI / UR)",
            approxSizeMb = 1630,
            minRamGb = 4,
            fileName = "gemma-2-2b-it-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf"
        ),
        OfflineModelSpec(
            id = "gemma-2-2b-fast-iq3",
            name = "Google Gemma 2 2B (Ultra-Fast Mobile IQ3)",
            type = OfflineModelType.LLM,
            language = "Multilingual (Lightweight)",
            approxSizeMb = 1250,
            minRamGb = 3,
            fileName = "gemma-2-2b-it-IQ3_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-IQ3_M.gguf"
        ),
        OfflineModelSpec(
            id = "gemma-2b-int4-task",
            name = "Google Gemma 2B (MediaPipe Hardware INT4)",
            type = OfflineModelType.LLM,
            language = "Native MediaPipe GPU Engine",
            approxSizeMb = 1350,
            minRamGb = 6,
            fileName = "gemma-2b-it-gpu-int4.bin",
            downloadUrl = "https://huggingface.co/google/gemma-2b-it-tflite/resolve/main/gemma-2b-it-gpu-int4.bin"
        ),

        // Multilingual & Bengali Specialists (Qwen 2.5)
        OfflineModelSpec(
            id = "qwen2.5-0.5b-gguf",
            name = "Qwen 2.5 0.5B Instruct (Ultra-Fast Bengali/Eng)",
            type = OfflineModelType.LLM,
            language = "Bangla / English / Hindi / Urdu",
            approxSizeMb = 398,
            minRamGb = 2,
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
        ),
        OfflineModelSpec(
            id = "qwen2.5-1.5b-gguf",
            name = "Qwen 2.5 1.5B Instruct (High-IQ Creative)",
            type = OfflineModelType.LLM,
            language = "Multilingual + Poetry / Shayeri / Jokes",
            approxSizeMb = 980,
            minRamGb = 4,
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
        ),
        OfflineModelSpec(
            id = "qwen2.5-coder-1.5b-gguf",
            name = "Qwen 2.5 Coder 1.5B (Offline Code Specialist)",
            type = OfflineModelType.LLM,
            language = "Kotlin / Python / Web / Android",
            approxSizeMb = 980,
            minRamGb = 4,
            fileName = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"
        ),

        // Compact Mobile & Edge Giants
        OfflineModelSpec(
            id = "llama-3.2-1b-gguf",
            name = "Meta Llama 3.2 1B Instruct (Mobile Optimized)",
            type = OfflineModelType.LLM,
            language = "English & Conversational General",
            approxSizeMb = 800,
            minRamGb = 3,
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"
        ),
        OfflineModelSpec(
            id = "phi-3-mini-gguf",
            name = "Microsoft Phi-3 Mini 4K (Deep Reasoning)",
            type = OfflineModelType.LLM,
            language = "Logic / Math / Reasoning",
            approxSizeMb = 2200,
            minRamGb = 6,
            fileName = "Phi-3-mini-4k-instruct-q4.gguf",
            downloadUrl = "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4.gguf"
        ),
        OfflineModelSpec(
            id = "tinyllama-1.1b-gguf",
            name = "TinyLlama 1.1B Chat (Zero-Lag Ultra-Light)",
            type = OfflineModelType.LLM,
            language = "Lightweight Casual Chat",
            approxSizeMb = 669,
            minRamGb = 2,
            fileName = "tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf"
        ),

        // Offline Neural Audio & Speech
        OfflineModelSpec(
            id = "kokoro-82m-voice",
            name = "Kokoro 82M Neural Voice (Offline TTS Engine)",
            type = OfflineModelType.TTS,
            language = "Hyper-Realistic Voice Synthesis",
            approxSizeMb = 147,
            minRamGb = 2,
            fileName = "model_q4f16.onnx",
            downloadUrl = "https://huggingface.co/onnx-community/Kokoro-82M-ONNX/resolve/main/onnx/model_q4f16.onnx"
        ),
        OfflineModelSpec(
            id = "whisper-tiny-stt",
            name = "Whisper Tiny (Offline Speech-to-Text STT)",
            type = OfflineModelType.STT,
            language = "Multilingual Real-time Speech",
            approxSizeMb = 75,
            minRamGb = 2,
            fileName = "ggml-tiny.bin",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
        )
    )

    private val _modelsState = MutableStateFlow(buildSnapshot())
    val modelsState: StateFlow<List<ModelDownloadState>> = _modelsState.asStateFlow()

    fun getLocalModelFile(modelId: String): File? {
        val spec = catalog.find { it.id == modelId }
        val fileName = spec?.fileName ?: modelId
        val file = File(modelsDir, fileName)
        return if (file.exists() && file.length() > 0L) file else null
    }

    fun getTotalStorageUsedBytes(): Long {
        return modelsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    suspend fun downloadModel(
        modelId: String,
        customUrl: String? = null,
        hfToken: String = ""
    ) = withContext(Dispatchers.IO) {
        val spec = catalog.find { it.id == modelId } ?: return@withContext
        val url = customUrl?.ifBlank { null } ?: spec.downloadUrl
        val targetFile = File(modelsDir, spec.fileName)
        val tempFile = File(modelsDir, "${spec.fileName}.part")

        updateItem(modelId) {
            it.copy(
                isDownloading = true,
                progressPercent = 0,
                downloadedBytes = 0L,
                totalBytes = 0L,
                error = null
            )
        }

        try {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; ZEE-AI)")

            if (hfToken.isNotBlank() && url.contains("huggingface.co")) {
                reqBuilder.header("Authorization", "Bearer ${hfToken.trim()}")
            }

            val request = reqBuilder.build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    val msg = when (code) {
                        401, 403 -> "HTTP $code: Gated model. Enter Hugging Face Access Token in Settings."
                        404 -> "HTTP 404: Model file not found on host."
                        else -> "HTTP $code: Server error (${response.message})"
                    }
                    throw IllegalStateException(msg)
                }

                val body = response.body ?: throw IllegalStateException("Empty response body from server.")
                val totalBytes = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastReportedPct = -1

                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            if (totalBytes > 0L) {
                                val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                if (pct != lastReportedPct) {
                                    lastReportedPct = pct
                                    updateItem(modelId) {
                                        it.copy(
                                            progressPercent = pct,
                                            downloadedBytes = downloaded,
                                            totalBytes = totalBytes
                                        )
                                    }
                                }
                            } else {
                                updateItem(modelId) {
                                    it.copy(
                                        downloadedBytes = downloaded,
                                        totalBytes = totalBytes
                                    )
                                }
                            }
                        }
                        output.flush()
                    }
                }
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                throw IllegalStateException("Failed to finalize downloaded file in app storage.")
            }

            updateItem(modelId) {
                it.copy(
                    installed = true,
                    installedBytes = targetFile.length(),
                    isDownloading = false,
                    progressPercent = 100,
                    downloadedBytes = targetFile.length(),
                    totalBytes = targetFile.length(),
                    error = null
                )
            }
        } catch (ce: CancellationException) {
            tempFile.delete()
            updateItem(modelId) {
                it.copy(isDownloading = false, progressPercent = 0, error = "Download cancelled.")
            }
            throw ce
        } catch (e: Exception) {
            tempFile.delete()
            updateItem(modelId) {
                it.copy(
                    isDownloading = false,
                    progressPercent = 0,
                    error = e.message ?: "Download connection error"
                )
            }
        } finally {
            activeJobs.remove(modelId)
        }
    }

    fun importLocalModelFile(sourceUri: Uri, fileName: String): Boolean {
        return try {
            val targetFile = File(modelsDir, fileName)
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            _modelsState.value = buildSnapshot()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun registerJob(modelId: String, job: Job) {
        activeJobs[modelId]?.cancel()
        activeJobs[modelId] = job
    }

    fun cancelDownload(modelId: String) {
        activeJobs.remove(modelId)?.cancel()
    }

    fun deleteModel(modelId: String): Boolean {
        cancelDownload(modelId)
        val spec = catalog.find { it.id == modelId }
        val target = File(modelsDir, spec?.fileName ?: modelId)
        val deleted = !target.exists() || target.delete()
        _modelsState.value = buildSnapshot()
        return deleted
    }

    private fun buildSnapshot(): List<ModelDownloadState> {
        return catalog.map { spec ->
            val file = File(modelsDir, spec.fileName)
            val exists = file.exists() && file.length() > 0L
            ModelDownloadState(
                spec = spec,
                installed = exists,
                installedBytes = if (exists) file.length() else 0L
            )
        }
    }

    private fun updateItem(
        modelId: String,
        transform: (ModelDownloadState) -> ModelDownloadState
    ) {
        _modelsState.value = _modelsState.value.map { item ->
            if (item.spec.id == modelId) transform(item) else item
        }
    }
}
