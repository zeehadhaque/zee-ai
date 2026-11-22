package com.zeeai.companion.automation

import android.accessibilityservice.AccessibilityService
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
import android.provider.Settings
import android.util.Log
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

    /**
     * AI আউটপুটের ভেতর থেকে [[TOOL:NAME|ARG]] খুঁজে বের করে ফোনে এক্সিকিউট করে
     */
    fun executeToolsInAiResponse(rawAiOutput: String): Pair<String, List<String>> {
        val actionLogs = mutableListOf<String>()
        val matches = toolPattern.findAll(rawAiOutput).toList()
        for (m in matches) {
            val toolName = m.groupValues[1].trim().uppercase()
            val toolArg = m.groupValues[2].trim()
            val result = dispatchTool(toolName, toolArg)
            if (result.reply.isNotBlank()) actionLogs.add(result.reply)
        }
        val cleanHumanText = rawAiOutput.replace(toolPattern, "").trim()
        return cleanHumanText to actionLogs
    }

    /**
     * সরাসরি বাংলা / বাংলিশ / ইংরেজি কমান্ড <50ms-এ অফলাইনে এক্সিকিউট করে (LLM ছাড়াই)
     */
    fun tryInstantOfflineCommand(input: String): OfflineToolOutcome {
        val text = input.trim()
        val lower = text.lowercase()

        // ১. সময় ও তারিখ / ব্যাটারি (অফলাইনে তাৎক্ষণিক তথ্য)
        if (lower in listOf("time", "koyta baje", "koita baje", "এখন কয়টা বাজে", "সময় কত", "what time is it")) {
            val now = SimpleDateFormat("hh:mm a, EEEE, dd MMM", Locale.getDefault()).format(Date())
            return OfflineToolOutcome(true, true, "এখন বাজে $now বন্ধু!")
        }
        if (lower.contains("battery") || lower.contains("ব্যাটারি") || lower.contains("চার্জ কত")) {
            val pct = getBatteryPercent()
            return OfflineToolOutcome(true, true, "ফোনে এখন $pct% চার্জ আছে বন্ধু।")
        }

        // ২. ফ্ল্যাশলাইট / টর্চ
        if (lower.contains("torch") || lower.contains("flash") || lower.contains("টর্চ") || lower.contains("ফ্ল্যাশ")) {
            val off = lower.contains("off") || lower.contains("bondho") || lower.contains("বন্ধ") || lower.contains("nivao") || lower.contains("নিভাও")
            val on = lower.contains("on") || lower.contains("chalu") || lower.contains("চালু") || lower.contains("jalao") || lower.contains("জ্বালাও")
            if (off) return dispatchTool("TORCH", "OFF")
            if (on) return dispatchTool("TORCH", "ON")
        }

        // ৩. ভলিউম
        if (lower.contains("volume") || lower.contains("sound") || lower.contains("ভলিউম") || lower.contains("সাউন্ড")) {
            return when {
                lower.contains("mute") || lower.contains("silent") || lower.contains("মিউট") -> dispatchTool("VOLUME", "MUTE")
                lower.contains("max") || lower.contains("full") || lower.contains("ফুল") -> dispatchTool("VOLUME", "MAX")
                lower.contains("down") || lower.contains("komao") || lower.contains("কমাও") || lower.contains("low") -> dispatchTool("VOLUME", "DOWN")
                lower.contains("up") || lower.contains("barao") || lower.contains("বাড়াও") || lower.contains("high") -> dispatchTool("VOLUME", "UP")
                else -> OfflineToolOutcome(false, false, "")
            }
        }

        // ৪. নেভিগেশন ও স্ক্রিন কন্ট্রোল (Accessibility)
        if (lower.contains("screenshot") || lower.contains("স্ক্রিনশট")) return dispatchTool("NAV", "SCREENSHOT")
        if (lower in listOf("go home", "home", "হোম", "হোম স্ক্রিন")) return dispatchTool("NAV", "HOME")
        if (lower in listOf("go back", "back", "ব্যাক", "পিছনে যাও")) return dispatchTool("NAV", "BACK")
        if (lower.contains("recent") || lower.contains("রিসেন্ট")) return dispatchTool("NAV", "RECENTS")
        if (lower.contains("notification") || lower.contains("নোটিফিকেশন")) return dispatchTool("NAV", "NOTIFICATIONS")
        if (lower.contains("lock screen") || lower.contains("phone lock") || lower.contains("স্ক্রিন লক")) return dispatchTool("NAV", "LOCK")
        if (lower.contains("scroll down") || lower.contains("niche scroll") || lower.contains("নিচে স্ক্রল")) return dispatchTool("SCROLL", "DOWN")
        if (lower.contains("scroll up") || lower.contains("upore scroll") || lower.contains("উপরে স্ক্রল")) return dispatchTool("SCROLL", "UP")

        // ৫. স্ক্রিনে ক্লিক ও টাইপ
        Regex("""^(?:click|tap|press|চাপো)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            return dispatchTool("CLICK_TEXT", it.groupValues[1].trim())
        }
        Regex("""^(?:type|write|লিখো)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            return dispatchTool("TYPE_TEXT", it.groupValues[1].trim())
        }

        // ৬. সেটিংস প্যানেল (Wi-Fi / Bluetooth / Brightness / Sound / Battery)
        if (lower.contains("wifi") || lower.contains("ওয়াইফাই")) {
            if (lower.contains("on") || lower.contains("off") || lower.contains("open") || lower.contains("setting") || lower.contains("খোলো")) {
                return dispatchTool("SETTINGS", "WIFI")
            }
        }
        if (lower.contains("bluetooth") || lower.contains("ব্লুটুথ")) return dispatchTool("SETTINGS", "BLUETOOTH")
        if (lower.contains("brightness") || lower.contains("ব্রাইটনেস") || lower.contains("আলো কমাও") || lower.contains("আলো বাড়াও")) {
            return dispatchTool("SETTINGS", "BRIGHTNESS")
        }

        // ৭. কল / ডায়াল
        val numberRegex = Regex("""(\+?\d[\d\s-]{5,15}\d)""")
        if ((lower.contains("call") || lower.contains("কল") || lower.contains("dial")) && numberRegex.containsMatchIn(text)) {
            val num = numberRegex.find(text)!!.groupValues[1].replace(" ", "").replace("-", "")
            return dispatchTool("CALL", num)
        }

        // ৮. টাইমার ও অ্যালার্ম
        val timerRegex = Regex("""(\d+)\s*(?:min|minute|minutes|মিনিট)""", RegexOption.IGNORE_CASE)
        if ((lower.contains("timer") || lower.contains("টাইমার")) && timerRegex.containsMatchIn(text)) {
            return dispatchTool("TIMER", timerRegex.find(text)!!.groupValues[1])
        }
        val alarmRegex = Regex("""(\d{1,2}):(\d{2})""")
        if ((lower.contains("alarm") || lower.contains("অ্যালার্ম")) && alarmRegex.containsMatchIn(text)) {
            val m = alarmRegex.find(text)!!
            return dispatchTool("ALARM", "${m.groupValues[1]}:${m.groupValues[2]}")
        }

        // ৯. যেকোনো অ্যাপ ওপেন করা (বাংলা + বাংলিশ + ইংরেজি)
        val openPatterns = listOf(
            Regex("""^(?:open|launch|start|run)\s+(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""^(.+?)\s+(?:kholo|khulo|open koro|chalu koro|খোলো|চালু করো|ওপেন করো)$""", RegexOption.IGNORE_CASE)
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
                "CALL" -> openDialer(arg)
                "SMS" -> openSmsComposer(arg)
                "ALARM" -> setAlarm(arg)
                "TIMER" -> setTimer(arg)
                "SETTINGS" -> openSettings(arg)
                "NAV" -> runAccessibilityGlobal(arg)
                "SCROLL" -> runAccessibilityScroll(arg)
                "CLICK_TEXT" -> runAccessibilityClick(arg)
                "TYPE_TEXT" -> runAccessibilityType(arg)
                "REMEMBER" -> {
                    memoryVault.remember(arg, "explicit")
                    OfflineToolOutcome(true, true, "মনে রেখে দিলাম বন্ধু! কখনো ভুলব না।")
                }
                else -> OfflineToolOutcome(false, false, "")
            }
        } catch (e: Exception) {
            Log.e("OfflinePhoneCtrl", "Tool $tool error: ${e.message}", e)
            OfflineToolOutcome(true, false, "কাজটা করতে ফোনের পারমিশন আটকেছে: ${e.localizedMessage ?: "Permission required"}")
        }
    }

    private fun openAppByName(rawQuery: String): OfflineToolOutcome {
        val bengaliAliases = mapOf(
            "ইউটিউব" to "youtube", "হোয়াটসঅ্যাপ" to "whatsapp", "হোয়াটসঅ্যাপ" to "whatsapp",
            "ফেসবুক" to "facebook", "মেসেঞ্জার" to "messenger", "ইনস্টাগ্রাম" to "instagram",
            "ক্যামেরা" to "camera", "গ্যালারি" to "gallery", "ফটো" to "photos",
            "সেটিংস" to "settings", "ক্যালকুলেটর" to "calculator", "ক্রোম" to "chrome",
            "মেসেজ" to "messages", "ফোন" to "phone", "ডায়ালার" to "phone",
            "ঘড়ি" to "clock", "অ্যালার্ম" to "clock", "ফাইল" to "files",
            "নোট" to "notes", "ক্যালেন্ডার" to "calendar", "ম্যাপ" to "maps",
            "প্লে স্টোর" to "play store", "রেকর্ডার" to "recorder", "মিউজিক" to "music"
        )
        val normalized = bengaliAliases[rawQuery.trim()] ?: rawQuery.trim().lowercase()

        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)

        val matched = apps.firstOrNull { info ->
            val label = info.loadLabel(pm).toString().lowercase()
            val pkg = info.activityInfo.packageName.lowercase()
            label == normalized || label.contains(normalized) || pkg.contains(normalized)
        }

        if (matched != null) {
            val launchIntent = pm.getLaunchIntentForPackage(matched.activityInfo.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                val label = matched.loadLabel(pm).toString()
                return OfflineToolOutcome(true, true, "হয়ে গেছে বন্ধু, $label খুলে দিয়েছি!")
            }
        }
        return OfflineToolOutcome(true, false, "ফোনে '$rawQuery' অ্যাপটা খুঁজে পেলাম না।")
    }

    private fun toggleFlashlight(enable: Boolean): OfflineToolOutcome {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return OfflineToolOutcome(true, false, "এই ফোনে ফ্ল্যাশলাইট পাওয়া যায়নি।")

        cm.setTorchMode(camId, enable)
        return OfflineToolOutcome(true, true, if (enable) "ফ্ল্যাশলাইট জ্বালিয়ে দিলাম!" else "ফ্ল্যাশলাইট বন্ধ করে দিয়েছি!")
    }

    private fun adjustVolume(mode: String): OfflineToolOutcome {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (mode.uppercase()) {
            "UP" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "DOWN" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "MUTE" -> am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
            "MAX" -> am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), AudioManager.FLAG_SHOW_UI)
        }
        return OfflineToolOutcome(true, true, "ভলিউম অ্যাডজাস্ট করে দিয়েছি!")
    }

    private fun openDialer(number: String): OfflineToolOutcome {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return OfflineToolOutcome(true, true, "$number নম্বরে ডায়ালার খুলে দিয়েছি!")
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
        return OfflineToolOutcome(true, true, "$num নম্বরে মেসেজ রেডি করে দিয়েছি!")
    }

    private fun setAlarm(timeStr: String): OfflineToolOutcome {
        val parts = timeStr.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return OfflineToolOutcome(true, false, "অ্যালার্মের সময় বুঝতে পারিনি।")
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, h)
            putExtra(AlarmClock.EXTRA_MINUTES, m)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return OfflineToolOutcome(true, true, "$h:${m.toString().padStart(2, '0')}-এ অ্যালার্ম সেট করছি!")
    }

    private fun setTimer(minutesStr: String): OfflineToolOutcome {
        val mins = minutesStr.filter { it.isDigit() }.toIntOrNull() ?: 1
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, mins * 60)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return OfflineToolOutcome(true, true, "$mins মিনিটের টাইমার চালু করে দিয়েছি!")
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
        return OfflineToolOutcome(true, true, "সেটিংস খুলে দিয়েছি!")
    }

    private fun runAccessibilityGlobal(nav: String): OfflineToolOutcome {
        val service = ZeeAccessibilityService.instance
            ?: return OfflineToolOutcome(true, false, "এই কন্ট্রোলের জন্য Models ট্যাব থেকে Accessibility চালু রাখো।")

        val actionId = when (nav.uppercase()) {
            "HOME" -> AccessibilityService.GLOBAL_ACTION_HOME
            "BACK" -> AccessibilityService.GLOBAL_ACTION_BACK
            "RECENTS" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "LOCK" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else -1
            "SCREENSHOT" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else -1
            else -> -1
        }
        if (actionId == -1) return OfflineToolOutcome(true, false, "এই ভার্সনে অ্যাকশনটি সাপোর্ট নেই।")
        val ok = service.performGlobalAction(actionId)
        return OfflineToolOutcome(true, ok, if (ok) "ডান বন্ধু!" else "অ্যাকশনটি কাজ করেনি।")
    }

    private fun runAccessibilityScroll(dir: String): OfflineToolOutcome {
        val service = ZeeAccessibilityService.instance
            ?: return OfflineToolOutcome(true, false, "স্ক্রল করতে Accessibility অন থাকা লাগবে।")
        val ok = service.scrollCurrentScreen(down = dir.equals("DOWN", ignoreCase = true))
        return OfflineToolOutcome(true, ok, if (ok) "স্ক্রল করে দিলাম!" else "এই স্ক্রিনে স্ক্রল করা যায়নি।")
    }

    private fun runAccessibilityClick(target: String): OfflineToolOutcome {
        val service = ZeeAccessibilityService.instance
            ?: return OfflineToolOutcome(true, false, "ট্যাপ করতে Accessibility অন থাকা লাগবে।")
        val ok = service.clickNodeContainingText(target)
        return OfflineToolOutcome(true, ok, if (ok) "'$target'-এ ট্যাপ করে দিয়েছি!" else "স্ক্রিনে '$target' বাটনটা পাইনি।")
    }

    private fun runAccessibilityType(text: String): OfflineToolOutcome {
        val service = ZeeAccessibilityService.instance
            ?: return OfflineToolOutcome(true, false, "টাইপ করতে Accessibility অন থাকা লাগবে।")
        val ok = service.typeTextIntoFocusedField(text)
        return OfflineToolOutcome(true, ok, if (ok) "লিখে দিয়েছি বন্ধু!" else "কোনো টেক্সট বক্স ফোকাস করা নেই।")
    }

    private fun getBatteryPercent(): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (level >= 0 && scale > 0) (level * 100) / scale else 0
    }

    fun getInstalledAppsHint(): String {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            pm.queryIntentActivities(intent, 0)
                .map { it.loadLabel(pm).toString() }
                .distinct()
                .take(28)
                .joinToString(", ")
        }.getOrDefault("YouTube, WhatsApp, Camera, Gallery, Settings, Phone, Chrome, Clock, Calculator")
    }
}
