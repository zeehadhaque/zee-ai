package com.zeeai.companion.voice

/**
 * Deterministic Bengali to Phonetic Banglish Converter.
 * Device-এর লোকাল TTS ইঞ্জিনে বাংলা ভয়েস প্যাক ইনস্টল না থাকলে (শুধু English TTS থাকলে)
 * বাংলা ইউনিকোড টেক্সট সরাসরি দিলে ডিভাইস সাইলেন্ট থাকে।
 * এই কনভার্টার টেক্সটকে ফোনেটিক বাংলিশে রূপান্তর করে যাতে English TTS ইঞ্জিনও স্পষ্ট বাংলা শব্দ উচ্চারণ করতে পারে।
 */
object BengaliToBanglish {

    private val vowels = mapOf(
        'অ' to "o", 'আ' to "a", 'ই' to "i", 'ঈ' to "ee", 'উ' to "u",
        'ঊ' to "oo", 'ঋ' to "ri", 'এ' to "e", 'ঐ' to "oi", 'ও' to "o", 'ঔ' to "ou"
    )

    private val karMap = mapOf(
        'া' to "a", 'ি' to "i", 'ী' to "ee", 'ু' to "u", 'ূ' to "oo",
        'ৃ' to "ri", 'ে' to "e", 'ৈ' to "oi", 'ো' to "o", 'ৌ' to "ou",
        '্' to ""
    )

    private val consonants = mapOf(
        'ক' to "k", 'খ' to "kh", 'গ' to "g", 'ঘ' to "gh", 'ঙ' to "ng",
        'চ' to "ch", 'ছ' to "chh", 'জ' to "j", 'ঝ' to "jh", 'ঞ' to "n",
        'ট' to "t", 'ঠ' to "th", 'ড' to "d", 'ঢ' to "dh", 'ণ' to "n",
        'ত' to "t", 'থ' to "th", 'দ' to "d", 'ধ' to "dh", 'ন' to "n",
        'প' to "p", 'ফ' to "f", 'ব' to "b", 'ভ' to "bh", 'ম' to "m",
        'য' to "j", 'র' to "r", 'ল' to "l", 'শ' to "sh", 'ষ' to "sh",
        'স' to "s", 'হ' to "h", 'ড়' to "r", 'ঢ়' to "rh", 'য়' to "y",
        'ৎ' to "t", 'ং' to "ng", 'ঃ' to "h", 'ঁ' to "n"
    )

    private val digits = mapOf(
        '০' to '0', '১' to '1', '২' to '2', '৩' to '3', '৪' to '4',
        '৫' to '5', '৬' to '6', '৭' to '7', '৮' to '8', '৯' to '9'
    )

    fun toBanglish(text: String): String {
        if (text.isBlank()) return ""
        val sb = java.lang.StringBuilder()
        val n = text.length
        var i = 0

        while (i < n) {
            val ch = text[i]
            when {
                ch in digits -> sb.append(digits[ch])
                ch in vowels -> sb.append(vowels[ch])
                ch in consonants -> {
                    val base = consonants[ch] ?: ""
                    if (i + 1 < n && text[i + 1] in karMap) {
                        val kar = karMap[text[i + 1]] ?: ""
                        sb.append(base).append(kar)
                        i++
                    } else if (i + 1 < n && text[i + 1] == '্') {
                        sb.append(base)
                        i++ // Skip virama / hasant
                    } else {
                        // Inherited 'o' sound unless end of word
                        val nextIsLetter = i + 1 < n && (text[i + 1] in consonants || text[i + 1] in vowels)
                        if (nextIsLetter) {
                            sb.append(base).append("o")
                        } else {
                            sb.append(base)
                        }
                    }
                }
                ch == '।' -> sb.append(".")
                else -> sb.append(ch)
            }
            i++
        }

        return sb.toString()
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
