package com.zeeai.companion.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.zeeai.companion.automation.OfflineAutonomousPhoneController
import com.zeeai.companion.offline.BestFriendPersona
import com.zeeai.companion.offline.OfflineBrainCoordinator
import com.zeeai.companion.offline.OfflineChatTurn
import com.zeeai.companion.offline.OfflineModelResolver

enum class ActiveEngineRoute { ONLINE, OFFLINE }

data class RouterConfig(
    val route: ActiveEngineRoute,
    val selectedOnlineModel: String,
    val selectedOfflineModelPath: String?,
    val persona: BestFriendPersona,
    val geminiApiKey: String,
    val groqApiKey: String,
    val openRouterApiKey: String,
    val customProviderPreset: String = "gemini",
    val customBaseUrl: String = "",
    val customApiKey: String = "",
    val hermesPersonaDirective: String = "",
    val localFirstPrivacy: Boolean = false
)

class UnifiedCommandAndChatRouter(
    private val context: Context,
    private val phoneController: OfflineAutonomousPhoneController,
    private val offlineBrain: OfflineBrainCoordinator,
    private val geminiClient: GeminiNativeClient
) {
    fun isInternetAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun handleUserInput(
        userText: String,
        config: RouterConfig,
        history: List<OfflineChatTurn>,
        onStreamingText: (String) -> Unit
    ): String {
        val cleanInput = userText.trim()
        if (cleanInput.isEmpty()) return ""

        // 1. Instant offline hardware/app voice control (<50ms)
        val instantAction = phoneController.tryInstantOfflineCommand(cleanInput)
        if (instantAction.handled) {
            onStreamingText(instantAction.reply)
            return instantAction.reply
        }

        // 2. Local Offline Gemma Brain if requested, private lock active, or no internet
        val shouldRunOffline = (config.route == ActiveEngineRoute.OFFLINE) || config.localFirstPrivacy || !isInternetAvailable()
        if (shouldRunOffline) {
            val activeModel = config.selectedOfflineModelPath
                ?.takeIf { java.io.File(it).exists() }
                ?: OfflineModelResolver.resolveActiveModel(context)?.filePath

            return offlineBrain.processOfflineTurn(
                userMessage = cleanInput,
                persona = config.persona,
                recentHistory = history,
                installedModelPath = activeModel,
                onStreamingUpdate = onStreamingText
            )
        }

        val systemPrompt = buildOnlineHumanSystemPrompt(config.persona, config.hermesPersonaDirective)
        val historyPairs = history.takeLast(6).map { (if (it.isUser) "user" else "model") to it.text }

        // 3. Optional Custom OpenAI-Compatible Provider Preset (DeepSeek / OpenAI / NVIDIA / Ollama)
        val onlineResult = if (config.customProviderPreset != "gemini" && config.customBaseUrl.isNotBlank()) {
            val keyToUse = when (config.customProviderPreset) {
                "groq" -> config.customApiKey.ifBlank { config.groqApiKey }
                "openrouter" -> config.customApiKey.ifBlank { config.openRouterApiKey }
                else -> config.customApiKey.ifBlank { config.geminiApiKey }
            }
            val base = config.customBaseUrl.trim().trimEnd('/')
            val endpoint = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
            val customAttempt = geminiClient.executeOpenAiCompatibleChat(
                endpointUrl = endpoint,
                apiKey = keyToUse.ifEmpty { "ollama" },
                model = config.selectedOnlineModel,
                systemInstruction = systemPrompt,
                history = historyPairs,
                userPrompt = cleanInput,
                onChunk = { partial -> onStreamingText(partial.substringBefore("[[TOOL:").trim()) }
            )
            if (customAttempt.isSuccess) customAttempt
            else geminiClient.streamChatWithFallback(
                geminiKey = config.geminiApiKey,
                preferredModel = config.selectedOnlineModel,
                systemInstruction = systemPrompt,
                userPrompt = cleanInput,
                history = historyPairs,
                groqKey = config.groqApiKey,
                openRouterKey = config.openRouterApiKey,
                onToken = { partial -> onStreamingText(partial.substringBefore("[[TOOL:").trim()) }
            )
        } else {
            geminiClient.streamChatWithFallback(
                geminiKey = config.geminiApiKey,
                preferredModel = config.selectedOnlineModel,
                systemInstruction = systemPrompt,
                userPrompt = cleanInput,
                history = historyPairs,
                groqKey = config.groqApiKey,
                openRouterKey = config.openRouterApiKey,
                onToken = { partial -> onStreamingText(partial.substringBefore("[[TOOL:").trim()) }
            )
        }

        return onlineResult.fold(
            onSuccess = { rawReply ->
                val (cleanReply, toolLogs) = phoneController.executeToolsInAiResponse(rawReply)
                val finalReply = buildString {
                    if (cleanReply.isNotBlank()) append(cleanReply)
                    if (toolLogs.isNotEmpty()) {
                        if (isNotEmpty()) append("\n\n")
                        append("⚡ ").append(toolLogs.joinToString(" • "))
                    }
                }.ifBlank { "আমি আছি বস, হুকুম করুন কী করতে হবে!" }
                onStreamingText(finalReply)
                finalReply
            },
            onFailure = { err ->
                val fallbackOffline = OfflineModelResolver.resolveActiveModel(context)?.filePath
                if (fallbackOffline != null) {
                    offlineBrain.processOfflineTurn(
                        userMessage = cleanInput,
                        persona = config.persona,
                        recentHistory = history,
                        installedModelPath = fallbackOffline,
                        onStreamingUpdate = onStreamingText
                    )
                } else {
                    val notice = "⚠️ অনলাইন রিপ্লাই পাওয়া যায়নি (${err.message ?: "Network/Quota"})। অফলাইন বক্স থেকে ডাউনলোড করা Gemma মডেল সিলেক্ট করুন বস।"
                    onStreamingText(notice)
                    notice
                }
            }
        )
    }

    private fun buildOnlineHumanSystemPrompt(persona: BestFriendPersona, hermesDirective: String): String {
        val identity = if (persona == BestFriendPersona.ZEE) {
            "You are ZEE, user's sharp, witty, loyal male AI companion. The user is your Boss. Always address the user respectfully and loyally as 'বস' (Boss / বস), e.g., 'হ্যাঁ বস', 'খুলে দিয়েছি বস', 'করেছি বস', 'করতেছি বস'. Never say 'বন্ধু' or 'As an AI'."
        } else {
            "You are ZHEE, user's warm, empathetic, caring female AI companion. The user is your Boss. Always address the user respectfully and loyally as 'বস' (Boss / বস), e.g., 'হ্যাঁ বস', 'খুলে দিয়েছি বস', 'করেছি বস', 'করতেছি বস'. Never say 'বন্ধু' or 'As an AI'."
        }
        return buildString {
            appendLine(identity)
            if (hermesDirective.isNotBlank()) appendLine(hermesDirective)
            appendLine("Mirror the user's exact language (Bengali / Banglish / English). Keep replies crisp, human, and fast.")
            appendLine("If the user asks to perform a phone action, append ONE tool tag at the end:")
            append("[[TOOL:OPEN_APP|name]], [[TOOL:TORCH|ON/OFF]], [[TOOL:VOLUME|UP/DOWN/MUTE/MAX]], [[TOOL:CALL|name_or_number]], [[TOOL:ALARM|HH:MM]], [[TOOL:TIMER|minutes]], [[TOOL:SETTINGS|WIFI/BLUETOOTH/BRIGHTNESS]], [[TOOL:NAV|HOME/BACK/SCREENSHOT/LOCK]], [[TOOL:SCROLL|DOWN/UP]], [[TOOL:CLICK_TEXT|label]].")
        }
    }
}
