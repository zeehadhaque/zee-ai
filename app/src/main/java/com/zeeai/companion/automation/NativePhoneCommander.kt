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
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    @Volatile
    private var isTorchOn = false

    private val knownApps = mapOf(
        "whatsapp" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "facebook" to "com.facebook.katana",
        "messenger" to "com.facebook.orca",
        "instagram" to "com.instagram.android",
        "chrome" to "com.android.chrome",
        "calculator" to "com.google.android.calculator",
        "telegram" to "org.telegram.messenger",
        "tiktok" to "com.zhiliaoapp.musically",
        "maps" to "com.google.android.apps.maps",
        "gmail" to "com.google.android.gm",
        "playstore" to "com.android.vending",
        "play store" to "com.android.vending",
        "photos" to "com.google.android.apps.photos",
        "gallery" to "com.google.android.apps.photos",
        "phone" to "com.google.android.dialer"
    )

    fun isTorchActive(): Boolean = isTorchOn

    private fun hasAnyTerm(lower: String, vararg terms: String): Boolean {
        val pattern = terms.joinToString("|") { Regex.escape(it) }
        val regex = Regex("""(?:^|[\s,।.!?:;"'()-])(?:$pattern)(?=$|[\s,।.!?:;"'()-])""", RegexOption.IGNORE_CASE)
        return regex.containsMatchIn(lower)
    }

    private fun normalizeCommand(raw: String): String {
        var text = raw.trim()
        val prefixRegex = Regex(
            """^(?:hey\s+|ok\s+|hi\s+|hello\s+)?(?:zee|zhee|ji|g|জি|ঝি|বন্ধু|ভাই|প্লিজ|please|বস|boss)[,\s।:!-]+""",
            RegexOption.IGNORE_CASE
        )
        repeat(2) {
            text = text.replace(prefixRegex, "").trim()
        }
        return text
            .replace(Regex("""[।.!?,"']+$"""), "")
            .replace(Regex("""\s+(?:please|plz|প্লিজ|দাও|করো তো|তো|একটু|বস|boss)$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

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
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
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
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null

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
                    if (numIdx != -1) it.getString(numIdx)?.replace(" ", "")?.replace("-", "") else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun runAccessibilityAction(cmdType: String, cmdArg: String = "", directBlock: (ZeeAccessibilityService) -> Boolean): Boolean? {
        val service = ZeeAccessibilityService.getInstance()
        if (service != null) {
            return directBlock(service)
        }
        if (!ZeeAccessibilityService.isServiceEnabled(context)) {
            return null
        }
        ZeeAccessibilityService.sendControlCommand(context, cmdType, cmdArg)
        return true
    }

    fun tryHandleCommand(rawInput: String): PhoneCommandOutcome {
        val text = normalizeCommand(rawInput)
        if (text.isBlank()) return PhoneCommandOutcome.NotAPhoneCommand
        val lower = text.lowercase().trim()

        // 0. Time & Battery Instant Queries
        if (hasAnyTerm(lower, "time", "koyta baje", "koita baje", "what time", "what time is it", "এখন কয়টা বাজে", "কয়টা বাজে", "সময় কত") ||
            Regex("""(?:koyta|koita|কয়টা|কয়টা)\s+(?:baje|বাজে)""").containsMatchIn(lower)
        ) {
            val now = SimpleDateFormat("hh:mm a, EEEE, dd MMM", Locale.getDefault()).format(Date())
            return PhoneCommandOutcome.Executed("Ekhon baje $now, boss.")
        }

        if (hasAnyTerm(lower, "battery", "ব্যাটারি", "চার্জ", "charge") &&
            !hasAnyTerm(lower, "settings", "সেটিংস") &&
            (lower == "battery" || hasAnyTerm(lower, "status", "koto", "percentage", "percent", "charge", "level", "কত", "চার্জ", "ব্যাটারি", "চেক"))
        ) {
            val pct = getBatteryPercent()
            return PhoneCommandOutcome.Executed(
                if (pct >= 0) "Phone-e ekhon $pct% battery charge ache boss." else "Battery level check kora jayni boss."
            )
        }

        // 1. Flashlight / Torch ON / OFF / TOGGLE
        if (hasAnyTerm(lower, "torch", "flashlight", "flash", "light", "আলো", "টর্চ", "ফ্ল্যাশ", "ফ্ল্যাশলাইট", "লাইট")) {
            val turnOn = hasAnyTerm(lower, "on", "jalao", "chalu", "kholo", "start", "জ্বালাও", "জ্বালা", "চালু")
            val turnOff = hasAnyTerm(lower, "off", "nepao", "nivao", "bondho", "stop", "নেভাও", "নিভাও", "বন্ধ")
            val ok = when {
                turnOff -> toggleTorch(false)
                turnOn -> toggleTorch(true)
                else -> toggleTorch(null)
            }
            return if (ok) {
                PhoneCommandOutcome.Executed(
                    if (isTorchOn) "Flashlight chalu kore diyechi boss." else "Flashlight bondho kore diyechi boss."
                )
            } else {
                PhoneCommandOutcome.Executed("Flashlight toggle kora jayni boss (camera onno app-e busy thakte pare).")
            }
        }

        // 2. Volume / Sound Control
        if (hasAnyTerm(lower, "volume", "sound", "awaj", "awaaj", "mute", "silent", "আওয়াজ", "আওয়াজ", "ভলিউম", "সাউন্ড", "মিউট")) {
            val isMute = hasAnyTerm(lower, "mute", "silent", "shanto", "নিঃশব্দ", "মিউট", "থামাও")
            val isMax = hasAnyTerm(lower, "max", "full", "100", "১০০", "ফুল")
            val isDown = hasAnyTerm(lower, "down", "komao", "koma", "low", "aste", "কমাও", "কম", "কমিয়ে")
            val isUp = hasAnyTerm(lower, "up", "barao", "baro", "high", "beshi", "বাড়াও", "বাড়াও", "বেশি", "বাড়িয়ে")

            val reply = when {
                isMute -> adjustVolume("mute")
                isMax -> adjustVolume("max")
                isDown -> adjustVolume("down")
                isUp -> adjustVolume("up")
                else -> adjustVolume("up")
            }
            return PhoneCommandOutcome.Executed(reply)
        }

        // 2b. Settings Panels (Wi-Fi, Bluetooth, Brightness/Display, Sound, Battery, Main Settings)
        if (hasAnyTerm(lower, "wifi", "wi-fi", "ওয়াইফাই", "ওয়াইফাই")) {
            val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            } else {
                Settings.ACTION_WIFI_SETTINGS
            }
            val ok = runCatching {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            return PhoneCommandOutcome.Executed(if (ok) "Wi-Fi connectivity panel open kore diyechi boss." else "Wi-Fi settings khola jayni boss.")
        }
        if (hasAnyTerm(lower, "bluetooth", "ব্লুটুথ")) {
            val ok = runCatching {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            return PhoneCommandOutcome.Executed(if (ok) "Bluetooth settings open kore diyechi boss." else "Bluetooth settings khola jayni boss.")
        }
        if (hasAnyTerm(lower, "brightness", "ব্রাইটনেস", "display settings")) {
            val ok = runCatching {
                context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            return PhoneCommandOutcome.Executed(if (ok) "Display brightness settings khule diyechi boss." else "Display settings khola jayni boss.")
        }
        if (lower == "settings" || lower == "open settings" || hasAnyTerm(lower, "সেটিংস")) {
            val ok = runCatching {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            return PhoneCommandOutcome.Executed(if (ok) "Phone settings open kore diyechi boss." else "Settings khola jayni boss.")
        }

        // 3. Post Story (Facebook / Instagram / Social)
        if (hasAnyTerm(lower, "story", "স্টোরি") &&
            hasAnyTerm(lower, "post", "dao", "chalu", "koro", "শেয়ার", "দাও", "পোস্ট")
        ) {
            val isInstagram = lower.contains("instagram") || lower.contains("insta")
            val isFacebook = lower.contains("facebook") || lower.contains("fb")

            return if (isInstagram) {
                launchApp("com.instagram.android")
                ZeeAccessibilityService.sendControlCommand(context, "OPEN_STORY")
                PhoneCommandOutcome.Executed("Instagram Story creator open kore diyechi boss.")
            } else if (isFacebook) {
                launchApp("com.facebook.katana")
                ZeeAccessibilityService.sendControlCommand(context, "OPEN_STORY")
                PhoneCommandOutcome.Executed("Facebook Story creator open kore diyechi boss.")
            } else {
                val launched = launchApp("com.instagram.android") || launchApp("com.facebook.katana")
                ZeeAccessibilityService.sendControlCommand(context, "OPEN_STORY")
                PhoneCommandOutcome.Executed(
                    if (launched) "Story posting creator open kore diyechi boss."
                    else "Story post korte Instagram ba Facebook app pawa jayni boss."
                )
            }
        }

        // 4. Autonomous Messaging: WhatsApp
        if (lower.contains("whatsapp") && hasAnyTerm(lower, "message", "msg", "pathao", "পাঠাও", "bolo", "বল", "পাঠিয়ে", "পাঠিয়ে")) {
            val digits = Regex("(\\+?\\d{7,15})").find(rawInput)?.groupValues?.get(1)
            val textToExtract = rawInput
                .replace(Regex("(?i)(?:whatsapp|message|msg|pathao|bolo|পাঠাও|বল|কে|daao|dao)"), "")
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
                ZeeAccessibilityService.sendControlCommand(context, "CLICK_SEND", "900")
                return PhoneCommandOutcome.Executed("WhatsApp-e $digits er inbox-e message ready kore diyechi boss.")
            } else {
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
                    ZeeAccessibilityService.sendControlCommand(context, "CLICK_SEND", "900")
                    return PhoneCommandOutcome.Executed("WhatsApp-e $targetName ($phone) ke message ready kore diyechi boss.")
                } else {
                    launchApp("com.whatsapp")
                    ZeeAccessibilityService.sendControlCommand(context, "CLICK_SEND", "1200")
                    return PhoneCommandOutcome.Executed("WhatsApp open korechi boss. Contact select korlei send kora jabe.")
                }
            }
        }

        // 5. Autonomous Messaging: Messenger / Facebook
        if ((lower.contains("messenger") || lower.contains("facebook")) &&
            hasAnyTerm(lower, "message", "msg", "pathao", "পাঠাও", "inbox")
        ) {
            launchApp("com.facebook.orca")
            val textToExtract = rawInput.replace(Regex("(?i)(?:messenger|facebook|message|msg|pathao|পাঠাও|inbox)"), "").trim()
            if (textToExtract.isNotBlank()) {
                val service = ZeeAccessibilityService.getInstance()
                if (service != null) {
                    service.autoTypeAndSend(textToExtract)
                } else if (ZeeAccessibilityService.isServiceEnabled(context)) {
                    ZeeAccessibilityService.sendControlCommand(context, "TYPE_TEXT", textToExtract)
                    ZeeAccessibilityService.sendControlCommand(context, "CLICK_SEND", "900")
                }
            }
            return PhoneCommandOutcome.Executed("Messenger inbox open kore diyechi boss.")
        }

        // 6. Active Screen Auto-Type, Click & Scroll
        Regex("""^(?:click|tap|press|চাপো)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val targetLabel = m.groupValues[1].trim()
            val ok = runAccessibilityAction("CLICK_TEXT", targetLabel) { it.clickNodeContainingText(targetLabel) }
                ?: return PhoneCommandOutcome.Executed("Screen click korte Phone Control tab theke Accessibility ON kore dao boss.")
            return PhoneCommandOutcome.Executed(if (ok) "'$targetLabel'-e tap kore diyechi boss." else "'$targetLabel' screen-e khuje pawa jayni boss.")
        }
        Regex("""^(?:type|write|লিখো)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val payload = m.groupValues[1].trim()
            val ok = runAccessibilityAction("TYPE_TEXT", payload) { it.typeTextIntoFocusedField(payload) }
                ?: return PhoneCommandOutcome.Executed("Type korte Accessibility permission ON thakte hobe boss.")
            return PhoneCommandOutcome.Executed(if (ok) "Text field-e likhe diyechi boss." else "Active kono text box pawa jayni boss.")
        }
        if (lower.contains("type and send") || lower.contains("msg type") || lower.contains("message type") || lower.contains("send this") || lower.contains("এই মেসেজ পাঠাও")) {
            val toType = rawInput.replace(Regex("(?i)(?:type and send|msg type|message type|send this|এই মেসেজ পাঠাও)"), "").trim()
            if (toType.isNotBlank()) {
                val ok = runAccessibilityAction("TYPE_TEXT", toType) { it.autoTypeAndSend(toType) }
                    ?: return PhoneCommandOutcome.Executed("Auto-send korte Accessibility ON kore dao boss.")
                return PhoneCommandOutcome.Executed(
                    if (ok) "Screen-er active inbox-e message type kore send kore diyechi boss."
                    else "Active kono text box pawa jayni boss (chat open koro)."
                )
            }
        }
        if (lower.contains("scroll down") || lower.contains("niche scroll") || lower.contains("নিচে স্ক্রল")) {
            val ok = runAccessibilityAction("SCROLL", "DOWN") { it.scrollCurrentScreen(down = true) }
                ?: return PhoneCommandOutcome.Executed("Scroll korte Accessibility permission ON kore dao boss.")
            return PhoneCommandOutcome.Executed(if (ok) "Screen niche scroll kore diyechi boss." else "Scroll kora jayni boss.")
        }
        if (lower.contains("scroll up") || lower.contains("upore scroll") || lower.contains("উপরে স্ক্রল")) {
            val ok = runAccessibilityAction("SCROLL", "UP") { it.scrollCurrentScreen(down = false) }
                ?: return PhoneCommandOutcome.Executed("Scroll korte Accessibility permission ON kore dao boss.")
            return PhoneCommandOutcome.Executed(if (ok) "Screen upore scroll kore diyechi boss." else "Scroll kora jayni boss.")
        }

        // 7. Phone Calls ("call 017xxxxxxxx", "call Abba", "Rahim ke call dao", "ফোন করো")
        if (hasAnyTerm(lower, "call", "dial", "phone", "ফোন", "কল", "ডায়াল", "ডায়াল") &&
            !lower.contains("lock") && !lower.contains("লক")
        ) {
            val digits = Regex("(\\+?\\d{6,15})").find(rawInput)?.groupValues?.get(1)
            if (digits != null) {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ZeeAccessibilityService.sendControlCommand(context, "CLICK_CALL", "500")
                return PhoneCommandOutcome.Executed("$digits dialer-e open kore diyechi boss.")
            } else {
                val targetName = text
                    .replace(Regex("""(?i)(?:^|\s)(?:call|dial|phone|koro|dao|ke|amake|ফোন|কল|করো|দাও|কে|ডায়াল|ডায়াল)(?=\s|$)"""), " ")
                    .trim()

                if (targetName.isNotBlank()) {
                    val hasContactsPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                    if (!hasContactsPerm) {
                        val intent = Intent(Intent.ACTION_DIAL).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        context.startActivity(intent)
                        return PhoneCommandOutcome.Executed("Contacts permission dewa nei boss, tai dialer open korechi.")
                    }
                    val phone = findContactPhoneNumber(targetName)
                    if (phone != null) {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        ZeeAccessibilityService.sendControlCommand(context, "CLICK_CALL", "500")
                        return PhoneCommandOutcome.Executed("$targetName ($phone) ke call dial kore diyechi boss.")
                    } else {
                        val intent = Intent(Intent.ACTION_DIAL).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        context.startActivity(intent)
                        return PhoneCommandOutcome.Executed("$targetName phone contacts-e pawa jayni boss, dialer open korechi.")
                    }
                }
            }
        }

        // 8. Screenshot & Lock Screen
        if (hasAnyTerm(lower, "screenshot", "screen shot", "স্ক্রিনশট")) {
            val ok = runAccessibilityAction("NAV", "SCREENSHOT") { it.navigate("screenshot") }
                ?: return PhoneCommandOutcome.Executed("Screenshot nite Phone Control theke Accessibility permission ON korte hobe boss.")
            return PhoneCommandOutcome.Executed(if (ok) "Screenshot niye niyechi boss." else "Screenshot action failed boss.")
        }
        if (lower.contains("lock phone") || lower.contains("lock screen") || lower.contains("phone lock") || lower.contains("screen lock") || lower.contains("স্ক্রিন লক") || lower.contains("ফোন লক")) {
            val ok = runAccessibilityAction("NAV", "LOCK") { it.navigate("lock") }
                ?: return PhoneCommandOutcome.Executed("Phone lock korte Accessibility permission ON thakte hobe boss.")
            return PhoneCommandOutcome.Executed(if (ok) "Phone lock kore diyechi boss." else "Lock action failed boss.")
        }

        // 9. Read Screen Text
        if (Regex("(screen|স্ক্রিন).*(read|poro|পড়ো|পড়ো|dekho|দেখো)").containsMatchIn(lower) ||
            Regex("(read|poro|পড়ো|পড়ো|dekho|দেখো).*(screen|স্ক্রিন)").containsMatchIn(lower)
        ) {
            val service = ZeeAccessibilityService.getInstance()
            if (service != null) {
                val screenText = service.readActiveScreenText(1400)
                return PhoneCommandOutcome.Executed(
                    if (screenText.isBlank()) "Screen-e readable kono text pawa jayni boss."
                    else "Screen-e ja dekha jachhe boss:\n$screenText"
                )
            }
            if (!ZeeAccessibilityService.isServiceEnabled(context)) {
                return PhoneCommandOutcome.Executed("Screen porte Phone Control tab theke Accessibility ON kore dao boss.")
            }
            ZeeAccessibilityService.sendControlCommand(context, "SNAPSHOT_SCREEN")
            return PhoneCommandOutcome.Executed("Screen scan request pathiyechi boss.")
        }

        // 10. Navigation (Back / Home / Recents / Notifications)
        if (lower == "back" || lower.contains("go back") || lower.contains("back jao") || lower.contains("পিছনে যাও") || lower.contains("পিছে যাও") || lower.contains("ব্যাকে যাও") || lower == "ব্যাক") {
            val ok = runAccessibilityAction("NAV", "BACK") { it.navigate("back") }
                ?: return PhoneCommandOutcome.Executed("Back jete Accessibility permission ON nei boss.")
            return PhoneCommandOutcome.Executed(if (ok) "Back gelam boss." else "Back action failed boss.")
        }
        if (lower == "home" || lower.contains("go home") || lower.contains("home screen") || lower.contains("হোমে যাও") || lower.contains("হোম স্ক্রিন") || lower == "হোম") {
            val ok = runAccessibilityAction("NAV", "HOME") { it.navigate("home") }
                ?: return PhoneCommandOutcome.Executed("Home screen-e jete Accessibility permission ON nei boss.")
            return PhoneCommandOutcome.Executed(if (ok) "Home screen-e niye gelam boss." else "Home action failed boss.")
        }
        if (hasAnyTerm(lower, "recents", "recent", "recent apps", "রিসেন্ট", "রিসেন্ট অ্যাপস")) {
            val ok = runAccessibilityAction("NAV", "RECENTS") { it.navigate("recents") }
                ?: return PhoneCommandOutcome.Executed("Recents open korte Accessibility permission ON nei boss.")
            return PhoneCommandOutcome.Executed(if (ok) "Recent apps open korechi boss." else "Recents failed boss.")
        }
        if (hasAnyTerm(lower, "notifications", "notification", "নোটিফিকেশন")) {
            val ok = runAccessibilityAction("NAV", "NOTIFICATIONS") { it.navigate("notifications") }
                ?: return PhoneCommandOutcome.Executed("Notifications open korte Accessibility permission ON nei boss.")
            return PhoneCommandOutcome.Executed(if (ok) "Notification panel open korechi boss." else "Notifications failed boss.")
        }

        // 11. Open Installed Apps (Prefix OR Suffix, English OR Bengali)
        val openPatterns = listOf(
            Regex("""^(?:open|launch|start|run|chalu\s+koro|open\s+koro|খুলুন|খোলো|চালু\s+করো|ওপেন\s+করো|চালাও)\s+(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""^(.+?)\s+(?:kholo|khulo|open\s+koro|chalu\s+koro|chalaw|open|launch|খোলো|চালু\s+করো|ওপেন\s+করো|খুলুন|চালাও)$""", RegexOption.IGNORE_CASE)
        )
        for (regex in openPatterns) {
            val m = regex.find(text) ?: continue
            val query = m.groupValues[1].trim()
            if (query.length in 2..36) {
                val launched = findAndLaunchApp(query.lowercase())
                if (launched != null) {
                    return PhoneCommandOutcome.Executed("Khule diyechi boss, $launched open hoyeche.")
                }
            }
        }
        if (hasAnyTerm(lower, "open", "kholo", "khulo", "chalu", "chalaw", "start", "launch", "খোলো", "খুলুন", "চালু", "চালাও", "ওপেন")) {
            val launched = findAndLaunchApp(lower)
            if (launched != null) {
                return PhoneCommandOutcome.Executed("Khule diyechi boss, $launched open hoyeche.")
            }
        }

        // 12. Alarms & Timers
        if (hasAnyTerm(lower, "alarm", "অ্যালার্ম", "এলার্ম")) {
            val timeMatch = Regex("(\\d{1,2})[:.](\\d{2})").find(lower)
            val hour = timeMatch?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 23) ?: 7
            val minute = timeMatch?.groupValues?.get(2)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
            val ok = openAlarmIntent(hour, minute, "ZEE AI Alarm")
            return PhoneCommandOutcome.Executed(
                if (ok) String.format("%02d:%02d-er alarm screen open korechi boss.", hour, minute)
                else "Clock app alarm support korche na boss."
            )
        }
        if (hasAnyTerm(lower, "timer", "টাইমার")) {
            val mins = Regex("(\\d+)").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 5
            val ok = openTimerIntent(mins * 60, "ZEE AI Timer")
            return PhoneCommandOutcome.Executed(if (ok) "$mins minute-er timer set korechi boss." else "Timer start kora jayni boss.")
        }

        return PhoneCommandOutcome.NotAPhoneCommand
    }

    private fun findAndLaunchApp(spokenInput: String): String? {
        val clean = spokenInput.trim()

        val banglaMap = mapOf(
            "ইউটিউব" to "youtube",
            "ফেসবুক" to "facebook",
            "হোয়াটসঅ্যাপ" to "whatsapp",
            "হোয়াটসঅ্যাপ" to "whatsapp",
            "মেসেঞ্জার" to "messenger",
            "ইনস্টাগ্রাম" to "instagram",
            "ক্যালকুলেটর" to "calculator",
            "ক্যামেরা" to "camera",
            "সেটিংস" to "settings",
            "গ্যালারি" to "gallery",
            "ক্রোম" to "chrome",
            "ম্যাপ" to "maps",
            "জিমেইল" to "gmail",
            "ফোন" to "phone",
            "মেসেজ" to "messages",
            "টেলিগ্রাম" to "telegram"
        )

        var targetQuery = clean
        for ((bnWord, enWord) in banglaMap) {
            if (clean.contains(bnWord)) {
                targetQuery = enWord
                break
            }
        }

        if (targetQuery == "camera" || clean.contains("ক্যামেরা") || clean.contains("camera")) {
            val camOk = runCatching {
                context.startActivity(
                    Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }.getOrDefault(false)
            if (camOk) return "Camera"
        }

        if (targetQuery == "settings" || clean.contains("সেটিংস")) {
            val setOk = runCatching {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            if (setOk) return "Settings"
        }

        for ((keyword, pkg) in knownApps) {
            if (targetQuery.contains(keyword) || clean.contains(keyword)) {
                if (launchApp(pkg)) return keyword.replaceFirstChar { it.uppercase() }
            }
        }

        // Android 11-16 <queries> CATEGORY_LAUNCHER lookup (works WITHOUT QUERY_ALL_PACKAGES)
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = runCatching { pm.queryIntentActivities(launcherIntent, 0) }.getOrDefault(emptyList())

        val stripped = clean
            .replace(Regex("""(?i)(?:open|launch|start|run|kholo|khulo|chalu|koro|chalaw|খোলো|খুলুন|চালু|করো|ওপেন|চালাও)"""), " ")
            .trim()
            .ifBlank { targetQuery }

        val matched = apps.firstOrNull { info ->
            val label = info.loadLabel(pm).toString().lowercase()
            val pkg = info.activityInfo.packageName.lowercase()
            label == targetQuery || label == stripped ||
                (stripped.length > 2 && (label.contains(stripped) || clean.contains(label) || pkg.contains(stripped)))
        }

        if (matched != null) {
            val launchIntent = pm.getLaunchIntentForPackage(matched.activityInfo.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return matched.loadLabel(pm).toString()
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

    private fun getBatteryPercent(): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (level >= 0 && scale > 0) (level * 100) / scale else -1
    }
}
