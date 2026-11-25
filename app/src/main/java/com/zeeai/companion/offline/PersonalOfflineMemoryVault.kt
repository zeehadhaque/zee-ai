package com.zeeai.companion.offline

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PersonalMemoryItem(
    val id: Long,
    val fact: String,
    val category: String,
    val timestamp: String
)

class PersonalOfflineMemoryVault(context: Context) {

    companion object {
        private const val PREFS = "zee_private_bestfriend_vault"
        private const val KEY_MEMORIES = "memories_v2"
        private const val KEY_USER_NICKNAME = "user_nickname"
        private const val MAX_MEMORIES = 80
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dateFmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

    fun setNickname(name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty()) prefs.edit().putString(KEY_USER_NICKNAME, clean).apply()
    }

    fun getNickname(): String? = prefs.getString(KEY_USER_NICKNAME, null)

    fun remember(fact: String, category: String = "personal") {
        val clean = fact.trim().replace("\n", " ")
        if (clean.length < 4) return

        val arr = loadRaw()
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("fact").equals(clean, ignoreCase = true)) {
                return
            }
        }

        val entry = JSONObject().apply {
            put("id", System.currentTimeMillis())
            put("fact", clean.take(280))
            put("category", category)
            put("timestamp", dateFmt.format(Date()))
        }
        arr.put(entry)

        val trimmed = JSONArray()
        val start = maxOf(0, arr.length() - MAX_MEMORIES)
        for (i in start until arr.length()) trimmed.put(arr.get(i))

        prefs.edit().putString(KEY_MEMORIES, trimmed.toString()).apply()
    }

    /**
     * ইউজারের মেসেজ থেকে অটোমেটিক ব্যক্তিগত কথা ও অনুভূতি অফলাইনে সেভ করে
     */
    fun autoExtractFromMessage(userMessage: String) {
        val clean = userMessage.trim()
        if (clean.length !in 8..260) return
        val lower = clean.lowercase()

        // নাম ধরার চেষ্টা
        val nameRegex = Regex("""(?:amar nam|my name is|call me|আমাকে .* ডাকবি|আমার নাম)\s+([a-zA-Z\u0980-\u09FF]{2,20})""", RegexOption.IGNORE_CASE)
        nameRegex.find(clean)?.let { match ->
            setNickname(match.groupValues[1])
            remember("User's name/nickname: ${match.groupValues[1]}", "identity")
        }

        val personalTriggers = listOf(
            "mone rakhis", "মনে রাখিস", "remember that", "remember this", "ভুলিস না",
            "mon kharap", "মন খারাপ", "ekla lagche", "একা লাগছে", "kosto", "কষ্ট লাগছে",
            "bhalobashi", "ভালোবাসি", "crush", "miss korchi", "মিস করছি", "breakup",
            "amar pochondo", "আমার পছন্দ", "amar dream", "আমার স্বপ্ন", "amar goal",
            "amar bondhu", "আমার বন্ধু", "best friend", "family", "মা", "বাবা",
            "cinema", "film", "movie", "story", "script", "গান", "কবিতা লিখি"
        )

        if (personalTriggers.any { lower.contains(it) }) {
            val cat = when {
                lower.contains("mon kharap") || lower.contains("মন খারাপ") || lower.contains("kosto") || lower.contains("একা") -> "emotion"
                lower.contains("dream") || lower.contains("স্বপ্ন") || lower.contains("goal") -> "dream"
                else -> "life_share"
            }
            remember(clean, cat)
        }
    }

    fun getAllMemories(): List<PersonalMemoryItem> {
        val arr = loadRaw()
        val list = mutableListOf<PersonalMemoryItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                PersonalMemoryItem(
                    id = o.optLong("id", i.toLong()),
                    fact = o.optString("fact"),
                    category = o.optString("category", "personal"),
                    timestamp = o.optString("timestamp", "")
                )
            )
        }
        return list.reversed()
    }

    /**
     * বর্তমান প্রশ্নের সাথে মিল আছে এমন মেমোরি + সাম্প্রতিক মেমোরি প্রম্পটে দেয়
     */
    fun buildPromptMemoryBlock(currentUserQuery: String, maxItems: Int = 8): String {
        val all = getAllMemories()
        val nick = getNickname()
        if (all.isEmpty() && nick.isNullOrBlank()) {
            return "No saved memories yet — assist your Boss naturally."
        }

        val queryWords = currentUserQuery.lowercase()
            .split(Regex("""[\s,.!?]+"""))
            .filter { it.length >= 3 }
            .toSet()

        val relevant = all.filter { item ->
            val fl = item.fact.lowercase()
            queryWords.any { fl.contains(it) }
        }.take(4)

        val recent = all.filter { it !in relevant }.take(maxItems - relevant.size)
        val combined = (relevant + recent).distinctBy { it.fact }

        return buildString {
            if (!nick.isNullOrBlank()) {
                appendLine("- Boss's Name: $nick")
            }
            for (m in combined) {
                append("- [").append(m.category).append(" • ").append(m.timestamp).append("] ")
                appendLine(m.fact)
            }
        }.trim()
    }

    fun deleteMemory(id: Long) {
        val arr = loadRaw()
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optLong("id") != id) kept.put(o)
        }
        prefs.edit().putString(KEY_MEMORIES, kept.toString()).apply()
    }

    fun clearAll() {
        prefs.edit().remove(KEY_MEMORIES).apply()
    }

    private fun loadRaw(): JSONArray {
        val raw = prefs.getString(KEY_MEMORIES, "[]") ?: "[]"
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }
}
