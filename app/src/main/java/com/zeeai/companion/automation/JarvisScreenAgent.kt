package com.zeeai.companion.automation

import android.content.Context
import android.util.Log
import com.zeeai.companion.ai.GeminiNativeClient
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Multi-step screen-aware Iron-Man JARVIS Autonomous Agent Loop.
 * Observes the foreground app's interactive Accessibility nodes, decides the next action via Gemini
 * JSON planning, performs taps/typing/scrolling/search/navigation, and verifies screen updates.
 */
class JarvisScreenAgent(
    private val context: Context,
    private val geminiClient: GeminiNativeClient,
    private val nativeCommander: NativePhoneCommander
) {
    companion object {
        private const val TAG = "JarvisScreenAgent"
        private const val MAX_STEPS = 6

        fun looksLikeScreenOrMultiStepTask(raw: String): Boolean {
            val l = raw.lowercase()
            return l.contains(" and ") ||
                l.contains(" kore ") ||
                l.contains(" korbe") ||
                l.contains(" tarpor") ||
                l.contains(" then ") ||
                l.contains(" screen") ||
                l.contains("স্ক্রিন") ||
                l.contains("comment") ||
                l.contains("কমেন্ট") ||
                l.contains("like") ||
                l.contains("লাইক") ||
                l.contains("subscribe") ||
                l.contains("dark mode") ||
                l.contains("hotspot") ||
                l.contains("first") ||
                l.contains("second") ||
                l.contains("prothom") ||
                l.contains("প্রথম")
        }
    }

    suspend fun executeGoal(
        goal: String,
        apiKey: String,
        onStepProgress: (String) -> Unit
    ): Result<String> {
        val cleanGoal = goal.trim()
        if (cleanGoal.isEmpty()) return Result.failure(IllegalArgumentException("Empty goal"))
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("Google AI Studio API Key প্রয়োজন বস।"))
        }

        val stepLog = mutableListOf<String>()

        for (step in 1..MAX_STEPS) {
            val screenMap = ZeeAccessibilityService.captureScreenMap()
            val screenDesc = screenMap?.formattedForAi
                ?: "ACCESSIBILITY_OFF (Can only launch apps / URLs via Intents unless ZEE AI Accessibility is enabled)"

            onStepProgress("⚡ Step $step: Analyzing screen...")

            val sysPrompt = """
                You are ZEE (JARVIS), an autonomous Android phone control agent. The user is your Boss (address as 'বস' in Bengali script for spoken replies).
                Look at the Boss's GOAL, PREVIOUS_STEPS, and CURRENT_SCREEN interactive nodes.
                Output ONE valid JSON object for the SINGLE next action:
                {
                  "action": "OPEN_APP|YT_SEARCH|GOOGLE_SEARCH|CLICK_NODE|TYPE_NODE|CLICK_TEXT|TYPE_TEXT|SCROLL|NAV|SKIP_AD|WAIT|DONE",
                  "nodeIndex": -1,
                  "targetLabel": "",
                  "text": "",
                  "direction": "DOWN",
                  "submitAfterType": true,
                  "replyBn": "কাজ শেষ হলে সংক্ষিপ্ত ও ন্যাচারাল বাংলায় (যেমন: 'কাজটি করে দিয়েছি বস') বলো"
                }
                Rules:
                - Prefer OPEN_APP / YT_SEARCH / GOOGLE_SEARCH if the target app isn't open yet (`FOREGROUND_APP`).
                - When the target app is open, inspect INTERACTIVE_NODES indices and pick exact `CLICK_NODE` or `TYPE_NODE`.
                - When sending a chat/comment/search, set `submitAfterType=true`.
                - Once the goal is fulfilled or you read/answered a screen query, set `action="DONE"` with crisp Bengali `replyBn`.
            """.trimIndent()

            val userPrompt = buildString {
                appendLine("GOAL: $cleanGoal")
                appendLine("PREVIOUS_STEPS: ${if (stepLog.isEmpty()) "None" else stepLog.joinToString(" -> ")}")
                appendLine("CURRENT_SCREEN:\n$screenDesc")
            }

            val jsonRes = geminiClient.generateJson(apiKey, sysPrompt, userPrompt)
            val rawJson = jsonRes.getOrElse { return Result.failure(it) }
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            val obj = runCatching { JSONObject(rawJson) }.getOrNull()
                ?: return Result.failure(IllegalStateException("Could not parse agent plan"))

            val action = obj.optString("action", "DONE").uppercase()
            val nodeIndex = obj.optInt("nodeIndex", -1)
            val targetLabel = obj.optString("targetLabel", "").trim()
            val text = obj.optString("text", "").trim()
            val direction = obj.optString("direction", "DOWN").trim().uppercase()
            val submitAfterType = obj.optBoolean("submitAfterType", true)
            val replyBn = obj.optString("replyBn", "").trim()

            Log.i(TAG, "Step $step action=$action node=$nodeIndex target=$targetLabel text=$text")

            when (action) {
                "DONE" -> {
                    val finalReply = replyBn.ifBlank { "কাজটি সম্পন্ন করেছি বস।" }
                    onStepProgress(finalReply)
                    return Result.success(finalReply)
                }
                "OPEN_APP" -> {
                    val appTarget = targetLabel.ifBlank { text }
                    val res = nativeCommander.tryHandleCommand("open $appTarget")
                    stepLog.add("OPEN_APP($appTarget):${res is PhoneCommandOutcome.Executed}")
                    delay(1100L)
                }
                "YT_SEARCH" -> {
                    val q = text.ifBlank { targetLabel }
                    nativeCommander.tryHandleCommand("youtube search $q")
                    stepLog.add("YT_SEARCH($q)")
                    delay(1400L)
                }
                "GOOGLE_SEARCH" -> {
                    val q = text.ifBlank { targetLabel }
                    nativeCommander.tryHandleCommand("google $q")
                    stepLog.add("GOOGLE_SEARCH($q)")
                    delay(1200L)
                }
                "CLICK_NODE" -> {
                    var ok = if (nodeIndex >= 0) ZeeAccessibilityService.performClickNodeIndex(nodeIndex) else false
                    if (!ok && targetLabel.isNotBlank()) {
                        ok = ZeeAccessibilityService.getInstance()?.clickNodeContainingText(targetLabel) ?: false
                    }
                    stepLog.add("CLICK($nodeIndex/$targetLabel)=$ok")
                    delay(950L)
                }
                "TYPE_NODE" -> {
                    var ok = if (nodeIndex >= 0) ZeeAccessibilityService.performTypeNodeIndex(nodeIndex, text, submitAfterType) else false
                    if (!ok) {
                        val svc = ZeeAccessibilityService.getInstance()
                        ok = if (submitAfterType) svc?.autoTypeAndSend(text) ?: false
                        else svc?.typeTextIntoFocusedField(text) ?: false
                    }
                    stepLog.add("TYPE($nodeIndex)=$ok")
                    delay(1000L)
                }
                "CLICK_TEXT" -> {
                    val label = targetLabel.ifBlank { text }
                    val ok = ZeeAccessibilityService.getInstance()?.clickNodeContainingText(label) ?: false
                    stepLog.add("CLICK_TEXT($label)=$ok")
                    delay(900L)
                }
                "TYPE_TEXT" -> {
                    val svc = ZeeAccessibilityService.getInstance()
                    val ok = if (submitAfterType) svc?.autoTypeAndSend(text) ?: false
                    else svc?.typeTextIntoFocusedField(text) ?: false
                    stepLog.add("TYPE_TEXT=$ok")
                    delay(900L)
                }
                "SCROLL" -> {
                    ZeeAccessibilityService.getInstance()?.swipeDirection(direction)
                    stepLog.add("SCROLL($direction)")
                    delay(850L)
                }
                "NAV" -> {
                    val navArg = direction.ifBlank { targetLabel }.ifBlank { "BACK" }
                    ZeeAccessibilityService.getInstance()?.handleGlobalNavigation(navArg)
                    stepLog.add("NAV($navArg)")
                    delay(900L)
                }
                "SKIP_AD" -> {
                    ZeeAccessibilityService.getInstance()?.skipAdIfPresent()
                    stepLog.add("SKIP_AD")
                    delay(700L)
                }
                "WAIT" -> {
                    stepLog.add("WAIT")
                    delay(1100L)
                }
                else -> {
                    return Result.success(replyBn.ifBlank { "কাজটি সম্পন্ন করেছি বস।" })
                }
            }
        }

        return Result.success("সব ধাপ চালিয়ে দিয়েছি বস।")
    }
}
