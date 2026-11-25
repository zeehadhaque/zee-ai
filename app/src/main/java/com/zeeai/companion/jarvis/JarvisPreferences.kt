package com.zeeai.companion.jarvis

import android.content.Context
import com.zeeai.companion.ui.components.ZeeAppMode
import com.zeeai.companion.ui.theme.LuxuryAccentPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class HudVisualStyle { ARC_REACTOR, HOLOGRAM_SUIT }
enum class PersonalityArchetype(val label: String, val desc: String) {
    BOSS_LOYAL(
        "Boss Loyal",
        "Elite AI companion — calm authority, loyal & crisp, always addresses you respectfully as 'বস' (Boss)."
    ),
    COMPANION(
        "Friend",
        "Warm, caring & empathetic personal confidant who addresses you as 'বস' with heartfelt attention."
    ),
    CYBERPUNK(
        "Cyber",
        "Tech-forward, razor-sharp wit, cybernetic precision and direct hacker cadence for Boss."
    ),
    EXECUTIVE(
        "Exec",
        "Chief of Staff — crisp corporate brevity, zero fluff, high-density intelligence for Boss."
    )
}
enum class FormalityLevel(val label: String) { CASUAL("Casual"), BALANCED("Balanced"), FORMAL("Formal") }
enum class HumorLevel(val label: String) { NONE("None"), SUBTLE("Subtle"), HIGH("High Wit") }

data class ProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val defaultModel: String
)

data class JarvisSettingsSnapshot(
    val appMode: ZeeAppMode = ZeeAppMode.HUD,
    val hudStyle: HudVisualStyle = HudVisualStyle.ARC_REACTOR,
    val luxuryAccent: LuxuryAccentPreset = LuxuryAccentPreset.OBSIDIAN_CYAN,
    val alwaysOnListening: Boolean = false,
    val useNeuralVoice: Boolean = true,
    val workflowsEnabled: Boolean = true,
    val macroRecorderEnabled: Boolean = true,
    val jarvisEyeEnabled: Boolean = true,
    val smartHomeEnabled: Boolean = false,
    val homeAssistantUrl: String = "http://homeassistant.local:8123",
    val homeAssistantToken: String = "",
    val smartHomeDefaultEntity: String = "light.living_room",
    val proactiveCommuteEnabled: Boolean = true,
    val commuteHomeAddress: String = "",
    val commuteWorkAddress: String = "",
    val proactiveAutoSpeak: Boolean = true,
    val proactiveVoiceConfirm: Boolean = true,
    val proactiveNightPrompt: Boolean = true,
    val proactiveStudyPrompt: Boolean = true,
    val proactiveMeetingReminders: Boolean = true,
    val proactiveTaskPrompt: Boolean = true,
    val proactiveMorningPrompt: Boolean = true,
    val proactiveBatteryAlert: Boolean = true,
    val localFirstPrivacy: Boolean = false,
    val confirmRiskyActions: Boolean = true,
    val skillsEnabled: Boolean = true,
    val voiceFeedback: Boolean = true,
    val maxSteps: Int = 15,
    val stepDelayMs: Int = 1200,
    val archetype: PersonalityArchetype = PersonalityArchetype.BOSS_LOYAL,
    val formality: FormalityLevel = FormalityLevel.BALANCED,
    val humor: HumorLevel = HumorLevel.SUBTLE,
    val emotionalAwareness: Boolean = true,
    val aiProviderPreset: String = "gemini",
    val aiBaseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    val aiCustomModel: String = "gemini-2.0-flash",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val useSystemPrompt: Boolean = true,
    val onboardingComplete: Boolean = false,
    val overlayBubbleActive: Boolean = false
)

class JarvisPreferences(context: Context) {
    companion object {
        val PROVIDER_PRESETS = listOf(
            ProviderPreset("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.0-flash"),
            ProviderPreset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
            ProviderPreset("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
            ProviderPreset("groq", "Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
            ProviderPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
            ProviderPreset("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1", "meta/llama-3.3-70b-instruct"),
            ProviderPreset("ollama", "Ollama (Local)", "http://127.0.0.1:11434/v1", "llama3.2"),
            ProviderPreset("custom", "Custom OpenAI", "", "")
        )
    }

    private val prefs = context.applicationContext.getSharedPreferences("zee_jarvis_prefs_v1", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<JarvisSettingsSnapshot> = _state.asStateFlow()

    private fun load(): JarvisSettingsSnapshot {
        val mode = runCatching { ZeeAppMode.valueOf(prefs.getString("app_mode", ZeeAppMode.HUD.name)!!) }
            .getOrDefault(ZeeAppMode.HUD)
        val hud = runCatching { HudVisualStyle.valueOf(prefs.getString("hud_style", HudVisualStyle.ARC_REACTOR.name)!!) }
            .getOrDefault(HudVisualStyle.ARC_REACTOR)
        val accent = runCatching { LuxuryAccentPreset.valueOf(prefs.getString("luxury_accent", LuxuryAccentPreset.OBSIDIAN_CYAN.name)!!) }
            .getOrDefault(LuxuryAccentPreset.OBSIDIAN_CYAN)
        val arch = runCatching { PersonalityArchetype.valueOf(prefs.getString("archetype", PersonalityArchetype.BOSS_LOYAL.name)!!) }
            .getOrDefault(PersonalityArchetype.BOSS_LOYAL)
        val form = runCatching { FormalityLevel.valueOf(prefs.getString("formality", FormalityLevel.BALANCED.name)!!) }
            .getOrDefault(FormalityLevel.BALANCED)
        val hum = runCatching { HumorLevel.valueOf(prefs.getString("humor", HumorLevel.SUBTLE.name)!!) }
            .getOrDefault(HumorLevel.SUBTLE)

        return JarvisSettingsSnapshot(
            appMode = mode,
            hudStyle = hud,
            luxuryAccent = accent,
            alwaysOnListening = prefs.getBoolean("always_on", false),
            useNeuralVoice = prefs.getBoolean("neural_voice", true),
            workflowsEnabled = prefs.getBoolean("workflows_enabled", true),
            macroRecorderEnabled = prefs.getBoolean("macro_recorder_enabled", true),
            jarvisEyeEnabled = prefs.getBoolean("jarvis_eye_enabled", true),
            smartHomeEnabled = prefs.getBoolean("smart_home_enabled", false),
            homeAssistantUrl = prefs.getString("ha_url", "http://homeassistant.local:8123").orEmpty(),
            homeAssistantToken = prefs.getString("ha_token", "").orEmpty(),
            smartHomeDefaultEntity = prefs.getString("ha_entity", "light.living_room").orEmpty(),
            proactiveCommuteEnabled = prefs.getBoolean("proactive_commute", true),
            commuteHomeAddress = prefs.getString("commute_home", "").orEmpty(),
            commuteWorkAddress = prefs.getString("commute_work", "").orEmpty(),
            proactiveAutoSpeak = prefs.getBoolean("proactive_auto_speak", true),
            proactiveVoiceConfirm = prefs.getBoolean("proactive_voice_confirm", true),
            proactiveNightPrompt = prefs.getBoolean("proactive_night", true),
            proactiveStudyPrompt = prefs.getBoolean("proactive_study", true),
            proactiveMeetingReminders = prefs.getBoolean("proactive_meeting", true),
            proactiveTaskPrompt = prefs.getBoolean("proactive_tasks", true),
            proactiveMorningPrompt = prefs.getBoolean("proactive_morning", true),
            proactiveBatteryAlert = prefs.getBoolean("proactive_battery", true),
            localFirstPrivacy = prefs.getBoolean("local_first_privacy", false),
            confirmRiskyActions = prefs.getBoolean("confirm_risky", true),
            skillsEnabled = prefs.getBoolean("skills_enabled", true),
            voiceFeedback = prefs.getBoolean("voice_feedback", true),
            maxSteps = prefs.getInt("max_steps", 15).coerceIn(3, 40),
            stepDelayMs = prefs.getInt("step_delay_ms", 1200).coerceIn(200, 5000),
            archetype = arch,
            formality = form,
            humor = hum,
            emotionalAwareness = prefs.getBoolean("emotional_awareness", true),
            aiProviderPreset = prefs.getString("ai_preset", "gemini").orEmpty(),
            aiBaseUrl = prefs.getString("ai_base_url", "https://generativelanguage.googleapis.com/v1beta").orEmpty(),
            aiCustomModel = prefs.getString("ai_custom_model", "gemini-2.0-flash").orEmpty(),
            temperature = prefs.getFloat("temperature", 0.7f),
            maxTokens = prefs.getInt("max_tokens", 2048).coerceIn(256, 8192),
            useSystemPrompt = prefs.getBoolean("use_system_prompt", true),
            onboardingComplete = prefs.getBoolean("onboarding_complete", false),
            overlayBubbleActive = prefs.getBoolean("overlay_bubble_active", false)
        )
    }

    fun update(transform: (JarvisSettingsSnapshot) -> JarvisSettingsSnapshot) {
        val next = transform(_state.value)
        _state.value = next
        prefs.edit().apply {
            putString("app_mode", next.appMode.name)
            putString("hud_style", next.hudStyle.name)
            putString("luxury_accent", next.luxuryAccent.name)
            putBoolean("always_on", next.alwaysOnListening)
            putBoolean("neural_voice", next.useNeuralVoice)
            putBoolean("workflows_enabled", next.workflowsEnabled)
            putBoolean("macro_recorder_enabled", next.macroRecorderEnabled)
            putBoolean("jarvis_eye_enabled", next.jarvisEyeEnabled)
            putBoolean("smart_home_enabled", next.smartHomeEnabled)
            putString("ha_url", next.homeAssistantUrl)
            putString("ha_token", next.homeAssistantToken)
            putString("ha_entity", next.smartHomeDefaultEntity)
            putBoolean("proactive_commute", next.proactiveCommuteEnabled)
            putString("commute_home", next.commuteHomeAddress)
            putString("commute_work", next.commuteWorkAddress)
            putBoolean("proactive_auto_speak", next.proactiveAutoSpeak)
            putBoolean("proactive_voice_confirm", next.proactiveVoiceConfirm)
            putBoolean("proactive_night", next.proactiveNightPrompt)
            putBoolean("proactive_study", next.proactiveStudyPrompt)
            putBoolean("proactive_meeting", next.proactiveMeetingReminders)
            putBoolean("proactive_tasks", next.proactiveTaskPrompt)
            putBoolean("proactive_morning", next.proactiveMorningPrompt)
            putBoolean("proactive_battery", next.proactiveBatteryAlert)
            putBoolean("local_first_privacy", next.localFirstPrivacy)
            putBoolean("confirm_risky", next.confirmRiskyActions)
            putBoolean("skills_enabled", next.skillsEnabled)
            putBoolean("voice_feedback", next.voiceFeedback)
            putInt("max_steps", next.maxSteps)
            putInt("step_delay_ms", next.stepDelayMs)
            putString("archetype", next.archetype.name)
            putString("formality", next.formality.name)
            putString("humor", next.humor.name)
            putBoolean("emotional_awareness", next.emotionalAwareness)
            putString("ai_preset", next.aiProviderPreset)
            putString("ai_base_url", next.aiBaseUrl)
            putString("ai_custom_model", next.aiCustomModel)
            putFloat("temperature", next.temperature)
            putInt("max_tokens", next.maxTokens)
            putBoolean("use_system_prompt", next.useSystemPrompt)
            putBoolean("onboarding_complete", next.onboardingComplete)
            putBoolean("overlay_bubble_active", next.overlayBubbleActive)
        }.apply()
    }

    fun selectProviderPreset(presetId: String) {
        val preset = PROVIDER_PRESETS.firstOrNull { it.id == presetId } ?: return
        update { cur ->
            cur.copy(
                aiProviderPreset = preset.id,
                aiBaseUrl = if (preset.baseUrl.isNotBlank()) preset.baseUrl else cur.aiBaseUrl,
                aiCustomModel = if (preset.defaultModel.isNotBlank()) preset.defaultModel else cur.aiCustomModel
            )
        }
    }
}
