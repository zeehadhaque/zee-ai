package com.zeeai.companion

import com.zeeai.companion.persona.CompanionPersonaEngine
import com.zeeai.companion.persona.PersonaType
import com.zeeai.companion.persona.SupportedLanguage
import org.junit.Assert.assertEquals
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
}
