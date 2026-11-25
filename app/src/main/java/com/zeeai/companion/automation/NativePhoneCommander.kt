package com.zeeai.companion.automation

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
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
        "gallery" to "com.google.android.apps.photos",
        "phone" to "com.google.android.dialer"
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
                    "Volume bariye diyechi, boss."
                }
                "down" -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    "Volume komiye diyechi, boss."
                }
                "mute" -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    "Volume mute kore diyechi, boss."
                }
                "max" -> {
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
                    "Volume 100% full kore diyechi, boss."
                }
                else -> "Volume command bujhte parini boss."
            }
        } catch (_: Exception) {
            "Volume change kora jayni boss."
        }
    }

    fun findContactPhoneNumber(name: String): String? {
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numIdx != -1) it.getString(numIdx) else null
                } else null
            }
        } catch (_: Exception) {
            null
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
                    if (isTorchOn) "Flashlight chalu kore diyechi boss." else "Flashlight bondho kore diyechi boss."
                )
            } else {
                PhoneCommandOutcome.Executed("Flashlight toggle kora jayni boss. Camera permission check koro.")
            }
        }

        // 2. Volume / Sound Control
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

        // 3. Post Story (Facebook / Instagram / Social)
        if (Regex("\\b(story|স্টোরি)\\b").containsMatchIn(lower) &&
            Regex("\\b(post|dao|chalu|koro|শেয়ার|দাও|পোস্ট)\\b").containsMatchIn(lower)) {
            val isInstagram = lower.contains("instagram") || lower.contains("insta")
            val isFacebook = lower.contains("facebook") || lower.contains("fb")

            if (isInstagram) {
                launchApp("com.instagram.android")
                ZeeAccessibilityService.sendControlCommand(context, "OPEN_STORY")
                ZeeAccessibilityService.getInstance()?.openStoryCreator()
                return PhoneCommandOutcome.Executed("Instagram Story creator open kore diyechi boss.")
            } else if (isFacebook) {
                launchApp("com.facebook.katana")
                ZeeAccessibilityService.sendControlCommand(context, "OPEN_STORY")
                ZeeAccessibilityService.getInstance()?.openStoryCreator()
                return PhoneCommandOutcome.Executed("Facebook Story creator open kore diyechi boss.")
            } else {
                // Default to Instagram or Facebook if installed
                val launched = launchApp("com.instagram.android") || launchApp("com.facebook.katana")
                ZeeAccessibilityService.sendControlCommand(context, "OPEN_STORY")
                ZeeAccessibilityService.getInstance()?.openStoryCreator()
                return PhoneCommandOutcome.Executed(
                    if (launched) "Story posting creator open kore diyechi boss."
                    else "Story post korte Instagram ba Facebook app pawa jayni boss."
                )
            }
        }

        // 4. Autonomous Messaging: "WhatsApp-e [number/name] ke message pathao [text]"
        if (lower.contains("whatsapp") && Regex("\\b(message|msg|pathao|পাঠাও|bolo|বল|পাঠিয়ে)\\b").containsMatchIn(lower)) {
            val digits = Regex("(\\+?\\d{7,15})").find(rawInput)?.groupValues?.get(1)
            val textToExtract = rawInput
                .replace(Regex("(?i)\\b(whatsapp|message|msg|pathao|bolo|পাঠাও|বল|কে|daao|dao)\\b"), "")
                .replace(digits ?: "", "")
                .trim()

            val msgPayload = textToExtract.ifBlank { "Hello!" }

            if (digits != null) {
                val cleanDigits = digits.replace(Regex("[^0-9]"), "")
                val encoded = URLEncoder.encode(msgPayload, "UTF-8")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$cleanDigits?text=$encoded")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                // Auto click Send button via Accessibility Service
                ZeeAccessibilityService.getInstance()?.autoClickSendAfterDelay(900L)
                return PhoneCommandOutcome.Executed("WhatsApp-e $digits er inbox-e message type kore send kore diyechi boss.")
            } else {
                // Contact Name search
                val words = lower.split(" ").filter { it !in listOf("whatsapp", "whatsapp-e", "message", "msg", "pathao", "ke", "bolo", "পাঠাও") }
                val targetName = words.firstOrNull().orEmpty()
                val phone = if (targetName.isNotBlank()) findContactPhoneNumber(targetName) else null

                if (phone != null) {
                    val cleanDigits = phone.replace(Regex("[^0-9]"), "")
                    val encoded = URLEncoder.encode(msgPayload, "UTF-8")
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$cleanDigits?text=$encoded")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ZeeAccessibilityService.getInstance()?.autoClickSendAfterDelay(900L)
                    return PhoneCommandOutcome.Executed("WhatsApp-e $targetName ($phone) ke message send kore diyechi boss.")
                } else {
                    launchApp("com.whatsapp")
                    ZeeAccessibilityService.getInstance()?.autoClickSendAfterDelay(1200L)
                    return PhoneCommandOutcome.Executed("WhatsApp open korechi boss. Target contact select korlei message auto-send hobe.")
                }
            }
        }

        // 5. Autonomous Messaging: Messenger / Facebook Inbox ("Messenger-e [name] ke message pathao [text]")
        if ((lower.contains("messenger") || lower.contains("facebook")) &&
            Regex("\\b(message|msg|pathao|পাঠাও|inbox)\\b").containsMatchIn(lower)) {
            launchApp("com.facebook.orca")
            val textToExtract = rawInput.replace(Regex("(?i)\\b(messenger|facebook|message|msg|pathao|পাঠাও|inbox)\\b"), "").trim()
            if (textToExtract.isNotBlank()) {
                ZeeAccessibilityService.getInstance()?.autoTypeAndSend(textToExtract)
            }
            return PhoneCommandOutcome.Executed("Messenger inbox open kore diyechi boss.")
        }

        // 6. Active Screen Auto-Type & Send ("ei message send koro [text]", "msg type koro [text]")
        if (Regex("\\b(type and send|msg type|message type|send this|এই মেসেজ পাঠাও)\\b").containsMatchIn(lower)) {
            val toType = rawInput.replace(Regex("(?i)\\b(type and send|msg type|message type|send this|এই মেসেজ পাঠাও)\\b"), "").trim()
            if (toType.isNotBlank()) {
                val ok = ZeeAccessibilityService.getInstance()?.autoTypeAndSend(toType) ?: false
                return PhoneCommandOutcome.Executed(
                    if (ok) "Screen-er active inbox-e message type kore send kore diyechi boss."
                    else "Active kono text box pawa jayni boss (chat open koro)."
                )
            }
        }

        // 7. Phone Calls ("call 017xxxxxxxx", "call Abba", "Rahim ke call dao", "ফোন করো")
        if (Regex("\\b(call|phone|ফোন|কল|ডায়াল)\\b").containsMatchIn(lower)) {
            val digits = Regex("(\\+?\\d{6,15})").find(rawInput)?.groupValues?.get(1)
            if (digits != null) {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ZeeAccessibilityService.getInstance()?.autoClickCallAfterDelay(500L)
                return PhoneCommandOutcome.Executed("$digits dialer-e call open kore diyechi boss.")
            } else {
                // Check contact name from query
                val targetName = lower
                    .replace(Regex("\\b(call|phone|koro|dao|ke|amake|ফোন|কল|করো|দাও|কে)\\b"), "")
                    .trim()

                if (targetName.isNotBlank()) {
                    val phone = findContactPhoneNumber(targetName)
                    if (phone != null) {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        ZeeAccessibilityService.getInstance()?.autoClickCallAfterDelay(500L)
                        return PhoneCommandOutcome.Executed("$targetName ($phone) ke call dial kore diyechi boss.")
                    } else {
                        // Open dialer directly
                        val intent = Intent(Intent.ACTION_DIAL).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        context.startActivity(intent)
                        return PhoneCommandOutcome.Executed("$targetName phone contacts-e pawa jayni boss, dialer open korechi.")
                    }
                }
            }
        }

        // 8. Screenshot & Lock Screen
        if (Regex("\\b(screenshot|screen shot|স্ক্রিনশট)\\b").containsMatchIn(lower)) {
            val service = ZeeAccessibilityService.getInstance()
                ?: return PhoneCommandOutcome.Executed("Screenshot nite Accessibility permission ON thakte hobe boss.")
            val ok = service.navigate("screenshot")
            return PhoneCommandOutcome.Executed(if (ok) "Screenshot niye niyechi boss." else "Screenshot action failed boss.")
        }
        if (Regex("\\b(lock phone|lock screen|স্ক্রিন লক|ফোন লক)\\b").containsMatchIn(lower)) {
            val service = ZeeAccessibilityService.getInstance()
                ?: return PhoneCommandOutcome.Executed("Phone lock korte Accessibility permission ON thakte hobe boss.")
            val ok = service.navigate("lock")
            return PhoneCommandOutcome.Executed(if (ok) "Phone lock kore diyechi boss." else "Lock action failed boss.")
        }

        // 9. Read Screen Text
        if (Regex("(screen|স্ক্রিন).*(read|poro|পড়ো|dekho|দেখো)").containsMatchIn(lower) ||
            Regex("(read|poro|পড়ো|dekho|দেখো).*(screen|স্ক্রিন)").containsMatchIn(lower)) {
            val service = ZeeAccessibilityService.getInstance()
                ?: return PhoneCommandOutcome.Executed("Screen porte Phone Control tab theke Accessibility ON kore dao boss.")
            val text = service.readActiveScreenText(1400)
            return PhoneCommandOutcome.Executed(if (text.isBlank()) "Screen-e readable kono text pawa jayni boss." else "Screen-e ja dekha jachhe boss:\n$text")
        }

        // 10. Navigation (Back / Home / Recents / Notifications)
        if (Regex("\\b(go back|back jao|পিছনে যাও|পিছে যাও)\\b").containsMatchIn(lower)) {
            val enabled = ZeeAccessibilityService.isServiceEnabled(context)
            if (enabled) {
                ZeeAccessibilityService.sendControlCommand(context, "NAV", "back")
                ZeeAccessibilityService.getInstance()?.navigate("back")
            }
            return PhoneCommandOutcome.Executed(if (enabled) "Back gelam boss." else "Accessibility permission ON nei boss.")
        }
        if (Regex("\\b(go home|home screen|হোমে যাও|হোম স্ক্রিন)\\b").containsMatchIn(lower)) {
            val enabled = ZeeAccessibilityService.isServiceEnabled(context)
            if (enabled) {
                ZeeAccessibilityService.sendControlCommand(context, "NAV", "home")
                ZeeAccessibilityService.getInstance()?.navigate("home")
            }
            return PhoneCommandOutcome.Executed(if (enabled) "Home screen-e niye gelam boss." else "Accessibility permission ON nei boss.")
        }
        if (Regex("\\b(recents|recent apps|রিসেন্ট অ্যাপস)\\b").containsMatchIn(lower)) {
            val enabled = ZeeAccessibilityService.isServiceEnabled(context)
            if (enabled) {
                ZeeAccessibilityService.sendControlCommand(context, "NAV", "recents")
                ZeeAccessibilityService.getInstance()?.navigate("recents")
            }
            return PhoneCommandOutcome.Executed(if (enabled) "Recent apps open korechi boss." else "Accessibility permission ON nei boss.")
        }
        if (Regex("\\b(notifications|notification|নোটিফিকেশন)\\b").containsMatchIn(lower)) {
            val enabled = ZeeAccessibilityService.isServiceEnabled(context)
            if (enabled) {
                ZeeAccessibilityService.sendControlCommand(context, "NAV", "notifications")
                ZeeAccessibilityService.getInstance()?.navigate("notifications")
            }
            return PhoneCommandOutcome.Executed(if (enabled) "Notification panel open korechi boss." else "Accessibility permission ON nei boss.")
        }

        // 11. Battery Status
        if (Regex("\\b(battery|চার্জ|ব্যাটারি)\\b").containsMatchIn(lower) &&
            Regex("\\b(status|koto|percentage|percent|charge)\\b").containsMatchIn(lower)) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            return PhoneCommandOutcome.Executed(if (pct >= 0) "Phone-e ekhon $pct% battery charge ache boss." else "Battery level check kora jayni boss.")
        }

        // 12. Open Installed Apps
        if (Regex("\\b(open|kholo|খোলো|chalu|চালু|chalaw|চালাও|start)\\b").containsMatchIn(lower)) {
            val launched = findAndLaunchApp(lower)
            if (launched != null) {
                return PhoneCommandOutcome.Executed("Khule diyechi boss, $launched open hoyeche.")
            }
        }

        // 13. Alarms & Timers
        if (Regex("\\b(alarm|অ্যালার্ম)\\b").containsMatchIn(lower)) {
            val timeMatch = Regex("(\\d{1,2})[:.](\\d{2})").find(lower)
            val hour = timeMatch?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 23) ?: 7
            val minute = timeMatch?.groupValues?.get(2)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
            val ok = openAlarmIntent(hour, minute, "ZEE AI Alarm")
            return PhoneCommandOutcome.Executed(
                if (ok) String.format("%02d:%02d-er alarm screen open korechi boss.", hour, minute)
                else "Clock app alarm support korche na boss."
            )
        }
        if (Regex("\\b(timer|টাইমার)\\b").containsMatchIn(lower)) {
            val mins = Regex("(\\d+)").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 5
            val ok = openTimerIntent(mins * 60, "ZEE AI Timer")
            return PhoneCommandOutcome.Executed(if (ok) "$mins minute-er timer set korechi boss." else "Timer start kora jayni boss.")
        }

        return PhoneCommandOutcome.NotAPhoneCommand
    }

    private fun findAndLaunchApp(spokenInput: String): String? {
        for ((keyword, pkg) in knownApps) {
            if (spokenInput.contains(keyword)) {
                if (launchApp(pkg)) return keyword.replaceFirstChar { it.uppercase() }
            }
        }

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
