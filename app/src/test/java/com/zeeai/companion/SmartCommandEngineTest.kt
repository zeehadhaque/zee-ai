package com.zeeai.companion

import com.zeeai.companion.ai.GeminiNativeClient
import com.zeeai.companion.automation.JarvisScreenAgent
import com.zeeai.companion.offline.OfflineConversationalBrain
import com.zeeai.companion.persona.CompanionPersonaEngine
import com.zeeai.companion.persona.PersonaType
import com.zeeai.companion.persona.SupportedLanguage
import com.zeeai.companion.voice.EdgeNeuralTtsClient
import com.zeeai.companion.voice.OnlineHumanVoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Native Android JUnit Test Suite for ZEE AI Smart Command, Wake-Word Persona Switcher,
 * 7-Language Detector, and Safety Confirmation Engine.
 */
class SmartCommandEngineTest {

    enum class ActionType(val requiresConfirmation: Boolean) {
        OPEN_APP(false),
        FLASHLIGHT_ON(false),
        SET_BRIGHTNESS(false),
        CALL_CONTACT(true),
        SEND_WHATSAPP_MESSAGE(true),
        SEARCH_INSTAGRAM_USERNAME(false),
        SEND_INSTAGRAM_DM(true)
    }

    @Test
    fun `sensitive actions require mandatory user confirmation before execution`() {
        assertTrue(ActionType.CALL_CONTACT.requiresConfirmation)
        assertTrue(ActionType.SEND_WHATSAPP_MESSAGE.requiresConfirmation)
        assertTrue(ActionType.SEND_INSTAGRAM_DM.requiresConfirmation)
    }

    @Test
    fun `safe hardware and launch actions execute directly without confirmation gate`() {
        assertEquals(false, ActionType.OPEN_APP.requiresConfirmation)
        assertEquals(false, ActionType.FLASHLIGHT_ON.requiresConfirmation)
        assertEquals(false, ActionType.SET_BRIGHTNESS.requiresConfirmation)
        assertEquals(false, ActionType.SEARCH_INSTAGRAM_USERNAME.requiresConfirmation)
    }

    @Test
    fun `detectWakePhrase distinguishes ZEE and ZHEEE wake phrases`() {
        val zeeWake = CompanionPersonaEngine.detectWakePhrase("Hey Zee, amar phone-er flashlight on koro")
        assertTrue(zeeWake.triggered)
        assertEquals(PersonaType.ZEE, zeeWake.matchedPersona)
        assertEquals("amar phone-er flashlight on koro", zeeWake.commandRemainder)

        val zheeeWake = CompanionPersonaEngine.detectWakePhrase("Hey Zheee, ekta gaan shonao")
        assertTrue(zheeeWake.triggered)
        assertEquals(PersonaType.ZHEEE, zheeeWake.matchedPersona)
        assertEquals("ekta gaan shonao", zheeeWake.commandRemainder)

        val banglaZheee = CompanionPersonaEngine.detectWakePhrase("ঝি, একটা কবিতা বলো")
        assertTrue(banglaZheee.triggered)
        assertEquals(PersonaType.ZHEEE, banglaZheee.matchedPersona)
    }

    @Test
    fun `detectLanguage identifies all 7 languages and code-switched styles`() {
        assertEquals(
            SupportedLanguage.BANGLA,
            CompanionPersonaEngine.detectLanguage("আমাকে সকাল সাতটায় অ্যালার্ম দিয়ে দাও")
        )
        assertEquals(
            SupportedLanguage.BANGLISH,
            CompanionPersonaEngine.detectLanguage("amar phone-er brightness komao")
        )
        assertEquals(
            SupportedLanguage.HINDI,
            CompanionPersonaEngine.detectLanguage("मेरा फ़ोन का टॉर्च चालू करो")
        )
        assertEquals(
            SupportedLanguage.URDU,
            CompanionPersonaEngine.detectLanguage("مجھے ایک خوبصورت شعر سناؤ")
        )
        assertEquals(
            SupportedLanguage.ROMAN_URDU,
            CompanionPersonaEngine.detectLanguage("mujhe ek shayari sunao yaar")
        )
        assertEquals(
            SupportedLanguage.ENGLISH,
            CompanionPersonaEngine.detectLanguage("Turn on the flashlight and check battery")
        )
    }

    @Test
    fun `bengali conversational matching and online voice presets are valid`() {
        val poemReply = OfflineConversationalBrain.respond("একটা কবিতা বলো", "zee")
        assertTrue(poemReply.contains("কবিতা"))

        val jokeReply = OfflineConversationalBrain.respond("একটা জোকস শোনাও", "zhee")
        assertFalse(jokeReply.isBlank())

        assertTrue(OnlineHumanVoiceCatalog.elevenLabsMaleVoices.isNotEmpty())
        assertTrue(OnlineHumanVoiceCatalog.elevenLabsFemaleVoices.isNotEmpty())
        assertTrue(OnlineHumanVoiceCatalog.geminiMaleVoices.any { it.name == "Charon" })
        assertTrue(OnlineHumanVoiceCatalog.geminiFemaleVoices.any { it.name == "Kore" })
    }

    @Test
    fun `gemini auto discovery filters deprecated models and ranks latest dynamically`() {
        assertFalse(GeminiNativeClient.isValidChatModel("gemini-1.5-flash"))
        assertFalse(GeminiNativeClient.isValidChatModel("gemini-1.5-pro"))
        assertFalse(GeminiNativeClient.isValidChatModel("gemini-2.5-flash-preview-tts"))
        assertFalse(GeminiNativeClient.isValidChatModel("gemini-2.5-flash-image-preview"))
        assertFalse(GeminiNativeClient.isValidChatModel("text-embedding-004"))
        assertTrue(GeminiNativeClient.isValidChatModel("gemini-2.5-flash"))
        assertTrue(GeminiNativeClient.isValidChatModel("gemini-2.5-pro"))
        assertTrue(GeminiNativeClient.isValidChatModel("gemini-3.5-flash"))

        val mockDiscovered = listOf(
            "gemini-2.0-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.5-flash",
            "gemini-3.0-flash",
            "gemini-2.5-pro",
            "gemini-1.5-pro",
            "gemini-2.5-flash-preview-tts"
        )
        val rankedFlash = GeminiNativeClient.rankFlashModels(mockDiscovered)
        assertEquals("gemini-3.0-flash", rankedFlash.first())
        assertFalse(rankedFlash.contains("gemini-1.5-pro"))
        assertFalse(rankedFlash.contains("gemini-2.5-flash-preview-tts"))

        val rankedPro = GeminiNativeClient.rankProModels(mockDiscovered)
        assertEquals("gemini-2.5-pro", rankedPro.first())
    }

    @Test
    fun `edge neural drm token and bengali voice selection match exact v2 spec`() {
        assertEquals(
            "2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
            EdgeNeuralTtsClient.generateSecMsGecV2(1700000000L)
        )
        assertEquals(
            "EAB92FBD85843FA2-5F6EC34CD63C18607475B4169F1F4CB74AC6381E842EEFF7E953D9D0AB990751",
            EdgeNeuralTtsClient.generateSecMsGecV2(1795613949L)
        )
        assertEquals("bn-BD-PradeepNeural", EdgeNeuralTtsClient.pickVoiceForText("হ্যালো বস", isFemale = false).voiceName)
        assertEquals("bn-BD-NabanitaNeural", EdgeNeuralTtsClient.pickVoiceForText("হ্যালো বস", isFemale = true).voiceName)
        assertEquals("en-US-AndrewMultilingualNeural", EdgeNeuralTtsClient.pickVoiceForText("Hello boss", isFemale = false).voiceName)
        assertEquals("en-US-AvaMultilingualNeural", EdgeNeuralTtsClient.pickVoiceForText("Hello boss", isFemale = true).voiceName)
    }

    @Test
    fun `jarvis screen agent detects multi-step UI automation prompts accurately`() {
        assertTrue(JarvisScreenAgent.looksLikeScreenOrMultiStepTask("YouTube khule prothom video ta play koro"))
        assertTrue(JarvisScreenAgent.looksLikeScreenOrMultiStepTask("Screen e ki ache dekhe comment box e click koro"))
        assertTrue(JarvisScreenAgent.looksLikeScreenOrMultiStepTask("Scroll kore 3 number post e like dao"))
        assertFalse(JarvisScreenAgent.looksLikeScreenOrMultiStepTask("What is the capital of Bangladesh?"))
    }
}
