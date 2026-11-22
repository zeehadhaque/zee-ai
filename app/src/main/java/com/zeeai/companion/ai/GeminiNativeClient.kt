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
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile
    private var activeCall: Call? = null

    companion object {
        val ALL_KNOWN_GEMINI_MODELS = listOf(
            // Frontier Gemini 4 & 3.8 Series
            "gemini-3.8-flash",
            "gemini-3.8-pro",
            "gemini-4-hydrogen",
            "gemini-4-carbon",
            "gemini-4-neon",
            "gemini-4-argon",
            "gemini-3.0-flash",
            "gemini-3.0-pro",
            // Stable 2.0 & 2.5 Series
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
            "gemini-2.0-flash-exp",
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            // Reasoning & Thinking Flagships
            "gemini-2.0-pro-exp-02-05",
            "gemini-2.0-flash-thinking-exp-01-21",
            "gemini-exp-1206",
            // Established 1.5 Workhorses
            "gemini-1.5-flash",
            "gemini-1.5-flash-8b",
            "gemini-1.5-flash-latest",
            "gemini-1.5-pro",
            "gemini-1.5-pro-latest"
        )

        const val DEFAULT_FLASH_MODEL = "gemini-2.0-flash"
        const val DEFAULT_PRO_MODEL = "gemini-1.5-pro"
    }

    // Ordered high-quota Free-Tier Flash models (all official latest Gemini endpoints)
    private val standardFlashPriority = listOf(
        "gemini-3.8-flash",
        "gemini-2.0-flash",
        "gemini-1.5-flash",
        "gemini-4-hydrogen",
        "gemini-4-neon",
        "gemini-2.0-flash-lite",
        "gemini-3.0-flash",
        "gemini-4-carbon",
        "gemini-4-argon",
        "gemini-1.5-flash-8b",
        "gemini-2.0-flash-exp",
        "gemini-2.5-flash",
        "gemini-2.0-pro-exp-02-05",
        "gemini-2.0-flash-thinking-exp-01-21",
        "gemini-3.8-pro",
        "gemini-1.5-pro",
        "gemini-1.5-flash-latest",
        "gemini-1.5-pro-latest",
        "gemini-exp-1206"
    )

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
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", cleanKey)
            .header("Content-Type", "application/json")
            .get()
            .build()

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

                // Prioritize high-quota Free-Tier Flash models FIRST
                val flashCandidates = discoveredList.filter { it.id.contains("flash", ignoreCase = true) }
                val bestFlash = flashCandidates.firstOrNull { it.id == "gemini-2.0-flash" }?.id
                    ?: flashCandidates.firstOrNull { it.id == "gemini-1.5-flash" }?.id
                    ?: flashCandidates.firstOrNull { it.id.contains("2.0-flash", ignoreCase = true) && !it.id.contains("lite") }?.id
                    ?: flashCandidates.firstOrNull { it.id.contains("1.5-flash", ignoreCase = true) && !it.id.contains("8b") }?.id
                    ?: flashCandidates.firstOrNull { it.id.contains("2.0-flash-lite", ignoreCase = true) }?.id
                    ?: flashCandidates.firstOrNull { it.id.contains("flash", ignoreCase = true) }?.id
                    ?: flashCandidates.firstOrNull()?.id
                    ?: DEFAULT_FLASH_MODEL

                val proCandidates = discoveredList.filter { it.id.contains("pro", ignoreCase = true) || it.id.contains("thinking", ignoreCase = true) }
                val bestPro = proCandidates.firstOrNull { it.id.contains("2.0-pro", ignoreCase = true) }?.id
                    ?: proCandidates.firstOrNull { it.id.contains("1.5-pro", ignoreCase = true) }?.id
                    ?: proCandidates.firstOrNull { it.id.contains("thinking", ignoreCase = true) }?.id
                    ?: proCandidates.firstOrNull()?.id
                    ?: DEFAULT_PRO_MODEL

                Result.success(ModelDiscoveryResult(discoveredList, bestFlash, bestPro))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun testAiConnection(
        geminiKey: String,
        groqKey: String = "",
        openRouterKey: String = ""
    ): String = withContext(Dispatchers.IO) {
        val cleanKey = geminiKey.trim()
        if (cleanKey.isNotBlank()) {
            val candidateTestModels = listOf(
                "gemini-3.8-flash",
                "gemini-2.0-flash",
                "gemini-1.5-flash",
                "gemini-4-hydrogen",
                "gemini-4-neon",
                "gemini-2.0-flash-lite",
                "gemini-1.5-pro",
                "gemini-1.5-flash-8b",
                "gemini-2.5-flash"
            )
            var lastErr = ""
            for (testModel in candidateTestModels) {
                val testUrl = "https://generativelanguage.googleapis.com/v1beta/models/$testModel:generateContent?key=$cleanKey"
                val testBody = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "hi")))))
                    put("generationConfig", JSONObject().put("maxOutputTokens", 5))
                }
                try {
                    val req = Request.Builder()
                        .url(testUrl)
                        .header("x-goog-api-key", cleanKey)
                        .header("Content-Type", "application/json")
                        .post(testBody.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            return@withContext "✅ Gemini Connected: $testModel (Online & Ready)"
                        } else if (resp.code == 429) {
                            lastErr = "⚠️ Gemini Quota Exhausted (429) on $testModel"
                        } else {
                            val body = resp.body?.string().orEmpty().take(120)
                            lastErr = "HTTP ${resp.code} on $testModel: $body"
                        }
                    }
                } catch (e: Exception) {
                    lastErr = e.message?.take(80) ?: "Network error"
                }
            }

            // Test backup providers
            if (groqKey.isNotBlank()) {
                val groqOk = testGroq(groqKey)
                if (groqOk) return@withContext "⚠️ Gemini ($lastErr) • Groq Backup Connected (llama-3.3-70b)"
            }
            if (openRouterKey.isNotBlank()) {
                val orOk = testOpenRouter(openRouterKey)
                if (orOk) return@withContext "⚠️ Gemini ($lastErr) • OpenRouter Backup Connected"
            }
            return@withContext "❌ $lastErr"
        } else if (groqKey.isNotBlank()) {
            if (testGroq(groqKey)) return@withContext "✅ Groq Backup Connected (llama-3.3-70b-versatile)"
            return@withContext "❌ Groq key test failed."
        } else if (openRouterKey.isNotBlank()) {
            if (testOpenRouter(openRouterKey)) return@withContext "✅ OpenRouter Backup Connected"
            return@withContext "❌ OpenRouter key test failed."
        }
        "No API Key Configured"
    }

    private fun testGroq(key: String): Boolean {
        return try {
            val req = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .header("Authorization", "Bearer ${key.trim()}")
                .post(
                    JSONObject().apply {
                        put("model", "llama-3.3-70b-versatile")
                        put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
                        put("max_tokens", 5)
                    }.toString().toRequestBody("application/json".toMediaType())
                ).build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    private fun testOpenRouter(key: String): Boolean {
        return try {
            val req = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .header("Authorization", "Bearer ${key.trim()}")
                .header("HTTP-Referer", "https://github.com/zeehadhaque/zee-ai")
                .header("X-Title", "ZEE AI")
                .post(
                    JSONObject().apply {
                        put("model", "meta-llama/llama-3.3-70b-instruct:free")
                        put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
                        put("max_tokens", 5)
                    }.toString().toRequestBody("application/json".toMediaType())
                ).build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    /**
     * Executes OpenAI-compatible chat completion for Groq or OpenRouter
     */
    suspend fun executeOpenAiCompatibleChat(
        endpointUrl: String,
        apiKey: String,
        model: String,
        systemInstruction: String,
        history: List<Pair<String, String>>,
        userPrompt: String,
        onChunk: (accumulatedText: String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val messages = JSONArray()
        if (systemInstruction.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemInstruction))
        }
        for ((role, text) in history.takeLast(10)) {
            val mappedRole = if (role == "model" || role == "assistant") "assistant" else "user"
            messages.put(JSONObject().put("role", mappedRole).put("content", text))
        }
        messages.put(JSONObject().put("role", "user").put("content", userPrompt))

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.7)
            put("stream", false)
        }

        val reqBuilder = Request.Builder()
            .url(endpointUrl)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Content-Type", "application/json")

        if (endpointUrl.contains("openrouter")) {
            reqBuilder.header("HTTP-Referer", "https://github.com/zeehadhaque/zee-ai")
            reqBuilder.header("X-Title", "ZEE AI Companion")
        }

        val request = reqBuilder.post(body.toString().toRequestBody("application/json".toMediaType())).build()

        try {
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("Backup provider error (${resp.code}): ${raw.take(160)}"))
                }
                val json = JSONObject(raw)
                val reply = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    .orEmpty()
                    .trim()
                onChunk(reply)
                Result.success(reply)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun streamChat(
        apiKey: String,
        model: String = DEFAULT_FLASH_MODEL,
        systemInstruction: String,
        history: List<Pair<String, String>>,
        userPrompt: String,
        enableGoogleSearch: Boolean = false,
        backupGroqKey: String = "",
        backupOpenRouterKey: String = "",
        onChunk: (accumulatedText: String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() && backupGroqKey.isBlank() && backupOpenRouterKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("No AI API key configured in Settings."))
        }

        // Build fallback list starting with requested model then standard Flash cascade
        val modelsToTry = mutableListOf<String>()
        modelsToTry.add(model)
        for (m in standardFlashPriority) {
            if (!modelsToTry.contains(m)) modelsToTry.add(m)
        }

        var lastError: Throwable? = null

        if (apiKey.isNotBlank()) {
            for (candidateModel in modelsToTry) {
                val res = streamChatSingleAttempt(
                    apiKey = apiKey,
                    model = candidateModel,
                    systemInstruction = systemInstruction,
                    history = history,
                    userPrompt = userPrompt,
                    enableGoogleSearch = enableGoogleSearch,
                    onChunk = onChunk
                )
                if (res.isSuccess) {
                    return@withContext res
                }
                val err = res.exceptionOrNull()
                lastError = err
                val msg = err?.message.orEmpty()
                // Fallback on quota, model not found, server error or bad argument
                val isRetryable = msg.contains("429") || msg.contains("404") || msg.contains("503") ||
                        msg.contains("RESOURCE_EXHAUSTED") || msg.contains("400") ||
                        msg.contains("not found") || msg.contains("not supported")
                if (!isRetryable) {
                    break
                }
            }
        }

        // Seamless Failover: Groq Backup
        if (backupGroqKey.isNotBlank()) {
            val groqRes = executeOpenAiCompatibleChat(
                endpointUrl = "https://api.groq.com/openai/v1/chat/completions",
                apiKey = backupGroqKey,
                model = "llama-3.3-70b-versatile",
                systemInstruction = systemInstruction,
                history = history,
                userPrompt = userPrompt,
                onChunk = onChunk
            )
            if (groqRes.isSuccess) return@withContext groqRes
        }

        // Seamless Failover: OpenRouter Backup
        if (backupOpenRouterKey.isNotBlank()) {
            val openRouterRes = executeOpenAiCompatibleChat(
                endpointUrl = "https://openrouter.ai/api/v1/chat/completions",
                apiKey = backupOpenRouterKey,
                model = "meta-llama/llama-3.3-70b-instruct:free",
                systemInstruction = systemInstruction,
                history = history,
                userPrompt = userPrompt,
                onChunk = onChunk
            )
            if (openRouterRes.isSuccess) return@withContext openRouterRes
        }

        Result.failure(lastError ?: IllegalStateException("Chat request failed."))
    }

    private suspend fun streamChatSingleAttempt(
        apiKey: String,
        model: String,
        systemInstruction: String,
        history: List<Pair<String, String>>,
        userPrompt: String,
        enableGoogleSearch: Boolean,
        onChunk: (accumulatedText: String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
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
            .header("x-goog-api-key", apiKey.trim())
            .header("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        activeCall = call

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string()?.take(250) ?: "HTTP ${response.code}"
                    return@withContext Result.failure(IllegalStateException("HTTP ${response.code}: $err"))
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
        model: String = DEFAULT_FLASH_MODEL,
        systemInstruction: String,
        userPrompt: String,
        backupGroqKey: String = "",
        onChunk: (accumulatedText: String, citations: List<GroundedCitation>, queries: List<String>) -> Unit
    ): Result<GroundedStreamResult> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            // If Gemini is blank but Groq backup is provided
            if (backupGroqKey.isNotBlank()) {
                var outputText = ""
                val groqRes = executeOpenAiCompatibleChat(
                    endpointUrl = "https://api.groq.com/openai/v1/chat/completions",
                    apiKey = backupGroqKey,
                    model = "llama-3.3-70b-versatile",
                    systemInstruction = systemInstruction,
                    history = emptyList(),
                    userPrompt = userPrompt,
                    onChunk = {
                        outputText = it
                        onChunk(it, emptyList(), emptyList())
                    }
                )
                return@withContext groqRes.map {
                    GroundedStreamResult(
                        text = "$it\n\n> ℹ️ *Answered via Groq Llama-3.3-70B backup (Google Search Grounding requires Gemini)*",
                        citations = emptyList(),
                        searchQueries = emptyList()
                    )
                }
            }
            return@withContext Result.failure(IllegalStateException("Gemini API key not set in Settings."))
        }

        val modelsToTry = mutableListOf<String>()
        modelsToTry.add(model)
        for (m in standardFlashPriority) {
            if (!modelsToTry.contains(m)) modelsToTry.add(m)
        }

        for (candidateModel in modelsToTry) {
            val groundedRes = streamGroundedSingleAttempt(
                apiKey = apiKey,
                model = candidateModel,
                systemInstruction = systemInstruction,
                userPrompt = userPrompt,
                onChunk = onChunk
            )
            if (groundedRes.isSuccess) {
                return@withContext groundedRes
            }
            val err = groundedRes.exceptionOrNull()
            val msg = err?.message.orEmpty()
            if (!msg.contains("429") && !msg.contains("404") && !msg.contains("RESOURCE_EXHAUSTED")) {
                break
            }
        }

        // If Grounding failed with 429 across models, fall back gracefully to Flash without grounding
        var ungroundedText = ""
        val ungroundedRes = streamChat(
            apiKey = apiKey,
            model = DEFAULT_FLASH_MODEL,
            systemInstruction = systemInstruction,
            history = emptyList(),
            userPrompt = userPrompt,
            enableGoogleSearch = false,
            backupGroqKey = backupGroqKey,
            onChunk = { partial ->
                ungroundedText = partial
                onChunk(partial, emptyList(), emptyList())
            }
        )

        if (ungroundedRes.isSuccess) {
            val cleanText = ungroundedRes.getOrNull().orEmpty()
            val note = "\n\n> ℹ️ *Search quota reached — answered using Flash intelligence without live web citations.*"
            val finalText = cleanText + note
            onChunk(finalText, emptyList(), emptyList())
            return@withContext Result.success(
                GroundedStreamResult(
                    text = finalText,
                    citations = emptyList(),
                    searchQueries = emptyList()
                )
            )
        }

        Result.failure(ungroundedRes.exceptionOrNull() ?: IllegalStateException("Research grounded search failed."))
    }

    private suspend fun streamGroundedSingleAttempt(
        apiKey: String,
        model: String,
        systemInstruction: String,
        userPrompt: String,
        onChunk: (accumulatedText: String, citations: List<GroundedCitation>, queries: List<String>) -> Unit
    ): Result<GroundedStreamResult> = withContext(Dispatchers.IO) {
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
            put("generationConfig", JSONObject().put("temperature", 0.7))
            put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        }

        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse&key=${apiKey.trim()}"

        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey.trim())
            .header("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        activeCall = call

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string()?.take(260) ?: "HTTP ${response.code}"
                    return@withContext Result.failure(
                        IllegalStateException("HTTP ${response.code}: $err")
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

    suspend fun generateOnce(
        apiKey: String,
        model: String = DEFAULT_FLASH_MODEL,
        systemInstruction: String,
        userPrompt: String,
        temperature: Double = 0.7,
        backupGroqKey: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        val modelsToTry = mutableListOf<String>()
        modelsToTry.add(model)
        for (m in standardFlashPriority) {
            if (!modelsToTry.contains(m)) modelsToTry.add(m)
        }

        if (apiKey.isNotBlank()) {
            for (candidate in modelsToTry) {
                val res = generateOnceSingleAttempt(apiKey, candidate, systemInstruction, userPrompt, temperature)
                if (res.isSuccess) return@withContext res
                val msg = res.exceptionOrNull()?.message.orEmpty()
                if (!msg.contains("429") && !msg.contains("404") && !msg.contains("RESOURCE_EXHAUSTED")) {
                    break
                }
            }
        }

        if (backupGroqKey.isNotBlank()) {
            val groqRes = executeOpenAiCompatibleChat(
                endpointUrl = "https://api.groq.com/openai/v1/chat/completions",
                apiKey = backupGroqKey,
                model = "llama-3.3-70b-versatile",
                systemInstruction = systemInstruction,
                history = emptyList(),
                userPrompt = userPrompt,
                onChunk = {}
            )
            if (groqRes.isSuccess) return@withContext groqRes
        }

        Result.failure(IllegalStateException("AI generation request failed across all models."))
    }

    private suspend fun generateOnceSingleAttempt(
        apiKey: String,
        model: String,
        systemInstruction: String,
        userPrompt: String,
        temperature: Double
    ): Result<String> = withContext(Dispatchers.IO) {
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
            .header("x-goog-api-key", apiKey.trim())
            .header("Content-Type", "application/json")
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
        model: String = DEFAULT_FLASH_MODEL,
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

        val modelsToTry = mutableListOf<String>()
        modelsToTry.add(model)
        for (m in standardFlashPriority) {
            if (!modelsToTry.contains(m)) modelsToTry.add(m)
        }

        for (candidateModel in modelsToTry) {
            val url =
                "https://generativelanguage.googleapis.com/v1beta/models/$candidateModel:streamGenerateContent?alt=sse&key=${apiKey.trim()}"

            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val call = client.newCall(request)
            activeCall = call

            try {
                val res = call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val err = response.body?.string()?.take(260) ?: "HTTP ${response.code}"
                        return@use Result.failure<String>(IllegalStateException("HTTP ${response.code}: $err"))
                    }

                    val stream = response.body?.byteStream()
                        ?: return@use Result.failure<String>(IllegalStateException("Empty Gemini stream."))

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
                if (res.isSuccess) return@withContext res
                val msg = res.exceptionOrNull()?.message.orEmpty()
                if (!msg.contains("429") && !msg.contains("404") && !msg.contains("RESOURCE_EXHAUSTED")) {
                    return@withContext res
                }
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            } finally {
                activeCall = null
            }
        }

        Result.failure(IllegalStateException("Multimodal generation failed."))
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
