package com.zeeai.companion.studios.council

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeeai.companion.ai.GeminiNativeClient
import com.zeeai.companion.data.local.SecureKeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val BgNavy = Color(0xFF060913)
private val CardNavy = Color(0xFF0B1120)
private val Silver = Color(0xFFC8D1E0)
private val ElectricBlue = Color(0xFF2F80FF)

private enum class MemberStatus { RUNNING, DONE, ERROR }

private data class CouncilMemberOutput(
    val id: String,
    val displayName: String,
    val roleTag: String,
    val status: MemberStatus,
    val text: String = "",
    val error: String = "",
    val latencyMs: Long = 0L
)

private val externalHttpClient = OkHttpClient.Builder()
    .connectTimeout(25, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .build()

@Composable
fun AICouncilScreen(
    keyVault: SecureKeyVault,
    geminiClient: GeminiNativeClient,
    isOnline: () -> Boolean
) {
    val scope = rememberCoroutineScope()

    var prompt by remember { mutableStateOf("") }
    var useHydrogen by remember { mutableStateOf(true) }
    var useCarbon by remember { mutableStateOf(true) }
    var useFlashLite by remember { mutableStateOf(true) }
    var useExternal by remember { mutableStateOf(false) }

    // Optional External OpenAI-Compatible Provider Config (persisted in Encrypted Vault)
    var showExternalConfig by remember { mutableStateOf(false) }
    var extName by remember { mutableStateOf(keyVault.getKey("council_ext_name")) }
    var extBaseUrl by remember { mutableStateOf(keyVault.getKey("council_ext_base_url")) }
    var extModelId by remember { mutableStateOf(keyVault.getKey("council_ext_model_id")) }
    var extApiKey by remember { mutableStateOf(keyVault.getKey("council_ext_api_key")) }

    var memberOutputs by remember { mutableStateOf<List<CouncilMemberOutput>>(emptyList()) }
    var verdictText by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("") }
    var runningCouncil by remember { mutableStateOf(false) }
    var synthesizing by remember { mutableStateOf(false) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    suspend fun callOpenAiCompatible(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        userPrompt: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = "${baseUrl.trim().trimEnd('/')}/chat/completions"
            val payload = JSONObject().apply {
                put("model", modelId.trim())
                put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", "Provide a clear, sharp, well-reasoned answer."))
                        .put(JSONObject().put("role", "user").put("content", userPrompt))
                )
            }
            val req = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            externalHttpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code}: ${raw.take(200)}")
                }
                JSONObject(raw)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?.trim()
                    .orEmpty()
                    .ifEmpty { throw IllegalStateException("Provider returned empty content.") }
            }
        }
    }

    fun conveneCouncil() {
        val question = prompt.trim()
        if (question.isEmpty() || runningCouncil) return
        if (!isOnline()) {
            statusText = "Offline: AI Council requires internet to query multiple models concurrently."
            return
        }

        val geminiKey = keyVault.getKey("gemini_api_key")
        val proModel = keyVault.getKey("gemini_pro_model").ifBlank { "gemini-2.5-pro" }
        val flashModel = keyVault.getKey("gemini_flash_model").ifBlank { "gemini-2.5-flash" }

        val tasks = mutableListOf<Triple<String, String, suspend () -> Result<String>>>()

        if (useHydrogen && geminiKey.isNotBlank()) {
            tasks.add(
                Triple(flashModel, "$flashModel • Fast Pragmatist") {
                    geminiClient.generateOnce(
                        apiKey = geminiKey,
                        model = flashModel,
                        systemInstruction = "Give a crisp, practical, directly actionable answer.",
                        userPrompt = question
                    )
                }
            )
        }
        if (useCarbon && geminiKey.isNotBlank()) {
            tasks.add(
                Triple(proModel, "$proModel • Flagship Strategist") {
                    geminiClient.generateOnce(
                        apiKey = geminiKey,
                        model = proModel,
                        systemInstruction = "Give a deeply reasoned, critical, edge-case-aware strategic analysis.",
                        userPrompt = question
                    )
                }
            )
        }
        if (useFlashLite && geminiKey.isNotBlank()) {
            tasks.add(
                Triple(flashModel, "$flashModel • Rapid Check") {
                    geminiClient.generateOnce(
                        apiKey = geminiKey,
                        model = flashModel,
                        systemInstruction = "Provide the tightest high-signal summary and key trade-offs.",
                        userPrompt = question
                    )
                }
            )
        }
        if (useExternal && extBaseUrl.isNotBlank() && extModelId.isNotBlank() && extApiKey.isNotBlank()) {
            val label = "${extName.ifBlank { "External" }} • $extModelId"
            tasks.add(
                Triple("external-provider", label) {
                    callOpenAiCompatible(extBaseUrl, extApiKey, extModelId, question)
                }
            )
        }

        if (tasks.isEmpty()) {
            statusText = "Enable at least one model and configure your Gemini API key (or external provider)."
            return
        }

        activeJob?.cancel()
        runningCouncil = true
        verdictText = ""
        statusText = "Querying ${tasks.size} models in parallel..."

        memberOutputs = tasks.map { (id, label, _) ->
            val parts = label.split(" • ")
            CouncilMemberOutput(
                id = id,
                displayName = parts.firstOrNull() ?: label,
                roleTag = parts.getOrNull(1) ?: "Member",
                status = MemberStatus.RUNNING
            )
        }

        activeJob = scope.launch {
            val results = coroutineScope {
                tasks.map { (id, label, runner) ->
                    async {
                        val start = SystemClock.elapsedRealtime()
                        val res = runner()
                        val elapsed = SystemClock.elapsedRealtime() - start
                        val parts = label.split(" • ")
                        val name = parts.firstOrNull() ?: label
                        val role = parts.getOrNull(1) ?: "Member"

                        val finishedItem = res.fold(
                            onSuccess = { txt ->
                                CouncilMemberOutput(
                                    id = id,
                                    displayName = name,
                                    roleTag = role,
                                    status = MemberStatus.DONE,
                                    text = txt,
                                    latencyMs = elapsed
                                )
                            },
                            onFailure = { err ->
                                CouncilMemberOutput(
                                    id = id,
                                    displayName = name,
                                    roleTag = role,
                                    status = MemberStatus.ERROR,
                                    error = err.message ?: "Request failed",
                                    latencyMs = elapsed
                                )
                            }
                        )

                        // Update this model's card immediately as soon as it finishes
                        withContext(Dispatchers.Main) {
                            memberOutputs = memberOutputs.map { existing ->
                                if (existing.id == id) finishedItem else existing
                            }
                        }
                        finishedItem
                    }
                }.awaitAll()
            }

            val okCount = results.count { it.status == MemberStatus.DONE }
            statusText = "Council finished: $okCount/${results.size} models succeeded."
            runningCouncil = false
        }
    }

    fun synthesizeVerdict() {
        val successful = memberOutputs.filter { it.status == MemberStatus.DONE && it.text.isNotBlank() }
        val geminiKey = keyVault.getKey("gemini_api_key")
        if (successful.size < 2 || geminiKey.isBlank() || synthesizing) return

        synthesizing = true
        verdictText = ""
        val proModel = keyVault.getKey("gemini_pro_model").ifBlank { "gemini-2.5-pro" }
        statusText = "Synthesizing comparison & final verdict with $proModel..."

        val comparisonPayload = successful.mapIndexed { i, m ->
            "### Model ${i + 1}: ${m.displayName} (${m.roleTag}, ${m.latencyMs} ms)\n${m.text}"
        }.joinToString("\n\n---\n\n")

        scope.launch {
            val res = geminiClient.streamChat(
                apiKey = geminiKey,
                model = proModel,
                systemInstruction = "You are the Chair of ZEE AI Council. Output: 1) Strength & Weakness comparison per model, 2) Best Individual Answer (with why), and 3) Unified Combined Final Answer taking the strongest insights from all models.",
                history = emptyList(),
                userPrompt = "Original Question: \"${prompt.trim()}\"\n\nResponses:\n$comparisonPayload",
                onChunk = { partial ->
                    scope.launch(Dispatchers.Main) { verdictText = partial }
                }
            )
            res.onSuccess {
                verdictText = it
                statusText = "Council Verdict ready."
            }.onFailure { err ->
                statusText = "Synthesis error: ${err.message}"
            }
            synthesizing = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Prompt & Model Selector Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardNavy, RoundedCornerShape(16.dp))
                .border(1.dp, Silver.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("AI Council (Concurrent Multi-Model)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                "Query multiple AI models simultaneously, measure real latency, compare strengths & weaknesses, and synthesize one best answer.",
                color = Silver.copy(alpha = 0.75f),
                fontSize = 11.sp
            )

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Council Question") },
                placeholder = { Text("Ask a strategy, coding, filmmaking, or high-stakes decision question...") },
                minLines = 3,
                maxLines = 6
            )

            // Model Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = useHydrogen,
                    onClick = { useHydrogen = !useHydrogen },
                    label = { Text("4 Hydrogen", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = useCarbon,
                    onClick = { useCarbon = !useCarbon },
                    label = { Text("4 Carbon", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = useFlashLite,
                    onClick = { useFlashLite = !useFlashLite },
                    label = { Text("3.5 Lite", fontSize = 11.sp) }
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = useExternal,
                    onClick = { useExternal = !useExternal },
                    label = { Text("External API", fontSize = 11.sp) }
                )
                TextButton(onClick = { showExternalConfig = !showExternalConfig }) {
                    Text(if (showExternalConfig) "Hide Provider Config" else "Configure External Provider", fontSize = 11.sp)
                }
            }

            if (showExternalConfig) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BgNavy, RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(value = extName, onValueChange = { extName = it }, label = { Text("Provider Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = extBaseUrl, onValueChange = { extBaseUrl = it }, label = { Text("Base URL (e.g. https://openrouter.ai/api/v1)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = extModelId, onValueChange = { extModelId = it }, label = { Text("Model ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(
                        value = extApiKey,
                        onValueChange = { extApiKey = it },
                        label = { Text("API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(onClick = {
                        keyVault.saveKey("council_ext_name", extName)
                        keyVault.saveKey("council_ext_base_url", extBaseUrl)
                        keyVault.saveKey("council_ext_model_id", extModelId)
                        keyVault.saveKey("council_ext_api_key", extApiKey)
                        useExternal = true
                        showExternalConfig = false
                        statusText = "External provider encrypted & saved in Keystore."
                    }) { Text("Save External Provider", fontSize = 11.sp) }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = prompt.isNotBlank() && !runningCouncil,
                    modifier = Modifier.weight(1f),
                    onClick = { conveneCouncil() }
                ) {
                    Text(if (runningCouncil) "Running Council..." else "Run AI Council", fontSize = 12.sp)
                }

                if (runningCouncil) {
                    OutlinedButton(onClick = {
                        activeJob?.cancel()
                        runningCouncil = false
                        statusText = "Council cancelled."
                    }) { Text("Cancel", color = Color(0xFFF87171), fontSize = 12.sp) }
                } else {
                    OutlinedButton(
                        enabled = memberOutputs.count { it.status == MemberStatus.DONE } >= 2 && !synthesizing,
                        onClick = { synthesizeVerdict() }
                    ) {
                        Text(if (synthesizing) "Synthesizing..." else "Synthesize Verdict", fontSize = 11.sp)
                    }
                }
            }

            if (statusText.isNotBlank()) {
                Text(statusText, color = ElectricBlue, fontSize = 11.sp)
            }
        }

        // Parallel Model Response Cards
        memberOutputs.forEach { member ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardNavy, RoundedCornerShape(16.dp))
                    .border(
                        1.dp,
                        when (member.status) {
                            MemberStatus.DONE -> Color(0xFF34D399).copy(alpha = 0.35f)
                            MemberStatus.ERROR -> Color(0xFFF87171).copy(alpha = 0.4f)
                            MemberStatus.RUNNING -> ElectricBlue.copy(alpha = 0.45f)
                        },
                        RoundedCornerShape(16.dp)
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(member.displayName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(member.roleTag, color = Silver.copy(alpha = 0.7f), fontSize = 11.sp)
                    }
                    when (member.status) {
                        MemberStatus.RUNNING -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        MemberStatus.DONE -> Text("${member.latencyMs} ms", color = Color(0xFF34D399), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        MemberStatus.ERROR -> Text("Failed", color = Color(0xFFF87171), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BgNavy, RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = when (member.status) {
                            MemberStatus.RUNNING -> "Waiting for response..."
                            MemberStatus.DONE -> member.text
                            MemberStatus.ERROR -> "Error: ${member.error}"
                        },
                        color = if (member.status == MemberStatus.ERROR) Color(0xFFFCA5A5) else Color.White,
                        fontSize = 12.sp,
                        lineHeight = 19.sp
                    )
                }
            }
        }

        // Synthesized Final Verdict
        if (verdictText.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardNavy, RoundedCornerShape(16.dp))
                    .border(1.dp, ElectricBlue.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "COUNCIL VERDICT & COMBINED SYNTHESIS",
                    color = ElectricBlue,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 0.8.sp
                )
                Text(verdictText, color = Color.White, fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
    }
}
