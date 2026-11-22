package com.zeeai.companion.persona

enum class PersonaType {
    ZEE,
    ZHEEE
}

enum class SupportedLanguage {
    BANGLA,
    BANGLISH,
    HINDI,
    URDU,
    ROMAN_URDU,
    ENGLISH,
    OTHER
}

data class WakePhraseMatch(
    val triggered: Boolean,
    val matchedPersona: PersonaType? = null,
    val commandRemainder: String = ""
)

object CompanionPersonaEngine {

    fun detectWakePhrase(input: String): WakePhraseMatch {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return WakePhraseMatch(triggered = false)

        // Match ZHEEE / ঝী first (longer / more specific match)
        val zheeePatternEn = Regex("""^(?:hey|hi|hello|ok)?\s*(?:zheee|zhee|zhe)\s*[,:\-]?\s*(.*)$""", RegexOption.IGNORE_CASE)
        val zheeeMatchEn = zheeePatternEn.find(trimmed)
        if (zheeeMatchEn != null) {
            val remainder = zheeeMatchEn.groupValues.getOrNull(1)?.trim().orEmpty()
            return WakePhraseMatch(
                triggered = true,
                matchedPersona = PersonaType.ZHEEE,
                commandRemainder = remainder
            )
        }

        val zheeePatternBn = Regex("""^(?:এই|হে|হ্যালো)?\s*(?:ঝি|ঝী)\s*[,:\-]?\s*(.*)$""")
        val zheeeMatchBn = zheeePatternBn.find(trimmed)
        if (zheeeMatchBn != null) {
            val remainder = zheeeMatchBn.groupValues.getOrNull(1)?.trim().orEmpty()
            return WakePhraseMatch(
                triggered = true,
                matchedPersona = PersonaType.ZHEEE,
                commandRemainder = remainder
            )
        }

        // Match ZEE / জী
        val zeePatternEn = Regex("""^(?:hey|hi|hello|ok)?\s*(?:zee|ze)\s*[,:\-]?\s*(.*)$""", RegexOption.IGNORE_CASE)
        val zeeMatchEn = zeePatternEn.find(trimmed)
        if (zeeMatchEn != null) {
            val remainder = zeeMatchEn.groupValues.getOrNull(1)?.trim().orEmpty()
            return WakePhraseMatch(
                triggered = true,
                matchedPersona = PersonaType.ZEE,
                commandRemainder = remainder
            )
        }

        val zeePatternBn = Regex("""^(?:এই|হে|হ্যালো)?\s*(?:জি|জী)\s*[,:\-]?\s*(.*)$""")
        val zeeMatchBn = zeePatternBn.find(trimmed)
        if (zeeMatchBn != null) {
            val remainder = zeeMatchBn.groupValues.getOrNull(1)?.trim().orEmpty()
            return WakePhraseMatch(
                triggered = true,
                matchedPersona = PersonaType.ZEE,
                commandRemainder = remainder
            )
        }

        return WakePhraseMatch(triggered = false)
    }

    fun detectLanguage(text: String): SupportedLanguage {
        val t = text.trim()
        if (t.isEmpty()) return SupportedLanguage.ENGLISH

        // Bangla script detection (\u0980-\u09FF)
        if (t.any { it in '\u0980'..'\u09FF' }) {
            return SupportedLanguage.BANGLA
        }

        // Hindi / Devanagari script detection (\u0900-\u097F)
        if (t.any { it in '\u0900'..'\u097F' }) {
            return SupportedLanguage.HINDI
        }

        // Urdu / Arabic script detection (\u0600-\u06FF)
        if (t.any { it in '\u0600'..'\u06FF' }) {
            return SupportedLanguage.URDU
        }

        val lower = t.lowercase()

        // Banglish heuristics
        val banglishMarkers = listOf(
            "amar", "tumi", "apni", "koro", "korbo", "kothay", "phone-er", "phone er",
            "bolo", "ekta", "kemon", "bhalo", "achho", "aachi", "dekho", "shuno",
            "shonao", "komao", "barao", "khulo"
        )
        if (banglishMarkers.any { lower.contains(it) }) {
            return SupportedLanguage.BANGLISH
        }

        // Roman Urdu heuristics
        val romanUrduMarkers = listOf(
            "shayari", "shairi", "yaar", "mera", "meri", "mere", "kaise", "kya",
            "bhai", "shuru", "suno", "sunao", "karo", "mujhe", "apka", "kaisi"
        )
        if (romanUrduMarkers.any { lower.contains(it) }) {
            return SupportedLanguage.ROMAN_URDU
        }

        return SupportedLanguage.ENGLISH
    }
}
