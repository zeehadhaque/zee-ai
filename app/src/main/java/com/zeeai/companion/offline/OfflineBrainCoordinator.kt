package com.zeeai.companion.offline

import com.zeeai.companion.automation.OfflineAutonomousPhoneController

class OfflineBrainCoordinator(
    private val llmEngine: LocalLlmInferenceEngine,
    private val memoryVault: PersonalOfflineMemoryVault,
    private val phoneController: OfflineAutonomousPhoneController
) {
    suspend fun processOfflineTurn(
        userMessage: String,
        persona: BestFriendPersona,
        recentHistory: List<OfflineChatTurn>,
        installedModelPath: String?,
        onStreamingUpdate: (String) -> Unit
    ): String {
        return try {
            // ১. ইউজার ব্যক্তিগত কোনো কথা শেয়ার করলে অফলাইনে সেভ করে রাখো
            memoryVault.autoExtractFromMessage(userMessage)

            // ২. সরাসরি ফোন কন্ট্রোল কমান্ড হলে <50ms-এ এক্সিকিউট করো (LLM ছাড়াই)
            val instant = phoneController.tryInstantOfflineCommand(userMessage)
            if (instant.handled) {
                onStreamingUpdate(instant.reply)
                return instant.reply
            }

            // ৩. অফলাইন মডেল ডাউনলোড করা না থাকলে ভদ্রভাবে জানিয়ে দাও (কখনো ক্র্যাশ নয়)
            if (installedModelPath.isNullOrBlank()) {
                val guide = "নেট বন্ধ আছে বস! অফলাইনে গল্প, কবিতা, শায়েরি আর যেকোনো সমস্যার সমাধান করতে Models ট্যাব থেকে 'Gemma 3 1B Instruct (.task)' বা 'Gemma 3n E2B (.task)' নামিয়ে নাও।\n\n(তবে ফোন কন্ট্রোল — যেমন 'torch on koro', 'YouTube kholo', 'volume barao', 'koyta baje' — এখনই অফলাইনে কাজ করবে বস!)"
                onStreamingUpdate(guide)
                return guide
            }

            // ৪. হিউম্যান বেস্ট-ফ্রেন্ড প্রম্পট তৈরি করে অফলাইন LLM স্ট্রিম করো (<130 টোকেন ফাস্ট প্রম্পট)
            onStreamingUpdate("ভাবছি বস...")
            val prompt = OfflineBestFriendPromptBuilder.buildFastGemmaPrompt(
                persona = persona,
                userMessage = userMessage,
                history = recentHistory,
                memoryBlock = memoryVault.buildPromptMemoryBlock(userMessage)
            )

            val genResult = llmEngine.generateStreaming(
                modelPath = installedModelPath,
                prompt = prompt,
                temperature = 0.85f,
                onPartialResult = { partial ->
                    val safeDisplay = partial.substringBefore("[[TOOL:").trim()
                    if (safeDisplay.isNotEmpty()) onStreamingUpdate(safeDisplay)
                }
            )

            genResult.fold(
                onSuccess = { raw ->
                    val (cleanReply, toolLogs) = phoneController.executeToolsInAiResponse(raw)
                    val combined = buildString {
                        if (cleanReply.isNotBlank()) append(cleanReply)
                        if (toolLogs.isNotEmpty()) {
                            if (isNotEmpty()) append("\n\n")
                            append("⚡ ").append(toolLogs.joinToString(" • "))
                        }
                    }.ifBlank { "আমি আছি বস, আবার বলুন কী নিয়ে কথা বলবেন?" }

                    onStreamingUpdate(combined)
                    combined
                },
                onFailure = { err ->
                    val notice = "⚠️ [On-Device Model Notice]: ${err.message ?: "অফলাইন মডেল লোড করা যায়নি।"}\n\nটিপস: Models ট্যাব থেকে হালকা 'Gemma 3 1B Instruct (.task)' (~529 MB) ডাউনলোড করলে ১২GB ফোনে ১-২ সেকেন্ডেই মাখনের মতো চলবে!"
                    onStreamingUpdate(notice)
                    notice
                }
            )
        } catch (t: Throwable) {
            val safe = "⚠️ ছোট্ট একটা অফলাইন এরর হয়েছিল (${t.localizedMessage ?: "Unknown"}), কিন্তু অ্যাপ ক্র্যাশ হতে দিইনি। আবার বলুন বস!"
            onStreamingUpdate(safe)
            safe
        }
    }
}
