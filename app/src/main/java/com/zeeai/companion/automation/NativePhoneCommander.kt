package com.zeeai.companion.automation

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.Settings
import java.net.URLEncoder

data class PendingConfirmation(
    val title: String,
    val details: String,
    val onConfirm: () -> String
)

sealed class PhoneCommandOutcome {
    data object NotAPhoneCommand : PhoneCommandOutcome()
    data class Executed(val replyText: String) : PhoneCommandOutcome()
    data class NeedsConfirmation(val confirmation: PendingConfirmation) : PhoneCommandOutcome()
}

class NativePhoneCommander(private val context: Context) {

    private var isTorchOn = false

    private val knownApps = mapOf(
        "whatsapp" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "facebook" to "com.facebook.katana",
        "messenger" to "com.facebook.orca",
        "instagram" to "com.instagram.android",
        "chrome" to "com.android.chrome",
        "camera" to "com.android.camera2",
        "calculator" to "com.google.android.calculator",
        "telegram" to "org.telegram.messenger",
        "tiktok" to "com.zhiliaoapp.musically",
        "maps" to "com.google.android.apps.maps",
        "gmail" to "com.google.android.gm",
        "playstore" to "com.android.vending",
        "photos" to "com.google.android.apps.photos",
        "gallery" to "com.google.android.apps.photos"
    )

    fun isTorchActive(): Boolean = isTorchOn

    fun toggleTorch(enable: Boolean? = null): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            val newState = enable ?: !isTorchOn
            cameraManager.setTorchMode(cameraId, newState)
            isTorchOn = newState
            true
        } catch (_: Exception) {
            false
        }
    }

    fun adjustVolume(action: String): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "Audio manager pawa jayni."
        return try {
            when (action) {
                "up" -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    "Volume bariye diyechi."
                }
                "down" -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    "Volume komiye diyechi."
                }
                "mute" -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    "Volume mute kore diyechi."
                }
                "max" -> {
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
                    "Volume 100% full kore diyechi."
                }
                else -> "Volume command bujhte parini."
            }
        } catch (_: Exception) {
            "Volume change kora jayni."
        }
    }

    fun tryHandleCommand(rawInput: String): PhoneCommandOutcome {
        val lower = rawInput.lowercase().trim()

        // 1. Flashlight / Torch ON / OFF / TOGGLE
        if (Regex("\\b(torch|flashlight|light|আলো|টর্চ|ফ্ল্যাশলাইট)\\b").containsMatchIn(lower)) {
            val turnOn = Regex("\\b(on|jalao|chalu|জ্বালাও|চালু|kholo)\\b").containsMatchIn(lower)
            val turnOff = Regex("\\b(off|nepao|bondho|নেভাও|বন্ধ|off kor)\\b").containsMatchIn(lower)
            val ok = when {
                turnOn -> toggleTorch(true)
                turnOff -> toggleTorch(false)
                else -> toggleTorch(null)
            }
            return if (ok) {
                PhoneCommandOutcome.Executed(
                    if (isTorchOn) "Flashlight chalu kore diyechi." else "Flashlight bondho kore diyechi."
                )
            } else {
                PhoneCommandOutcome.Executed("Flashlight toggle kora jayni. Camera permission check koro.")
            }
        }

        // 2. Volume / Sound Control (Up, Down, Mute, Max)
        if (Regex("\\b(volume|sound|awaj|awaaj|আওয়াজ|ভলিউম|সাউন্ড)\\b").containsMatchIn(lower)) {
            val isUp = Regex("\\b(up|barao|baro|high|beshi|বাড়াও|বেশি|barie)\\b").containsMatchIn(lower)
            val isDown = Regex("\\b(down|komao|koma|low|aste|কম|কমাও|komie)\\b").containsMatchIn(lower)
            val isMute = Regex("\\b(mute|silent|shanto|নিঃশব্দ|bondho)\\b").containsMatchIn(lower)
            val isMax = Regex("\\b(max|full|100|১০০|shobcheye)\\b").containsMatchIn(lower)

            val reply = when {
                isMax -> adjustVolume("max")
                isUp -> adjustVolume("up")
                isDown -> adjustVolume("down")
                isMute -> adjustVolume("mute")
                else -> adjustVolume("up")
            }
            return PhoneCommandOutcome.Executed(reply)
        }

        // 3. Take Screenshot
        if (Regex("\\b(screenshot|screen shot|স্ক্রিনশট)\\b").containsMatchIn(lower)) {
            val service = ZeeAccessibilityService.getInstance()
                ?: return PhoneCommandOutcome.Executed(
                    "Screenshot nite hole 'ZEE AI Phone Control' Accessibility ON thakte hobe."
                )
            val ok = service.navigate("screenshot")
            return PhoneCommandOutcome.Executed(
                if (ok) "Screenshot niye niyechi."
                else "Device screenshot action support korche na (Android 9+ proyojon)."
            )
        }

        // 4. Lock Screen
        if (Regex("\\b(lock phone|lock screen|স্ক্রিন লক|ফোন লক)\\b").containsMatchIn(lower)) {
            val service = ZeeAccessibilityService.getInstance()
                ?: return PhoneCommandOutcome.Executed(
                    "Phone lock korte Accessibility permission ON thakte hobe."
                )
            val ok = service.navigate("lock")
            return PhoneCommandOutcome.Executed(
                if (ok) "Phone lock kore diyechi."
                else "Lock action support korche na (Android 9+ proyojon)."
            )
        }

        // 5. Read current screen via ZeeAccessibilityService
        if (Regex("(screen|স্ক্রিন).*(read|poro|পড়ো|পড়ো|dekho|দেখো)").containsMatchIn(lower) ||
            Regex("(read|poro|পড়ো|পড়ো|dekho|দেখো).*(screen|স্ক্রিন)").containsMatchIn(lower)) {
            val service = ZeeAccessibilityService.getInstance()
                ?: return PhoneCommandOutcome.Executed(
                    "Screen porte hole Phone Control tab theke Accessibility ON kore dao."
                )
            val text = service.readActiveScreenText(1400)
            return PhoneCommandOutcome.Executed(
                if (text.isBlank()) "Screen-e readable kono text pawa jayni."
                else "Screen-e ja dekha jachhe:\n$text"
            )
        }

        // 6. Global Navigation (Back / Home / Recents / Notifications / Quick Settings / Scroll)
        if (Regex("\\b(go back|back jao|পিছনে যাও|পিছে যাও)\\b").containsMatchIn(lower)) {
            val ok = ZeeAccessibilityService.getInstance()?.navigate("back") ?: false
            return PhoneCommandOutcome.Executed(if (ok) "Back gelam." else "Accessibility permission ON nei.")
        }
        if (Regex("\\b(go home|home screen|হোমে যাও|হোম স্ক্রিন)\\b").containsMatchIn(lower)) {
            val ok = ZeeAccessibilityService.getInstance()?.navigate("home") ?: false
            return PhoneCommandOutcome.Executed(if (ok) "Home screen-e niye gelam." else "Accessibility permission ON nei.")
        }
        if (Regex("\\b(recents|recent apps|রিসেন্ট অ্যাপস)\\b").containsMatchIn(lower)) {
            val ok = ZeeAccessibilityService.getInstance()?.navigate("recents") ?: false
            return PhoneCommandOutcome.Executed(if (ok) "Recent apps open korechi." else "Accessibility permission ON nei.")
        }
        if (Regex("\\b(notifications|notification|নোটিফিকেশন)\\b").containsMatchIn(lower)) {
            val ok = ZeeAccessibilityService.getInstance()?.navigate("notifications") ?: false
            return PhoneCommandOutcome.Executed(if (ok) "Notification panel open korechi." else "Accessibility permission ON nei.")
        }
        if (Regex("\\b(quick settings|কুইক সেটিংস)\\b").containsMatchIn(lower)) {
            val ok = ZeeAccessibilityService.getInstance()?.navigate("quick_settings") ?: false
            return PhoneCommandOutcome.Executed(if (ok) "Quick Settings open korechi." else "Accessibility permission ON nei.")
        }
        if (Regex("\\b(scroll down|niche scroll|নিচে নামাও|নিচে যাও)\\b").containsMatchIn(lower)) {
            val ok = ZeeAccessibilityService.getInstance()?.scrollActiveContainer(true) ?: false
            return PhoneCommandOutcome.Executed(if (ok) "Niche scroll korechi." else "Scroll kora jayni (Accessibility check koro).")
        }
        if (Regex("\\b(scroll up|upore scroll|উপরে ওঠাও|উপরে যাও)\\b").containsMatchIn(lower)) {
            val ok = ZeeAccessibilityService.getInstance()?.scrollActiveContainer(false) ?: false
            return PhoneCommandOutcome.Executed(if (ok) "Upore scroll korechi." else "Scroll kora jayni (Accessibility check koro).")
        }

        // 7. Battery Status
        if (Regex("\\b(battery|চার্জ|ব্যাটারি)\\b").containsMatchIn(lower) &&
            Regex("\\b(status|koto|percentage|percent|charge)\\b").containsMatchIn(lower)) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            return PhoneCommandOutcome.Executed(
                if (pct >= 0) "Phone-e ekhon $pct% battery charge ache."
                else "Battery level check kora jayni."
            )
        }

        // 8. Open Android Apps ("open WhatsApp", "YouTube kholo", "Facebook chalaw", etc.)
        if (Regex("\\b(open|kholo|খোলো|chalu|চালু|chalaw|চালাও|start)\\b").containsMatchIn(lower)) {
            val launched = findAndLaunchApp(lower)
            if (launched != null) {
                return PhoneCommandOutcome.Executed("$launched open kore diyechi.")
            }
        }

        // 9. Set Alarm ("alarm set kor 7:30", "alarm 6:00")
        if (Regex("\\b(alarm|অ্যালার্ম)\\b").containsMatchIn(lower)) {
            val timeMatch = Regex("(\\d{1,2})[:.](\\d{2})").find(lower)
            val hour = timeMatch?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 23) ?: 7
            val minute = timeMatch?.groupValues?.get(2)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
            val ok = openAlarmIntent(hour, minute, "ZEE AI Alarm")
            return PhoneCommandOutcome.Executed(
                if (ok) String.format("%02d:%02d-er alarm screen open korechi.", hour, minute)
                else "Clock app alarm support korche na."
            )
        }

        // 10. Set Timer ("timer 5 minute", "টাইমার ৩ মিনিট")
        if (Regex("\\b(timer|টাইমার)\\b").containsMatchIn(lower)) {
            val mins = Regex("(\\d+)").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 5
            val ok = openTimerIntent(mins * 60, "ZEE AI Timer")
            return PhoneCommandOutcome.Executed(
                if (ok) "$mins minute-er timer set korechi."
                else "Timer start kora jayni."
            )
        }

        // 11. WhatsApp Message Draft (Requires Confirmation Gate)
        if (lower.contains("whatsapp") && Regex("\\b(message|msg|pathao|পাঠাও|likho)\\b").containsMatchIn(lower)) {
            return PhoneCommandOutcome.NeedsConfirmation(
                PendingConfirmation(
                    title = "WhatsApp Draft Open Korbo?",
                    details = "Message draft: \"$rawInput\"\nWhatsApp-e draft khulbe, tumi dekhe tarpor Send chapbe.",
                    onConfirm = {
                        val encoded = URLEncoder.encode(rawInput, "UTF-8")
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=$encoded")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }
                        "WhatsApp draft khule diyechi."
                    }
                )
            )
        }

        // 12. Phone Call Draft (Requires Confirmation Gate — uses ACTION_DIAL safely)
        if (Regex("\\b(call|কল)\\b").containsMatchIn(lower)) {
            val digits = Regex("(\\+?\\d{6,15})").find(lower)?.groupValues?.get(1)
            if (digits != null) {
                return PhoneCommandOutcome.NeedsConfirmation(
                    PendingConfirmation(
                        title = "Call Dialer Open Korbo?",
                        details = "Number: $digits (Dialer open hobe, confirm kore call dibe)",
                        onConfirm = {
                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                            "$digits dialer-e open kore diyechi."
                        }
                    )
                )
            }
        }

        return PhoneCommandOutcome.NotAPhoneCommand
    }

    private fun findAndLaunchApp(spokenInput: String): String? {
        // Direct known app match
        for ((keyword, pkg) in knownApps) {
            if (spokenInput.contains(keyword)) {
                if (launchApp(pkg)) return keyword.replaceFirstChar { it.uppercase() }
            }
        }

        // Common Bengali transliterated app names
        val banglaMap = mapOf(
            "ইউটিউব" to "com.google.android.youtube",
            "ফেসবুক" to "com.facebook.katana",
            "হোয়াটসঅ্যাপ" to "com.whatsapp",
            "মেসেঞ্জার" to "com.facebook.orca",
            "ক্যালকুলেটর" to "com.google.android.calculator",
            "ক্যামেরা" to "com.android.camera2",
            "সেটিংস" to Settings.ACTION_SETTINGS,
            "গ্যালারি" to "com.google.android.apps.photos"
        )
        for ((bName, target) in banglaMap) {
            if (spokenInput.contains(bName)) {
                if (target == Settings.ACTION_SETTINGS) {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return "Settings"
                    }
                }
                if (launchApp(target)) return bName
            }
        }

        // Dynamic search across installed apps on the device
        val pm = context.packageManager
        val installed = runCatching { pm.getInstalledApplications(0) }.getOrNull().orEmpty()
        for (app in installed) {
            val label = runCatching { pm.getApplicationLabel(app).toString().lowercase() }.getOrNull() ?: continue
            if (label.length > 2 && spokenInput.contains(label)) {
                val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return label.replaceFirstChar { it.uppercase() }
                }
            }
        }

        return null
    }

    private fun launchApp(packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun openAlarmIntent(hour: Int, minute: Int, label: String): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun openTimerIntent(seconds: Int, label: String): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
