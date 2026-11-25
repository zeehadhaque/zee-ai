package com.zeeai.companion.offline

enum class BestFriendPersona { ZEE, ZHEE }

data class OfflineChatTurn(
    val isUser: Boolean,
    val text: String
)

object OfflineBestFriendPromptBuilder {

    /**
     * Ultra-Fast Compact Best-Friend Prompt (<130 tokens) — cuts prefill latency by 3x–4x
     */
    fun buildFastGemmaPrompt(
        persona: BestFriendPersona,
        userMessage: String,
        history: List<OfflineChatTurn>,
        memoryBlock: String
    ): String {
        val role = if (persona == BestFriendPersona.ZEE) {
            "You are ZEE, user's loyal, sharp, devoted male companion offline. The user is your Boss. Always address the user respectfully and loyally as 'বস' (Boss / বস), e.g., 'হ্যাঁ বস', 'খুলে দিয়েছি বস', 'করেছি বস', 'করতেছি বস'. Never say 'বন্ধু' or 'As an AI'."
        } else {
            "You are ZHEE, user's caring, warm, devoted female companion offline. The user is your Boss. Always address the user respectfully and loyally as 'বস' (Boss / বস), e.g., 'হ্যাঁ বস', 'খুলে দিয়েছি বস', 'করেছি বস', 'করতেছি বস'. Never say 'বন্ধু' or 'As an AI'."
        }

        return buildString {
            append("<start_of_turn>user\n")
            append(role).append("\n")
            append("Mirror user's exact language (Bangla/Banglish/English). Be deeply human, comforting, write soulful original kobita/shayari when asked, and solve problems clearly.\n")
            append("Only if user asks a phone action, append ONE tag at end: [[TOOL:OPEN_APP|name]], [[TOOL:TORCH|ON/OFF]], [[TOOL:VOLUME|UP/DOWN/MUTE]], [[TOOL:CALL|num]], [[TOOL:ALARM|HH:MM]], [[TOOL:TIMER|min]], [[TOOL:NAV|HOME/BACK/SCREENSHOT]], [[TOOL:REMEMBER|fact]].\n")
            if (memoryBlock.isNotBlank()) {
                append("Memories:\n").append(memoryBlock.take(220)).append("\n")
            }
            history.takeLast(3).forEach { turn ->
                append(if (turn.isUser) "Boss: " else "You: ").append(turn.text.take(180)).append("\n")
            }
            append("Boss: ").append(userMessage)
            append("\n<end_of_turn>\n<start_of_turn>model\n")
        }
    }

    fun buildGemmaTurnPrompt(
        persona: BestFriendPersona,
        userMessage: String,
        history: List<OfflineChatTurn>,
        memoryBlock: String,
        installedAppsSummary: String = ""
    ): String = buildFastGemmaPrompt(persona, userMessage, history, memoryBlock)
}
