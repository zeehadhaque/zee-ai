package com.zeeai.companion.automation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.zeeai.companion.offline.PersonalOfflineMemoryVault
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class OfflineToolOutcome(
    val handled: Boolean,
    val success: Boolean,
    val reply: String
)

class OfflineAutonomousPhoneController(
    private val context: Context,
    private val memoryVault: PersonalOfflineMemoryVault
) {
    private val toolPattern = Regex("""\[\[TOOL:([A-Z_]+)\|([^\]]+)]]""")

    private fun normalizeVoiceCommand(raw: String): String {
        var text = raw.trim()
        // শুরু থেকে Wake-word ও সম্বোধন বাদ দেওয়া (Hey ZEE / ZHEE / জি / বন্ধু / ভাই / প্লিজ / বস / boss)
        val prefixRegex = Regex(
            """^(?:hey\s+|ok\s+|hi\s+|hello\s+)?(?:zee|zhee|ji|g|জি|ঝি|বন্ধু|ভাই|প্লিজ|please|বস|boss)[,\s।:!-]+""",
            RegexOption.IGNORE_CASE
        )
        repeat(2) {
            text = text.replace(prefixRegex, "").trim()
        }
        // শেষের দাঁড়ি, ফুলস্টপ, প্রশ্নবোধক বা "please / একটু / তো / বস / boss" বাদ দেওয়া
        text = text
            .replace(Regex("""[।.!?,"']+$"""), "")
            .replace(Regex("""\s+(?:please|plz|প্লিজ|দাও|করো তো|তো|একটু|বস|boss)$""", RegexOption.IGNORE_CASE), "")
            .trim()
        return text
    }

    fun executeToolsInAiResponse(rawAiOutput: String): Pair<String, List<String>> {
        val actionLogs = mutableListOf<String>()
        for (m in toolPattern.findAll(rawAiOutput)) {
            val toolName = m.groupValues[1].trim().uppercase()
            val toolArg = m.groupValues[2].trim()
            val result = dispatchTool(toolName, toolArg)
            if (result.reply.isNotBlank()) actionLogs.add(result.reply)
        }
        val cleanText = rawAiOutput.replace(toolPattern, "").trim()
        return cleanText to actionLogs
    }

    fun tryInstantOfflineCommand(input: String): OfflineToolOutcome {
        val text = normalizeVoiceCommand(input)
        if (text.isBlank()) return OfflineToolOutcome(false, false, "")
        val lower = text.lowercase()

        // ১. সময় ও ব্যাটারি
        if (lower in listOf("time", "koyta baje", "koita baje", "এখন কয়টা বাজে", "সময় কত", "what time is it") ||
            Regex("""(?:koyta|koita|কয়টা)\s+(?:baje|বাজে)""").containsMatchIn(lower) ||
            Regex("""(?:what\s+time|current\s+time)""").containsMatchIn(lower)
        ) {
            val now = SimpleDateFormat("hh:mm a, EEEE, dd MMM", Locale.getDefault()).format(Date())
            return OfflineToolOutcome(true, true, "এখন বাজে $now বস!")
        }
        if (lower.contains("battery") || lower.contains("ব্যাটারি") || lower.contains("চার্জ কত") || lower.contains("charge koto")) {
            return OfflineToolOutcome(true, true, "ফোনে এখন ${getBatteryPercent()}% চার্জ আছে বস।")
        }

        // ২. ফ্ল্যাশলাইট (CAMERA permission ছাড়াই API 23+ এ কাজ করে)
        if (lower.contains("torch") || lower.contains("flash") || lower.contains("টর্চ") || lower.contains("ফ্ল্যাশ") || lower.contains("লাইট")) {
            val off = lower.contains("off") || lower.contains("bondho") || lower.contains("বন্ধ") || lower.contains("nivao") || lower.contains("নিভাও")
            val on = lower.contains("on") || lower.contains("chalu") || lower.contains("চালু") || lower.contains("jalao") || lower.contains("জ্বালাও") || lower.contains("দাও")
            if (off) return dispatchTool("TORCH", "OFF")
            if (on) return dispatchTool("TORCH", "ON")
        }

        // ৩. ভলিউম
        if (lower.contains("volume") || lower.contains("sound") || lower.contains("ভলিউম") || lower.contains("সাউন্ড") || lower.contains("আওয়াজ")) {
            return when {
                lower.contains("mute") || lower.contains("silent") || lower.contains("মিউট") || lower.contains("থামাও") -> dispatchTool("VOLUME", "MUTE")
                lower.contains("max") || lower.contains("full") || lower.contains("ফুল") -> dispatchTool("VOLUME", "MAX")
                lower.contains("down") || lower.contains("komao") || lower.contains("কমাও") || lower.contains("low") -> dispatchTool("VOLUME", "DOWN")
                lower.contains("up") || lower.contains("barao") || lower.contains("বাড়াও") || lower.contains("high") -> dispatchTool("VOLUME", "UP")
                else -> OfflineToolOutcome(false, false, "")
            }
        }

        // ৪. Cross-Process Accessibility কন্ট্রোল
        if (lower.contains("screenshot") || lower.contains("স্ক্রিনশট")) return dispatchTool("NAV", "SCREENSHOT")
        if (lower in listOf("go home", "home", "হোম", "হোম স্ক্রিন") || lower.contains("home screen") || lower.contains("হোমে যাও")) return dispatchTool("NAV", "HOME")
        if (lower in listOf("go back", "back", "ব্যাক", "পিছনে যাও") || lower.contains("go back") || lower.contains("ব্যাকে যাও")) return dispatchTool("NAV", "BACK")
        if (lower.contains("recent") || lower.contains("রিসেন্ট")) return dispatchTool("NAV", "RECENTS")
        if (lower.contains("notification") || lower.contains("নোটিফিকেশন")) return dispatchTool("NAV", "NOTIFICATIONS")
        if (lower.contains("lock screen") || lower.contains("phone lock") || lower.contains("screen lock") || lower.contains("স্ক্রিন লক") || lower.contains("ফোন লক")) return dispatchTool("NAV", "LOCK")
        if (lower.contains("scroll down") || lower.contains("niche scroll") || lower.contains("নিচে স্ক্রল")) return dispatchTool("SCROLL", "DOWN")
        if (lower.contains("scroll up") || lower.contains("upore scroll") || lower.contains("উপরে স্ক্রল")) return dispatchTool("SCROLL", "UP")

        Regex("""^(?:click|tap|press|চাপো)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            return dispatchTool("CLICK_TEXT", it.groupValues[1].trim())
        }
        Regex("""^(?:type|write|লিখো)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            return dispatchTool("TYPE_TEXT", it.groupValues[1].trim())
        }

        // ৫. সেটিংস প্যানেল
        if (lower.contains("wifi") || lower.contains("ওয়াইফাই")) {
            if (lower.contains("on") || lower.contains("off") || lower.contains("open") || lower.contains("setting") || lower.contains("খোলো")) {
                return dispatchTool("SETTINGS", "WIFI")
            }
        }
        if (lower.contains("bluetooth") || lower.contains("ব্লুটুথ")) return dispatchTool("SETTINGS", "BLUETOOTH")
        if (lower.contains("brightness") || lower.contains("ব্রাইটনেস")) return dispatchTool("SETTINGS", "BRIGHTNESS")

        // ৬. কল (নম্বর বা কন্টাক্ট নাম — যেমন "call Siam", "Siam ke call dao", "017... call")
        val numberRegex = Regex("""(\+?\d[\d\s-]{5,15}\d)""")
        if (lower.contains("call") || lower.contains("কল") || lower.contains("dial")) {
            val numberMatch = numberRegex.find(text)
            if (numberMatch != null) {
                val cleanNum = numberMatch.groupValues[1].replace(" ", "").replace("-", "")
                return dispatchTool("CALL", cleanNum)
            }
            val namePatterns = listOf(
                Regex("""^(?:call|dial|কল করো|কল দাও)\s+(.+)$""", RegexOption.IGNORE_CASE),
                Regex("""^(.+?)\s*(?:ke|কে)\s+(?:call|কল)\s*(?:dao|koro|দাও|করো)?$""", RegexOption.IGNORE_CASE)
            )
            for (p in namePatterns) {
                val m = p.find(text) ?: continue
                val contactName = m.groupValues[1].trim()
                if (contactName.length in 2..32) {
                    return dispatchTool("CALL", contactName)
                }
            }
        }

        // ৭. টাইমার ও অ্যালার্ম
        val timerRegex = Regex("""(\d+)\s*(?:min|minute|minutes|মিনিট)""", RegexOption.IGNORE_CASE)
        if ((lower.contains("timer") || lower.contains("টাইমার")) && timerRegex.containsMatchIn(text)) {
            return dispatchTool("TIMER", timerRegex.find(text)!!.groupValues[1])
        }
        val alarmRegex = Regex("""(\d{1,2}):(\d{2})""")
        if ((lower.contains("alarm") || lower.contains("অ্যালার্ম")) && alarmRegex.containsMatchIn(text)) {
            val m = alarmRegex.find(text)!!
            return dispatchTool("ALARM", "${m.groupValues[1]}:${m.groupValues[2]}")
        }

        // ৮. অ্যাপ ওপেন (<queries> দিয়ে, QUERY_ALL_PACKAGES ছাড়াই)
        val openPatterns = listOf(
            Regex("""^(?:open|launch|start|run|chalu koro|খুলুন|খোলো|চালু করো|ওপেন করো)\s+(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""^(.+?)\s+(?:kholo|khulo|open koro|chalu koro|open|launch|খোলো|চালু করো|ওপেন করো|খুলুন)$""", RegexOption.IGNORE_CASE)
        )
        for (regex in openPatterns) {
            val m = regex.find(text) ?: continue
            val query = m.groupValues[1].trim()
            if (query.length in 2..32) {
                val res = dispatchTool("OPEN_APP", query)
                if (res.success) return res
            }
        }

        return OfflineToolOutcome(handled = false, success = false, reply = "")
    }

    fun dispatchTool(tool: String, arg: String): OfflineToolOutcome {
        return try {
            when (tool) {
                "OPEN_APP" -> openAppByName(arg)
                "TORCH" -> toggleFlashlight(arg.equals("ON", ignoreCase = true))
                "VOLUME" -> adjustVolume(arg)
                "CALL" -> dialNumberOrContact(arg)
                "SMS" -> openSmsComposer(arg)
                "ALARM" -> setAlarm(arg)
                "TIMER" -> setTimer(arg)
                "SETTINGS" -> openSettings(arg)
                "NAV", "SCROLL", "CLICK_TEXT", "TYPE_TEXT" -> sendCrossProcessAccessibility(tool, arg)
                "REMEMBER" -> {
                    memoryVault.remember(arg, "explicit")
                    OfflineToolOutcome(true, true, "মনে রেখে দিয়েছি বস! কখনো ভুলব না।")
                }
                else -> OfflineToolOutcome(false, false, "")
            }
        } catch (e: Exception) {
            Log.e("OfflinePhoneCtrl", "Tool $tool failed: ${e.message}", e)
            OfflineToolOutcome(true, false, "কাজটা করতে পারমিশন লাগবে: ${e.localizedMessage ?: "Permission required"}")
        }
    }

    private fun sendCrossProcessAccessibility(type: String, arg: String): OfflineToolOutcome {
        if (!ZeeAccessibilityService.isServiceEnabled(context)) {
            return OfflineToolOutcome(true, false, "ফোন কন্ট্রোলের জন্য Models ট্যাব থেকে ZEE AI Accessibility চালু করে নাও বস।")
        }
        ZeeAccessibilityService.sendControlCommand(context, type, arg)
        val msg = when (type) {
            "SCROLL" -> "স্ক্রল করে দিয়েছি বস!"
            "CLICK_TEXT" -> "'$arg'-এ ট্যাপ করে দিয়েছি বস!"
            "TYPE_TEXT" -> "লিখে দিয়েছি বস!"
            else -> "করে দিয়েছি বস!"
        }
        return OfflineToolOutcome(true, true, msg)
    }

    private fun dialNumberOrContact(target: String): OfflineToolOutcome {
        val cleaned = target.trim()
        val isPhoneNumber = cleaned.matches(Regex("""^\+?\d[\d\s-]{4,}$"""))
        val finalNumber = if (isPhoneNumber) {
            cleaned.replace(" ", "").replace("-", "")
        } else {
            val resolved = resolveContactNumberByName(cleaned)
                ?: return OfflineToolOutcome(
                    true,
                    false,
                    "'$cleaned' নামে কোনো কন্টাক্ট পাইনি অথবা Contacts পারমিশন দেওয়া নেই।"
                )
            resolved
        }

        // Zero-permission safe ACTION_DIAL (Play Protect 100% clean)
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$finalNumber")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return OfflineToolOutcome(true, true, "$cleaned ($finalNumber)-এর জন্য ডায়ালার খুলে দিয়েছি বস!")
    }

    private fun resolveContactNumberByName(nameQuery: String): String? {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$nameQuery%")

        return runCatching {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numberIdx >= 0) cursor.getString(numberIdx)?.replace(" ", "")?.replace("-", "") else null
                } else null
            }
        }.getOrNull()
    }

    private fun openAppByName(rawQuery: String): OfflineToolOutcome {
        val bengaliAliases = mapOf(
            "ইউটিউব" to "youtube", "হোয়াটসঅ্যাপ" to "whatsapp", "হোয়াটসঅ্যাপ" to "whatsapp",
            "ফেসবুক" to "facebook", "মেসেঞ্জার" to "messenger", "ইনস্টাগ্রাম" to "instagram",
            "ক্যামেরা" to "camera", "গ্যালারি" to "gallery", "ফটো" to "photos",
            "সেটিংস" to "settings", "ক্যালকুলেটর" to "calculator", "ক্রোম" to "chrome",
            "মেসেজ" to "messages", "ফোন" to "phone", "ডায়ালার" to "phone",
            "ঘড়ি" to "clock", "ফাইল" to "files", "নোট" to "notes"
        )
        val normalized = bengaliAliases[rawQuery.trim()] ?: rawQuery.trim().lowercase()

        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // AndroidManifest-এর <queries> দিয়ে QUERY_ALL_PACKAGES ছাড়াই সব লঞ্চার অ্যাপ পাওয়া যায়
        val apps = pm.queryIntentActivities(launcherIntent, 0)

        val matched = apps.firstOrNull { info ->
            val label = info.loadLabel(pm).toString().lowercase()
            val pkg = info.activityInfo.packageName.lowercase()
            label == normalized || label.contains(normalized) || pkg.contains(normalized)
        }

        if (matched != null) {
            val launch = pm.getLaunchIntentForPackage(matched.activityInfo.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return OfflineToolOutcome(true, true, "খুলে দিয়েছি বস, ${matched.loadLabel(pm)} চালু হয়েছে!")
            }
        }
        return OfflineToolOutcome(true, false, "বস, ফোনে '$rawQuery' নামের অ্যাপটা খুঁজে পেলাম না।")
    }

    private fun toggleFlashlight(enable: Boolean): OfflineToolOutcome {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return OfflineToolOutcome(true, false, "বস, এই ফোনে ফ্ল্যাশলাইট পাওয়া যায়নি।")

        cm.setTorchMode(camId, enable)
        return OfflineToolOutcome(true, true, if (enable) "ফ্ল্যাশলাইট জ্বালিয়ে দিয়েছি বস!" else "ফ্ল্যাশলাইট বন্ধ করে দিয়েছি বস!")
    }

    private fun adjustVolume(mode: String): OfflineToolOutcome {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (mode.uppercase()) {
            "UP" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "DOWN" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "MUTE" -> am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
            "MAX" -> am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), AudioManager.FLAG_SHOW_UI)
        }
        return OfflineToolOutcome(true, true, "ভলিউম অ্যাডজাস্ট করে দিয়েছি বস!")
    }

    private fun openSmsComposer(arg: String): OfflineToolOutcome {
        val parts = arg.split("|", limit = 2)
        val num = parts.getOrNull(0)?.trim().orEmpty()
        val body = parts.getOrNull(1)?.trim().orEmpty()
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num")).apply {
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return OfflineToolOutcome(true, true, "$num নম্বরে মেসেজ রেডি করে দিয়েছি বস!")
    }

    private fun setAlarm(timeStr: String): OfflineToolOutcome {
        val parts = timeStr.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return OfflineToolOutcome(true, false, "বস, অ্যালার্মের সময় বুঝতে পারিনি।")
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, h)
            putExtra(AlarmClock.EXTRA_MINUTES, m)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return OfflineToolOutcome(true, true, "$h:${m.toString().padStart(2, '0')}-এ অ্যালার্ম সেট করেছি বস!")
    }

    private fun setTimer(minutesStr: String): OfflineToolOutcome {
        val mins = minutesStr.filter { it.isDigit() }.toIntOrNull() ?: 1
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, mins * 60)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return OfflineToolOutcome(true, true, "$mins মিনিটের টাইমার চালু করে দিয়েছি বস!")
    }

    private fun openSettings(panel: String): OfflineToolOutcome {
        val action = when (panel.uppercase()) {
            "WIFI" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS
            "BLUETOOTH" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "BRIGHTNESS" -> Settings.ACTION_DISPLAY_SETTINGS
            "SOUND" -> Settings.ACTION_SOUND_SETTINGS
            "BATTERY" -> Intent.ACTION_POWER_USAGE_SUMMARY
            else -> Settings.ACTION_SETTINGS
        }
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return OfflineToolOutcome(true, true, "সেটিংস খুলে দিয়েছি বস!")
    }

    private fun getBatteryPercent(): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (level >= 0 && scale > 0) (level * 100) / scale else 0
    }

    fun getInstalledAppsHint(): String = ""
}
