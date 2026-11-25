package com.zeeai.companion.voice

import com.zeeai.companion.data.local.SecureKeyVault

data class ElevenLabsVoicePreset(
    val id: String,
    val name: String,
    val tag: String,
    val isFemale: Boolean
)

data class GeminiStudioVoicePreset(
    val name: String,
    val subtitle: String,
    val isFemale: Boolean
)

data class OnlineVoiceConfig(
    val elevenLabsMaleId: String = OnlineHumanVoiceCatalog.DEFAULT_ELEVEN_MALE_ID,
    val elevenLabsFemaleId: String = OnlineHumanVoiceCatalog.DEFAULT_ELEVEN_FEMALE_ID,
    val geminiMaleVoice: String = OnlineHumanVoiceCatalog.DEFAULT_GEMINI_MALE_VOICE,
    val geminiFemaleVoice: String = OnlineHumanVoiceCatalog.DEFAULT_GEMINI_FEMALE_VOICE,
    val preferGeminiOverCloudFree: Boolean = true
)

/**
 * Curated 100% Online Human Voice Presets for ZEE (Male) & ZHEE (Female).
 * Offline robotic TTS and Sherpa-ONNX engines have been permanently removed.
 */
object OnlineHumanVoiceCatalog {

    const val KEY_ELEVEN_MALE_ID = "elevenlabs_male_voice_id"
    const val KEY_ELEVEN_FEMALE_ID = "elevenlabs_female_voice_id"
    const val KEY_GEMINI_MALE_VOICE = "gemini_tts_male_voice"
    const val KEY_GEMINI_FEMALE_VOICE = "gemini_tts_female_voice"

    const val DEFAULT_ELEVEN_MALE_ID = "pNInz6obpgDQGcFmaJgB"     // Adam
    const val DEFAULT_ELEVEN_FEMALE_ID = "EXAVITQu4vr4xnSDxMaL"   // Sarah / Bella
    const val DEFAULT_GEMINI_MALE_VOICE = "Charon"
    const val DEFAULT_GEMINI_FEMALE_VOICE = "Kore"

    val elevenLabsMaleVoices = listOf(
        ElevenLabsVoicePreset("pNInz6obpgDQGcFmaJgB", "Adam", "Deep Warm Boss Companion", false),
        ElevenLabsVoicePreset("onwK4e9ZLuTAKqWW03F9", "Daniel", "Crisp JARVIS Executive", false),
        ElevenLabsVoicePreset("nPczCjzI2devNBz1zQrb", "Brian", "Deep Calm Narrator", false),
        ElevenLabsVoicePreset("ErXwobaYiN019PkySvjV", "Antoni", "Friendly Natural Male", false),
        ElevenLabsVoicePreset("TxGEqnHWrfWFTfGW9XjX", "Josh", "Energetic Conversational", false),
        ElevenLabsVoicePreset("TX3LPaxmHKxFdv7VOQHJ", "Liam", "Articulate Studio Male", false)
    )

    val elevenLabsFemaleVoices = listOf(
        ElevenLabsVoicePreset("EXAVITQu4vr4xnSDxMaL", "Sarah", "Warm Expressive Female", true),
        ElevenLabsVoicePreset("21m00Tcm4TlvDq8ikWAM", "Rachel", "Calm Crystal-Clear", true),
        ElevenLabsVoicePreset("9BWtsMINqrJLrRacOk9x", "Aria", "Vibrant Natural Human", true),
        ElevenLabsVoicePreset("XB0fDUnXU5powFXDhCwa", "Charlotte", "Refined & Friendly", true)
    )

    val geminiMaleVoices = listOf(
        GeminiStudioVoicePreset("Charon", "Deep JARVIS Tone", false),
        GeminiStudioVoicePreset("Fenrir", "Warm & Strong", false),
        GeminiStudioVoicePreset("Orus", "Sharp & Confident", false),
        GeminiStudioVoicePreset("Puck", "Upbeat & Quick", false)
    )

    val geminiFemaleVoices = listOf(
        GeminiStudioVoicePreset("Kore", "Warm & Natural", true),
        GeminiStudioVoicePreset("Aoede", "Breezy & Expressive", true),
        GeminiStudioVoicePreset("Zephyr", "Bright & Cheerful", true),
        GeminiStudioVoicePreset("Leda", "Soft & Caring", true)
    )

    fun loadConfig(vault: SecureKeyVault): OnlineVoiceConfig {
        return OnlineVoiceConfig(
            elevenLabsMaleId = vault.getKey(KEY_ELEVEN_MALE_ID).ifBlank { DEFAULT_ELEVEN_MALE_ID },
            elevenLabsFemaleId = vault.getKey(KEY_ELEVEN_FEMALE_ID).ifBlank { DEFAULT_ELEVEN_FEMALE_ID },
            geminiMaleVoice = vault.getKey(KEY_GEMINI_MALE_VOICE).ifBlank { DEFAULT_GEMINI_MALE_VOICE },
            geminiFemaleVoice = vault.getKey(KEY_GEMINI_FEMALE_VOICE).ifBlank { DEFAULT_GEMINI_FEMALE_VOICE }
        )
    }

    fun getVoiceDisplayName(voiceId: String, isFemale: Boolean): String {
        val pool = if (isFemale) elevenLabsFemaleVoices else elevenLabsMaleVoices
        return pool.firstOrNull { it.id == voiceId }?.name ?: "Custom (${voiceId.take(8)})"
    }
}
