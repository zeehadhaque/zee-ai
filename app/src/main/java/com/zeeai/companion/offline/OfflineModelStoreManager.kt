package com.zeeai.companion.offline

import android.content.Context
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
    val error: String? = null
)

class OfflineModelStoreManager(context: Context) {

    private val modelsDir: File = File(context.filesDir, "zee_offline_models").apply {
        if (!exists()) mkdirs()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val activeJobs = mutableMapOf<String, Job>()

    // Replace URLs with your hosted model mirror / Hugging Face direct file URLs
    val catalog: List<OfflineModelSpec> = listOf(
        OfflineModelSpec(
            id = "gemma-2b-int4-task",
            name = "Gemma 2B (INT4 On-Device LLM)",
            type = OfflineModelType.LLM,
            language = "Multilingual (EN / BN / HI / UR)",
            approxSizeMb = 1350,
            minRamGb = 6,
            fileName = "gemma-2b-it-gpu-int4.bin",
            downloadUrl = "https://your-model-host.example/models/gemma-2b-it-gpu-int4.bin"
        ),
        OfflineModelSpec(
            id = "qwen2.5-1.5b-gguf",
            name = "Qwen 2.5 1.5B Instruct (Q4_K_M GGUF)",
            type = OfflineModelType.LLM,
            language = "Multilingual + Poetry/Chat",
            approxSizeMb = 980,
            minRamGb = 4,
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            downloadUrl = "https://your-model-host.example/models/qwen2.5-1.5b-instruct-q4_k_m.gguf"
        ),
        OfflineModelSpec(
            id = "sherpa-whisper-tiny-multi",
            name = "Sherpa-ONNX Whisper Tiny (Offline STT)",
            type = OfflineModelType.STT,
            language = "Bangla / English / Hindi / Urdu",
            approxSizeMb = 115,
            minRamGb = 3,
            fileName = "sherpa-onnx-whisper-tiny.tar.bz2",
            downloadUrl = "https://your-model-host.example/models/sherpa-onnx-whisper-tiny.tar.bz2"
        ),
        OfflineModelSpec(
            id = "piper-zee-zhee-voices",
            name = "Offline Neural Voice Pack (ZEE & ZHEE)",
            type = OfflineModelType.TTS,
            language = "Bangla / English",
            approxSizeMb = 72,
            minRamGb = 2,
            fileName = "zee-zhee-vits-voices.zip",
            downloadUrl = "https://your-model-host.example/models/zee-zhee-vits-voices.zip"
        ),
        OfflineModelSpec(
            id = "wakeword-zee-zhee",
            name = "Wake Word Pack (Hey Zee / ZHEE)",
            type = OfflineModelType.WAKE_WORD,
            language = "Universal",
            approxSizeMb = 14,
            minRamGb = 2,
            fileName = "zee-zhee-kws-onnx.zip",
            downloadUrl = "https://your-model-host.example/models/zee-zhee-kws-onnx.zip"
        )
    )

    private val _modelsState = MutableStateFlow(buildSnapshot())
    val modelsState: StateFlow<List<ModelDownloadState>> = _modelsState.asStateFlow()

    fun getLocalModelFile(modelId: String): File? {
        val spec = catalog.find { it.id == modelId } ?: return null
        val file = File(modelsDir, spec.fileName)
        return if (file.exists() && file.length() > 0L) file else null
    }

    fun getTotalStorageUsedBytes(): Long {
        return modelsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    suspend fun downloadModel(modelId: String) = withContext(Dispatchers.IO) {
        val spec = catalog.find { it.id == modelId } ?: return@withContext
        val targetFile = File(modelsDir, spec.fileName)
        val tempFile = File(modelsDir, "${spec.fileName}.part")

        updateItem(modelId) {
            it.copy(isDownloading = true, progressPercent = 0, error = null)
        }

        try {
            val request = Request.Builder().url(spec.downloadUrl).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}: Check model download URL")
                }

                val body = response.body ?: throw IllegalStateException("Empty response body")
                val totalBytes = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
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
                                    updateItem(modelId) { it.copy(progressPercent = pct) }
                                }
                            }
                        }
                        output.flush()
                    }
                }
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                throw IllegalStateException("Failed to finalize downloaded model file.")
            }

            updateItem(modelId) {
                it.copy(
                    installed = true,
                    installedBytes = targetFile.length(),
                    isDownloading = false,
                    progressPercent = 100,
                    error = null
                )
            }
        } catch (ce: CancellationException) {
            tempFile.delete()
            updateItem(modelId) {
                it.copy(isDownloading = false, progressPercent = 0, error = "Download cancelled")
            }
            throw ce
        } catch (e: Exception) {
            tempFile.delete()
            updateItem(modelId) {
                it.copy(
                    isDownloading = false,
                    progressPercent = 0,
                    error = e.message ?: "Download failed"
                )
            }
        } finally {
            activeJobs.remove(modelId)
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
        val spec = catalog.find { it.id == modelId } ?: return false
        val target = File(modelsDir, spec.fileName)
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
