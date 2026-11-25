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
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile
    private var activeCall: Call? = null

    private val modelCooldownUntilMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val COOLDOWN_DURATION_MS = 10L * 60L * 1000L // ১০ মিনিট

    @Volatile
    private var lastDiscoveredApiKey: String = ""
    @Volatile
    private var lastDiscoveryTimestampMs: Long = 0L
    @Volatile
    var cachedBestFlash: String = DEFAULT_FLASH_MODEL
        private set
    @Volatile
    var cachedBestPro: String = DEFAULT_PRO_MODEL
        private set
    @Volatile
    var cachedDiscoveredModels: List<String> = emptyList()
        private set

    fun isModelCoolingDown(model: String): Boolean {
        val until = modelCooldownUntilMs[model] ?: return false
        return System.currentTimeMillis() < until
    }

    fun markModelCooldown(model: String) {
        modelCooldownUntilMs[model] = System.currentTimeMillis() + COOLDOWN_DURATION_MS
    }

    fun primeDiscoveredModels(
        apiKey: String,
        models: List<String>,
        bestFlash: String?,
        bestPro: String?
    ) {
        val cleanKey = apiKey.trim()
        val valid = models.map { it.removePrefix("models/").trim() }
            .filter { isValidChatModel(it) }
            .distinct()
        if (cleanKey.isNotBlank() && valid.isNotEmpty()) {
            lastDiscoveredApiKey = cleanKey
            cachedDiscoveredModels = valid
            cachedBestFlash = bestFlash?.removePrefix("models/")?.trim()
                ?.takeIf { isValidChatModel(it) } ?: rankFlashModels(valid).firstOrNull() ?: DEFAULT_FLASH_MODEL
            cachedBestPro = bestPro?.removePrefix("models/")?.trim()
                ?.takeIf { isValidChatModel(it) } ?: rankProModels(valid).firstOrNull() ?: DEFAULT_PRO_MODEL
        }
    }

    fun clearDiscoveryCache() {
        lastDiscoveredApiKey = ""
        lastDiscoveryTimestampMs = 0L
        cachedDiscoveredModels = emptyList()
        cachedBestFlash = DEFAULT_FLASH_MODEL
        cachedBestPro = DEFAULT_PRO_MODEL
        modelCooldownUntilMs.clear()
    }

    companion object {
        val ALL_KNOWN_GEMINI_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.5-flash-lite",
            "gemini-2.0-flash"
        )

        const val DEFAULT_FLASH_MODEL = "gemini-2.5-flash"
        const val DEFAULT_PRO_MODEL = "gemini-2.5-pro"

        private val NON_CHAT_FRAGMENTS = listOf(
            "embedding", "imagen", "veo", "aqa", "tts", "-image", "image-preview",
            "robotics", "computer-use", "thinking-exp", "native-audio", "-audio",
            "live", "customtools", "gemini-1.0", "gemini-1.5"
        )

        private val VERSION_REGEX = Regex("gemini-(\\d+)(?:\\.(\\d+))?")
        private val SNAPSHOT_DATE_SUFFIX = Regex("-(?:\\d{3}|\\d{2}-\\d{2})$")

        fun isValidChatModel(id: String): Boolean {
            val clean = id.removePrefix("models/").trim()
            val lower = clean.lowercase()
            if (lower.isBlank()) return false
            if (NON_CHAT_FRAGMENTS.any { lower.contains(it) }) return false
            if (lower.contains("-exp")) return false
            return lower.startsWith("gemini-")
        }

        private fun modelRankScore(id: String): Int {
            val lower = id.removePrefix("models/").trim().lowercase()
            val m = VERSION_REGEX.find(lower)
            val versionScore = if (m != null) {
                val major = m.groupValues[1].toIntOrNull() ?: 0
                val minor = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
                major * 100 + minor
            } else if (lower.endsWith("-latest")) {
                249
            } else {
                100
            }
            val isPreview = lower.contains("preview")
            val isLite = lower.contains("lite")
            val isSnapshot = SNAPSHOT_DATE_SUFFIX.containsMatchIn(lower)
            var score = versionScore * 100
            if (!isPreview) score += 40
            if (!isLite) score += 20
            if (!isSnapshot) score += 10
            return score
        }

        fun rankFlashModels(ids: List<String>): List<String> {
            val valid = ids.map { it.removePrefix("models/").trim() }
                .filter { isValidChatModel(it) }
                .distinct()
            val flashOnly = valid.filter { it.contains("flash", ignoreCase = true) }
                .sortedByDescending { modelRankScore(it) }
            val fallback = valid.filterNot { it.contains("flash", ignoreCase = true) }
                .sortedByDescending { modelRankScore(it) }
            return flashOnly + fallback
        }

        fun rankProModels(ids: List<String>): List<String> {
            val valid = ids.map { it.removePrefix("models/").trim() }
                .filter { isValidChatModel(it) }
                .distinct()
            val proOnly = valid.filter {
                it.contains("pro", ignoreCase = true) || it.contains("ultra", ignoreCase = true)
            }.sortedByDescending { modelRankScore(it) }
            val fallback = valid.filterNot {
                it.contains("pro", ignoreCase = true) || it.contains("ultra", ignoreCase = true)
            }.sortedByDescending { modelRankScore(it) }
            return proOnly + fallback
        }
    }

    // Modern active fallback chain when key discovery isn't cached yet
    private val standardFlashPriority = listOf(
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-2.0-flash",
        "gemini-2.5-pro"
    )

    private val standardProPriority = listOf(
        "gemini-2.5-pro",
        "gemini-2.5-flash",
        "gemini-2.0-flash"
    )

    fun cancelActive() {
        activeCall?.cancel()
        activeCall = null
    }

    suspend fun ensureModelsDiscovered(
        apiKey: String,
        forceRefresh: Boolean = false
    ): ModelDiscoveryResult? {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) return null
        val freshEnough = (System.currentTimeMillis() - lastDiscoveryTimestampMs) < 30L * 60L * 1000L
        if (!forceRefresh && cleanKey == lastDiscoveredApiKey && cachedDiscoveredModels.isNotEmpty() && freshEnough) {
            return ModelDiscoveryResult(
                models = cachedDiscoveredModels.map { DiscoveredModel(it, it, "", listOf("generateContent")) },
                bestFlashModel = cachedBestFlash,
                bestProModel = cachedBestPro
            )
        }
        return discoverModels(cleanKey).getOrNull()
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

                val rawDiscovered = mutableListOf<DiscoveredModel>()
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

                    if (methods.contains("generateContent") && isValidChatModel(id)) {
                        rawDiscovered.add(DiscoveredModel(id, displayName, description, methods))
                    }
                }

                if (rawDiscovered.isEmpty()) {
                    return@withContext Result.failure(IllegalStateException("No content generation models found for this API key."))
                }

                val rankedFlashIds = rankFlashModels(rawDiscovered.map { it.id })
                val rankedProIds = rankProModels(rawDiscovered.map { it.id })
                val bestFlash = rankedFlashIds.firstOrNull() ?: DEFAULT_FLASH_MODEL
                val bestPro = rankedProIds.firstOrNull() ?: bestFlash

                val orderedIds = (listOf(bestFlash, bestPro) + rankedFlashIds + rankedProIds).distinct()
                val byId = rawDiscovered.associateBy { it.id }
                val sortedModels = orderedIds.mapNotNull { byId[it] }

                lastDiscoveredApiKey = cleanKey
                lastDiscoveryTimestampMs = System.currentTimeMillis()
                cachedBestFlash = bestFlash
                cachedBestPro = bestPro
                cachedDiscoveredModels = orderedIds

                Result.success(ModelDiscoveryResult(sortedModels, bestFlash, bestPro))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun buildCandidateModels(
        apiKey: String,
        requestedModel: String,
        preferPro: Boolean = false
    ): List<String> {
        val cleanKey = apiKey.trim()
        val discovery = if (cleanKey.isNotBlank()) ensureModelsDiscovered(cleanKey) else null
        val liveList = discovery?.models?.map { it.id } ?: cachedDiscoveredModels

        val cleanReq = requestedModel.removePrefix("models/").trim()
        val modelsToTry = mutableListOf<String>()

        if (isValidChatModel(cleanReq) && (liveList.isEmpty() || liveList.contains(cleanReq))) {
            modelsToTry.add(cleanReq)
        }

        val livePreferred = if (preferPro) cachedBestPro else cachedBestFlash
        if (isValidChatModel(livePreferred) && !modelsToTry.contains(livePreferred)) {
            modelsToTry.add(livePreferred)
        }

        val rankedLive = if (preferPro) rankProModels(liveList) else rankFlashModels(liveList)
        for (m in rankedLive) {
            if (!modelsToTry.contains(m)) modelsToTry.add(m)
        }

        val fallbackList = if (preferPro) standardProPriority else standardFlashPriority
        for (m in fallbackList) {
            if (!modelsToTry.contains(m)) modelsToTry.add(m)
        }

        val nonCooling = modelsToTry.filter { !isModelCoolingDown(it) }
        return if (nonCooling.isNotEmpty()) nonCooling else modelsToTry
    }

    suspend fun testAiConnection(
        geminiKey: String,
        selectedModel: String = "",
        groqKey: String = "",
        openRouterKey: String = ""
    ): String = withContext(Dispatchers.IO) {
        val cleanKey = geminiKey.trim()
        if (cleanKey.isNotBlank()) {
            val discovery = ensureModelsDiscovered(cleanKey, forceRefresh = true)
            val candidateTestModels = buildCandidateModels(
                apiKey = cleanKey,
                requestedModel = selectedModel.ifBlank { discovery?.bestFlashModel ?: "" },
                preferPro = false
            )
            var lastErr = ""
            for (testModel in candidateTestModels.take(6)) {
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
                            val cleanSelected = selectedModel.removePrefix("models/").trim()
                            val autoNotice = if (cleanSelected.isNotBlank() && cleanSelected != testModel) " (Auto-Selected: $testModel)" else ""
                            return@withContext "✅ Gemini Connected$autoNotice: $testModel (Online & Ready)"
                        } else if (resp.code == 429) {
                            markModelCooldown(testModel)
                            lastErr = "⚠️ Gemini Quota Exhausted (429) on $testModel"
                        } else {
                            if (resp.code == 404 || resp.code == 400) markModelCooldown(testModel)
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
     * Executes true SSE line-by-line streaming for Groq or OpenRouter
     */
    suspend fun streamOpenAiCompatible(
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
        for ((role, text) in history.takeLast(6)) {
            val mappedRole = if (role == "model" || role == "assistant") "assistant" else "user"
            messages.put(JSONObject().put("role", mappedRole).put("content", text))
        }
        messages.put(JSONObject().put("role", "user").put("content", userPrompt))

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.75)
            put("stream", true)
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
            val fullText = StringBuilder()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val rawErr = resp.body?.string().orEmpty().take(180)
                    return@withContext Result.failure(IllegalStateException("HTTP ${resp.code}: $rawErr"))
                }
                val stream = resp.body?.byteStream()
                    ?: return@withContext Result.failure(IllegalStateException("Empty response body"))
                val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                reader.useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (!trimmed.startsWith("data:")) continue
                        val data = trimmed.removePrefix("data:").trim()
                        if (data == "[DONE]" || data.isEmpty()) break
                        val delta = runCatching {
                            JSONObject(data)
                                .optJSONArray("choices")
                                ?.optJSONObject(0)
                                ?.optJSONObject("delta")
                                ?.optString("content", "")
                                .orEmpty()
                        }.getOrDefault("")
                        if (delta.isNotEmpty()) {
                            fullText.append(delta)
                            onChunk(fullText.toString())
                        }
                    }
                }
            }
            val res = fullText.toString().trim()
            if (res.isNotEmpty()) Result.success(res) else Result.failure(IllegalStateException("Empty stream reply"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun executeOpenAiCompatibleChat(
        endpointUrl: String,
        apiKey: String,
        model: String,
        systemInstruction: String,
        history: List<Pair<String, String>>,
        userPrompt: String,
        onChunk: (accumulatedText: String) -> Unit
    ): Result<String> = streamOpenAiCompatible(
        endpointUrl = endpointUrl,
        apiKey = apiKey,
        model = model,
        systemInstruction = systemInstruction,
        history = history,
        userPrompt = userPrompt,
        onChunk = onChunk
    )

    suspend fun streamChatWithFallback(
        geminiKey: String,
        preferredModel: String,
        systemInstruction: String,
        userPrompt: String,
        history: List<Pair<String, String>> = emptyList(),
        groqKey: String = "",
        openRouterKey: String = "",
        onToken: (String) -> Unit
    ): Result<String> = streamChat(
        apiKey = geminiKey,
        model = preferredModel,
        systemInstruction = systemInstruction,
        history = history,
        userPrompt = userPrompt,
        backupGroqKey = groqKey,
        backupOpenRouterKey = openRouterKey,
        onChunk = onToken
    )

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

        val finalModelsToTry = buildCandidateModels(
            apiKey = apiKey,
            requestedModel = model,
            preferPro = model.contains("pro", ignoreCase = true)
        )

        var lastError: Throwable? = null

        if (apiKey.isNotBlank()) {
            for (candidateModel in finalModelsToTry) {
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
                if (msg.contains("429") || msg.contains("404") || msg.contains("400") ||
                    msg.contains("RESOURCE_EXHAUSTED") || msg.contains("NOT_FOUND")
                ) {
                    markModelCooldown(candidateModel)
                }
                val isFatalAuth = msg.contains("API_KEY_INVALID") || msg.contains("API key not valid") ||
                        msg.contains("PERMISSION_DENIED") || msg.contains("HTTP 403") || msg.contains("HTTP 401")
                if (isFatalAuth) {
                    break
                }
            }
        }

        // Seamless Failover: Groq True SSE Streaming Backup
        if (backupGroqKey.isNotBlank()) {
            val groqRes = streamOpenAiCompatible(
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

        // Seamless Failover: OpenRouter True SSE Streaming Backup
        if (backupOpenRouterKey.isNotBlank()) {
            val openRouterRes = streamOpenAiCompatible(
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
        val targetModel = model.removePrefix("models/").trim()
        if (targetModel.isBlank() || targetModel == "gemini-exp-1206") {
            return@withContext Result.failure(IllegalStateException("Model $targetModel is not found or unsupported."))
        }

        // Clean & sanitize history turns to strictly satisfy Gemini alternating turn requirement
        val cleanHistory = mutableListOf<Pair<String, String>>()
        for ((role, text) in history) {
            val cleanText = text.trim()
            if (cleanText.isBlank() || cleanText.startsWith("Online AI error") || cleanText.startsWith("Error:")) {
                continue
            }
            val normRole = if (role == "user") "user" else "model"
            if (cleanHistory.isNotEmpty() && cleanHistory.last().first == normRole) {
                val lastPair = cleanHistory.removeAt(cleanHistory.size - 1)
                cleanHistory.add(normRole to "${lastPair.second}\n$cleanText")
            } else {
                cleanHistory.add(normRole to cleanText)
            }
        }

        // Ensure first turn in history is "user"
        while (cleanHistory.isNotEmpty() && cleanHistory.first().first != "user") {
            cleanHistory.removeAt(0)
        }

        val contents = JSONArray()
        for ((role, text) in cleanHistory.takeLast(10)) {
            contents.put(
                JSONObject().apply {
                    put("role", role)
                    put("parts", JSONArray().put(JSONObject().put("text", text)))
                }
            )
        }

        val isGemmaModel = targetModel.lowercase().startsWith("gemma")
        if (cleanHistory.isNotEmpty() && cleanHistory.last().first == "user") {
            val lastObj = contents.getJSONObject(contents.length() - 1)
            val partsArr = lastObj.getJSONArray("parts")
            val existingText = partsArr.getJSONObject(0).getString("text")
            val combined = if (isGemmaModel && systemInstruction.isNotBlank() && cleanHistory.size == 1) {
                "Instructions:\n$systemInstruction\n\nUser:\n$existingText\n${userPrompt.trim()}"
            } else {
                "$existingText\n${userPrompt.trim()}"
            }
            partsArr.put(0, JSONObject().put("text", combined))
        } else {
            val userText = if (isGemmaModel && systemInstruction.isNotBlank() && cleanHistory.isEmpty()) {
                "Instructions:\n$systemInstruction\n\nUser:\n${userPrompt.trim()}"
            } else {
                userPrompt.trim()
            }
            contents.put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", userText)))
                }
            )
        }

        val bodyJson = JSONObject().apply {
            if (!isGemmaModel && systemInstruction.isNotBlank()) {
                put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction.trim())))
                )
            }
            put("contents", contents)
            put("generationConfig", JSONObject().put("temperature", 0.85))
            if (!isGemmaModel && enableGoogleSearch) {
                put("tools", JSONArray().put(JSONObject().put("googleSearch", JSONObject())))
            }
        }

        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/$targetModel:streamGenerateContent?alt=sse&key=${apiKey.trim()}"

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
                    val rawErr = response.body?.string().orEmpty().take(250)
                    val err = if (rawErr.isNotBlank()) rawErr else "HTTP ${response.code}"
                    if (response.code == 429 || response.code == 404 || err.contains("RESOURCE_EXHAUSTED") || err.contains("NOT_FOUND")) {
                        markModelCooldown(targetModel)
                    }
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

        val modelsToTry = buildCandidateModels(
            apiKey = apiKey,
            requestedModel = model,
            preferPro = model.contains("pro", ignoreCase = true)
        )

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
            if (msg.contains("429") || msg.contains("404") || msg.contains("400") ||
                msg.contains("RESOURCE_EXHAUSTED") || msg.contains("NOT_FOUND")
            ) {
                markModelCooldown(candidateModel)
            }
            val isFatalAuth = msg.contains("API_KEY_INVALID") || msg.contains("API key not valid") ||
                    msg.contains("PERMISSION_DENIED") || msg.contains("HTTP 403") || msg.contains("HTTP 401")
            if (isFatalAuth) {
                break
            }
        }

        // If Grounding failed with 429 across models, fall back gracefully to Flash without grounding
        var ungroundedText = ""
        val ungroundedRes = streamChat(
            apiKey = apiKey,
            model = cachedBestFlash,
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
            put("tools", JSONArray().put(JSONObject().put("googleSearch", JSONObject())))
        }

        val targetModel = model.removePrefix("models/").trim()
        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/$targetModel:streamGenerateContent?alt=sse&key=${apiKey.trim()}"

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
        val modelsToTry = buildCandidateModels(
            apiKey = apiKey,
            requestedModel = model,
            preferPro = model.contains("pro", ignoreCase = true)
        )

        if (apiKey.isNotBlank()) {
            for (candidate in modelsToTry) {
                val res = generateOnceSingleAttempt(apiKey, candidate, systemInstruction, userPrompt, temperature)
                if (res.isSuccess) return@withContext res
                val msg = res.exceptionOrNull()?.message.orEmpty()
                if (msg.contains("429") || msg.contains("404") || msg.contains("400") ||
                    msg.contains("RESOURCE_EXHAUSTED") || msg.contains("NOT_FOUND")
                ) {
                    markModelCooldown(candidate)
                }
                val isFatalAuth = msg.contains("API_KEY_INVALID") || msg.contains("API key not valid") ||
                        msg.contains("PERMISSION_DENIED") || msg.contains("HTTP 403") || msg.contains("HTTP 401")
                if (isFatalAuth) {
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
        val targetModel = model.removePrefix("models/").trim()
        val isGemmaModel = targetModel.lowercase().startsWith("gemma")
        val finalUserPrompt = if (isGemmaModel && systemInstruction.isNotBlank()) {
            "Instructions:\n$systemInstruction\n\nUser:\n$userPrompt"
        } else {
            userPrompt
        }
        val bodyJson = JSONObject().apply {
            if (!isGemmaModel && systemInstruction.isNotBlank()) {
                put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                )
            }
            put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", finalUserPrompt)))
                )
            )
            put("generationConfig", JSONObject().put("temperature", temperature))
        }

        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/$targetModel:generateContent?key=${apiKey.trim()}"

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

        val modelsToTry = buildCandidateModels(
            apiKey = apiKey,
            requestedModel = model,
            preferPro = model.contains("pro", ignoreCase = true)
        )

        for (candidateModel in modelsToTry) {
            val targetModel = candidateModel.removePrefix("models/").trim()
            val url =
                "https://generativelanguage.googleapis.com/v1beta/models/$targetModel:streamGenerateContent?alt=sse&key=${apiKey.trim()}"

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
                val isFatalAuth = msg.contains("API_KEY_INVALID") || msg.contains("API key not valid") ||
                        msg.contains("PERMISSION_DENIED") || msg.contains("HTTP 403") || msg.contains("HTTP 401")
                if (isFatalAuth) {
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

    /**
     * Transcribes direct hardware AudioRecord WAV bytes cleanly via live Gemini Flash multimodal audio,
     * preserving Bengali, Banglish, and English accurately without any Google Speech Activity popup.
     */
    suspend fun transcribeAudioWav(
        apiKey: String,
        wavBytes: ByteArray
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("API key is blank"))
        }
        val b64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val prompt = """
            Listen carefully to the user voice audio (could be Bengali, Banglish, or English).
            Output ONLY the exact spoken words cleanly in natural Bengali/English/Banglish script with no extra commentary.
            If there is only background noise or silence, output [SILENCE].
        """.trimIndent()

        val bodyJson = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "parts",
                            JSONArray()
                                .put(
                                    JSONObject().put(
                                        "inlineData",
                                        JSONObject()
                                            .put("mimeType", "audio/wav")
                                            .put("data", b64)
                                    )
                                )
                                .put(JSONObject().put("text", prompt))
                        )
                )
            )
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.0)
                put("maxOutputTokens", 180)
            })
        }

        val modelsToTry = buildCandidateModels(cleanKey, cachedBestFlash, preferPro = false)
        for (candidate in modelsToTry) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$candidate:generateContent?key=$cleanKey"
            val req = Request.Builder()
                .url(url)
                .header("x-goog-api-key", cleanKey)
                .header("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val txt = JSONObject(resp.body?.string().orEmpty())
                            .optJSONArray("candidates")
                            ?.optJSONObject(0)
                            ?.optJSONObject("content")
                            ?.optJSONArray("parts")
                            ?.optJSONObject(0)
                            ?.optString("text", "")
                            .orEmpty()
                            .trim()
                        if (txt.isNotBlank()) return@withContext Result.success(txt)
                    } else if (resp.code == 404 || resp.code == 429 || resp.code == 400) {
                        markModelCooldown(candidate)
                    }
                }
            } catch (_: Exception) {
                // cascade to next model
            }
        }
        Result.failure(IllegalStateException("Audio transcription failed"))
    }

    /**
     * Generates a strict JSON object response for autonomous multi-step screen agent decisions.
     */
    suspend fun generateJson(
        apiKey: String,
        systemInstruction: String,
        userPrompt: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) return@withContext Result.failure(IllegalArgumentException("API key is blank"))

        val bodyJson = JSONObject().apply {
            if (systemInstruction.isNotBlank()) {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))))
            }
            put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                )
            )
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.15)
                put("responseMimeType", "application/json")
                put("maxOutputTokens", 450)
            })
        }

        val modelsToTry = buildCandidateModels(cleanKey, cachedBestFlash, preferPro = false)
        var lastErr = "JSON request failed"
        for (candidate in modelsToTry) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$candidate:generateContent?key=$cleanKey"
            val req = Request.Builder()
                .url(url)
                .header("x-goog-api-key", cleanKey)
                .header("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val txt = JSONObject(resp.body?.string().orEmpty())
                            .optJSONArray("candidates")
                            ?.optJSONObject(0)
                            ?.optJSONObject("content")
                            ?.optJSONArray("parts")
                            ?.optJSONObject(0)
                            ?.optString("text", "")
                            .orEmpty()
                            .trim()
                        if (txt.isNotBlank()) return@withContext Result.success(txt)
                    } else {
                        if (resp.code == 404 || resp.code == 429 || resp.code == 400) {
                            markModelCooldown(candidate)
                        }
                        lastErr = "HTTP ${resp.code}"
                    }
                }
            } catch (e: Exception) {
                lastErr = e.message ?: "Network error"
            }
        }
        Result.failure(IllegalStateException(lastErr))
    }
}
