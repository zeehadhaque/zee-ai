package com.zeeai.companion.ai

import android.content.ContentResolver
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class GroundedCitation(
    val title: String,
    val uri: String
)

data class GroundedStreamResult(
    val text: String,
    val citations: List<GroundedCitation>,
    val searchQueries: List<String>
)

data class DiscoveredModel(
    val id: String,
    val displayName: String,
    val description: String,
    val supportedGenerationMethods: List<String>
)

data class ModelDiscoveryResult(
    val models: List<DiscoveredModel>,
    val bestFlashModel: String,
    val bestProModel: String
)

class GeminiNativeClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeCall: Call? = null

    fun cancelActive() {
        activeCall?.cancel()
        activeCall = null
    }

    suspend fun discoverModels(apiKey: String): Result<ModelDiscoveryResult> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Google AI Studio API key is blank."))
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$cleanKey"
        val request = Request.Builder().url(url).get().build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string()?.take(250) ?: "HTTP ${response.code}"
                    return@withContext Result.failure(
                        IllegalStateException("Invalid API key or network error (${response.code}): $err")
                    )
                }

                val jsonBody = response.body?.string()
                    ?: return@withContext Result.failure(IllegalStateException("Empty response from Google AI Studio."))
                val root = JSONObject(jsonBody)
                val modelsArray = root.optJSONArray("models") ?: JSONArray()

                val discoveredList = mutableListOf<DiscoveredModel>()
                for (i in 0 until modelsArray.length()) {
                    val mObj = modelsArray.optJSONObject(i) ?: continue
                    val rawName = mObj.optString("name", "")
                    val id = if (rawName.startsWith("models/")) rawName.removePrefix("models/") else rawName
                    val displayName = mObj.optString("displayName", id)
                    val description = mObj.optString("description", "")
                    val methodsArray = mObj.optJSONArray("supportedGenerationMethods")
                    val methods = mutableListOf<String>()
                    if (methodsArray != null) {
                        for (j in 0 until methodsArray.length()) {
                            methods.add(methodsArray.optString(j))
                        }
                    }

                    if (methods.contains("generateContent")) {
                        discoveredList.add(DiscoveredModel(id, displayName, description, methods))
                    }
                }

                if (discoveredList.isEmpty()) {
                    return@withContext Result.failure(IllegalStateException("No content generation models found for this API key."))
                }

                // Auto-select best Pro and Flash models
                val proCandidates = discoveredList.filter { it.id.contains("pro", ignoreCase = true) }
                val bestPro = proCandidates.firstOrNull { it.id.contains("2.5", ignoreCase = true) }?.id
                    ?: proCandidates.firstOrNull { it.id.contains("2.0", ignoreCase = true) }?.id
                    ?: proCandidates.firstOrNull()?.id
                    ?: discoveredList.firstOrNull { !it.id.contains("flash", ignoreCase = true) }?.id
                    ?: discoveredList.first().id

                val flashCandidates = discoveredList.filter { it.id.contains("flash", ignoreCase = true) }
                val bestFlash = flashCandidates.firstOrNull { it.id.contains("2.5", ignoreCase = true) }?.id
                    ?: flashCandidates.firstOrNull { it.id.contains("2.0", ignoreCase = true) }?.id
                    ?: flashCandidates.firstOrNull()?.id
                    ?: discoveredList.first().id

                Result.success(ModelDiscoveryResult(discoveredList, bestFlash, bestPro))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun streamChat(
        apiKey: String,
        model: String = "gemini-2.5-flash",
        systemInstruction: String,
        history: List<Pair<String, String>>, // Pair(role = "user"|"model", text)
        userPrompt: String,
        enableGoogleSearch: Boolean = false,
        onChunk: (accumulatedText: String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Gemini API key not set."))
        }

        val contents = JSONArray()
        for ((role, text) in history.takeLast(12)) {
            contents.put(
                JSONObject().apply {
                    put("role", role)
                    put("parts", JSONArray().put(JSONObject().put("text", text)))
                }
            )
        }
        contents.put(
            JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
            }
        )

        val bodyJson = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
            )
            put("contents", contents)
            put("generationConfig", JSONObject().put("temperature", 0.85))
            if (enableGoogleSearch) {
                put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            }
        }

        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse&key=${apiKey.trim()}"

        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        activeCall = call

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string()?.take(250) ?: "HTTP ${response.code}"
                    return@withContext Result.failure(IllegalStateException("Gemini error (${response.code}): $err"))
                }

                val stream = response.body?.byteStream()
                    ?: return@withContext Result.failure(IllegalStateException("Empty Gemini stream."))

                val accumulated = StringBuilder()
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null && isActive) {
                        val trimmed = line?.trim() ?: continue
                        if (!trimmed.startsWith("data:")) continue
                        val jsonStr = trimmed.removePrefix("data:").trim()
                        if (jsonStr.isEmpty() || jsonStr == "[DONE]") continue

                        runCatching {
                            val obj = JSONObject(jsonStr)
                            val parts = obj.optJSONArray("candidates")
                                ?.optJSONObject(0)
                                ?.optJSONObject("content")
                                ?.optJSONArray("parts")
                            if (parts != null) {
                                for (i in 0 until parts.length()) {
                                    val piece = parts.optJSONObject(i)?.optString("text", "") ?: ""
                                    if (piece.isNotEmpty()) {
                                        accumulated.append(piece)
                                        onChunk(accumulated.toString())
                                    }
                                }
                            }
                        }
                    }
                }
                Result.success(accumulated.toString().trim())
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            activeCall = null
        }
    }

    suspend fun streamGrounded(
        apiKey: String,
        model: String = "gemini-2.5-pro",
        systemInstruction: String,
        userPrompt: String,
        onChunk: (accumulatedText: String, citations: List<GroundedCitation>, queries: List<String>) -> Unit
    ): Result<GroundedStreamResult> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Gemini API key not set in Settings."))
        }

        val bodyJson = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                )
            )
            // temperature = 1.0 is recommended by Google for reliable Search Grounding dispatch
            put("generationConfig", JSONObject().put("temperature", 1.0))
            put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        }

        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse&key=${apiKey.trim()}"

        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        activeCall = call

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string()?.take(260) ?: "HTTP ${response.code}"
                    return@withContext Result.failure(
                        IllegalStateException("Gemini Grounding error (${response.code}): $err")
                    )
                }

                val stream = response.body?.byteStream()
                    ?: return@withContext Result.failure(IllegalStateException("Empty Gemini stream."))

                val accumulated = StringBuilder()
                val citationMap = LinkedHashMap<String, GroundedCitation>()
                val querySet = LinkedHashSet<String>()

                BufferedReader(InputStreamReader(stream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null && isActive) {
                        val trimmed = line?.trim() ?: continue
                        if (!trimmed.startsWith("data:")) continue
                        val jsonStr = trimmed.removePrefix("data:").trim()
                        if (jsonStr.isEmpty() || jsonStr == "[DONE]") continue

                        runCatching {
                            val obj = JSONObject(jsonStr)
                            val candidate = obj.optJSONArray("candidates")?.optJSONObject(0) ?: return@runCatching

                            val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
                            if (parts != null) {
                                for (i in 0 until parts.length()) {
                                    val piece = parts.optJSONObject(i)?.optString("text", "") ?: ""
                                    if (piece.isNotEmpty()) accumulated.append(piece)
                                }
                            }

                            // Parse Google Search Grounding metadata (webSearchQueries + groundingChunks)
                            val meta = candidate.optJSONObject("groundingMetadata")
                            if (meta != null) {
                                val queries = meta.optJSONArray("webSearchQueries")
                                if (queries != null) {
                                    for (i in 0 until queries.length()) {
                                        val q = queries.optString(i).trim()
                                        if (q.isNotEmpty()) querySet.add(q)
                                    }
                                }

                                val chunks = meta.optJSONArray("groundingChunks")
                                if (chunks != null) {
                                    for (i in 0 until chunks.length()) {
                                        val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                                        val uri = web.optString("uri").trim()
                                        if (uri.isNotEmpty() && !citationMap.containsKey(uri)) {
                                            val title = web.optString("title").trim().ifEmpty { uri }
                                            citationMap[uri] = GroundedCitation(title = title, uri = uri)
                                        }
                                    }
                                }
                            }

                            onChunk(accumulated.toString(), citationMap.values.toList(), querySet.toList())
                        }
                    }
                }

                Result.success(
                    GroundedStreamResult(
                        text = accumulated.toString().trim(),
                        citations = citationMap.values.toList(),
                        searchQueries = querySet.toList()
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            activeCall = null
        }
    }

    /**
     * Concurrency-safe single-shot request for AI Council parallel model execution.
     */
    suspend fun generateOnce(
        apiKey: String,
        model: String,
        systemInstruction: String,
        userPrompt: String,
        temperature: Double = 0.7
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Gemini API key not set."))
        }

        val bodyJson = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                )
            )
            put("generationConfig", JSONObject().put("temperature", temperature))
        }

        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${apiKey.trim()}"

        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)

        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            if (cont.isActive) {
                                cont.resume(
                                    Result.failure(
                                        IllegalStateException("HTTP ${resp.code}: ${raw.take(200)}")
                                    )
                                )
                            }
                            return
                        }
                        val text = runCatching {
                            val parts = JSONObject(raw)
                                .optJSONArray("candidates")
                                ?.optJSONObject(0)
                                ?.optJSONObject("content")
                                ?.optJSONArray("parts")
                            buildString {
                                if (parts != null) {
                                    for (i in 0 until parts.length()) {
                                        append(parts.optJSONObject(i)?.optString("text", "") ?: "")
                                    }
                                }
                            }.trim()
                        }.getOrDefault("")

                        if (cont.isActive) {
                            if (text.isNotEmpty()) cont.resume(Result.success(text))
                            else cont.resume(Result.failure(IllegalStateException("Empty model response.")))
                        }
                    }
                }
            })
        }
    }

    suspend fun streamMultimodal(
        apiKey: String,
        contentResolver: ContentResolver,
        fileUri: Uri?,
        mimeType: String?,
        fileSizeBytes: Long,
        model: String = "gemini-2.5-flash",
        systemInstruction: String,
        prompt: String,
        onStatus: (String) -> Unit = {},
        onChunk: (accumulatedText: String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Gemini API key not set in Settings."))
        }

        val parts = JSONArray().put(JSONObject().put("text", prompt))

        if (fileUri != null && !mimeType.isNullOrBlank()) {
            val maxInlineBytes = 8L * 1024L * 1024L
            if (fileSizeBytes in 1..maxInlineBytes) {
                onStatus("Reading file for Gemini multimodal analysis...")
                val rawBytes = contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
                    ?: return@withContext Result.failure(IllegalStateException("Cannot open selected file."))
                val b64 = Base64.encodeToString(rawBytes, Base64.NO_WRAP)
                parts.put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject()
                            .put("mimeType", mimeType)
                            .put("data", b64)
                    )
                )
            } else {
                onStatus("Uploading large media to Gemini Files API...")
                val uploaded = uploadUriToGeminiFilesApi(
                    apiKey = apiKey,
                    contentResolver = contentResolver,
                    uri = fileUri,
                    mimeType = mimeType,
                    fileSizeBytes = fileSizeBytes,
                    onStatus = onStatus
                ).getOrElse { return@withContext Result.failure(it) }

                parts.put(
                    JSONObject().put(
                        "fileData",
                        JSONObject()
                            .put("mimeType", mimeType)
                            .put("fileUri", uploaded)
                    )
                )
            }
        }

        onStatus("Streaming analysis...")

        val bodyJson = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", parts)
                )
            )
            put("generationConfig", JSONObject().put("temperature", 0.4))
        }

        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse&key=${apiKey.trim()}"

        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        activeCall = call

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string()?.take(260) ?: "HTTP ${response.code}"
                    return@withContext Result.failure(IllegalStateException("Gemini error (${response.code}): $err"))
                }

                val stream = response.body?.byteStream()
                    ?: return@withContext Result.failure(IllegalStateException("Empty Gemini stream."))

                val accumulated = StringBuilder()
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null && isActive) {
                        val trimmed = line?.trim() ?: continue
                        if (!trimmed.startsWith("data:")) continue
                        val jsonStr = trimmed.removePrefix("data:").trim()
                        if (jsonStr.isEmpty() || jsonStr == "[DONE]") continue

                        runCatching {
                            val obj = JSONObject(jsonStr)
                            val respParts = obj.optJSONArray("candidates")
                                ?.optJSONObject(0)
                                ?.optJSONObject("content")
                                ?.optJSONArray("parts")
                            if (respParts != null) {
                                for (i in 0 until respParts.length()) {
                                    val piece = respParts.optJSONObject(i)?.optString("text", "") ?: ""
                                    if (piece.isNotEmpty()) {
                                        accumulated.append(piece)
                                        onChunk(accumulated.toString())
                                    }
                                }
                            }
                        }
                    }
                }
                Result.success(accumulated.toString().trim())
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            activeCall = null
        }
    }

    private suspend fun uploadUriToGeminiFilesApi(
        apiKey: String,
        contentResolver: ContentResolver,
        uri: Uri,
        mimeType: String,
        fileSizeBytes: Long,
        onStatus: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val streamingBody = object : RequestBody() {
            override fun contentType(): MediaType = mimeType.toMediaType()
            override fun contentLength(): Long = if (fileSizeBytes > 0L) fileSizeBytes else -1L
            override fun writeTo(sink: BufferedSink) {
                contentResolver.openInputStream(uri)?.source()?.use { src ->
                    sink.writeAll(src)
                } ?: throw IllegalStateException("Could not open media stream.")
            }
        }

        val uploadUrl =
            "https://generativelanguage.googleapis.com/upload/v1beta/files?uploadType=media&key=${apiKey.trim()}"

        val uploadReq = Request.Builder()
            .url(uploadUrl)
            .post(streamingBody)
            .build()

        val uploadedMeta = client.newCall(uploadReq).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                return@withContext Result.failure(
                    IllegalStateException("Gemini Files upload failed (${resp.code}): ${raw.take(220)}")
                )
            }
            JSONObject(raw).optJSONObject("file")
                ?: return@withContext Result.failure(IllegalStateException("Invalid Gemini Files response."))
        }

        val fileName = uploadedMeta.optString("name")
        val fileUri = uploadedMeta.optString("uri")
        var state = uploadedMeta.optString("state", "ACTIVE")

        // Video & large audio files process asynchronously on Gemini servers before becoming ACTIVE
        var attempts = 0
        while (state == "PROCESSING" && attempts < 30 && isActive) {
            onStatus("Processing media on Gemini servers (${attempts + 1}s)...")
            delay(2000)
            attempts++
            val pollReq = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/$fileName?key=${apiKey.trim()}")
                .get()
                .build()
            client.newCall(pollReq).execute().use { pollResp ->
                if (pollResp.isSuccessful) {
                    state = JSONObject(pollResp.body?.string().orEmpty()).optString("state", "ACTIVE")
                }
            }
        }

        if (state != "ACTIVE") {
            return@withContext Result.failure(IllegalStateException("Media processing ended in state: $state"))
        }
        Result.success(fileUri)
    }
}
