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
import android.view.KeyEvent
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
        "wa" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "yt" to "com.google.android.youtube",
        "facebook" to "com.facebook.katana",
        "fb" to "com.facebook.katana",
        "messenger" to "com.facebook.orca",
        "instagram" to "com.instagram.android",
        "insta" to "com.instagram.android",
        "chrome" to "com.android.chrome",
        "google" to "com.google.android.googlequicksearchbox",
        "calculator" to "com.google.android.calculator",
        "telegram" to "org.telegram.messenger",
        "tiktok" to "com.zhiliaoapp.musically",
        "maps" to "com.google.android.apps.maps",
        "gmail" to "com.google.android.gm",
        "playstore" to "com.android.vending",
        "play store" to "com.android.vending",
        "photos" to "com.google.android.apps.photos",
        "gallery" to "com.google.android.apps.photos",
        "phone" to "com.google.android.dialer",
        "messages" to "com.google.android.apps.messaging",
        "sms" to "com.google.android.apps.messaging"
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
            ?: return "অডিও ম্যানেজার পাওয়া যায়নি বস।"
        return try {
            when (action) {
                "up" -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    "ভলিউম বাড়িয়ে দিয়েছি বস।"
                }
                "down" -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    "ভলিউম কমিয়ে দিয়েছি বস।"
                }
                "mute" -> {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                    "ভলিউম মিউট করে দিয়েছি বস।"
                }
                "max" -> {
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
                    "ভলিউম ফুল করে দিয়েছি বস।"
                }
                else -> "ভলিউম কমান্ড বুঝতে পারিনি বস।"
            }
        } catch (_: Exception) {
            "ভলিউম পরিবর্তন করা যায়নি বস।"
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
            return PhoneCommandOutcome.Executed("এখন বাজে $now, বস।")
        }

        if (hasAnyTerm(lower, "battery", "ব্যাটারি", "চার্জ", "charge") &&
            !hasAnyTerm(lower, "settings", "সেটিংস") &&
            (lower == "battery" || hasAnyTerm(lower, "status", "koto", "percentage", "percent", "charge", "level", "কত", "চার্জ", "ব্যাটারি", "চেক"))
        ) {
            val pct = getBatteryPercent()
            return PhoneCommandOutcome.Executed(
                if (pct >= 0) "ফোনে এখন $pct শতাংশ চার্জ আছে বস।" else "ব্যাটারি লেভেল চেক করা যায়নি বস।"
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
                    if (isTorchOn) "ফ্ল্যাশলাইট চালু করে দিয়েছি বস।" else "ফ্ল্যাশলাইট বন্ধ করে দিয়েছি বস।"
                )
            } else {
                PhoneCommandOutcome.Executed("ফ্ল্যাশলাইট অন করা যায়নি বস।")
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
            return PhoneCommandOutcome.Executed(if (ok) "ওয়াইফাই কানেকশন প্যানেল খুলে দিয়েছি বস।" else "ওয়াইফাই সেটিংস খোলা যায়নি বস।")
        }
        if (hasAnyTerm(lower, "bluetooth", "ব্লুটুথ")) {
            val ok = runCatching {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            return PhoneCommandOutcome.Executed(if (ok) "ব্লুটুথ সেটিংস খুলে দিয়েছি বস।" else "ব্লুটুথ সেটিংস খোলা যায়নি বস।")
        }
        if (hasAnyTerm(lower, "brightness", "ব্রাইটনেস", "display settings")) {
            val ok = runCatching {
                context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            return PhoneCommandOutcome.Executed(if (ok) "ডিসপ্লে ব্রাইটনেস সেটিংস খুলে দিয়েছি বস।" else "ডিসপ্লে সেটিংস খোলা যায়নি বস।")
        }
        if (lower == "settings" || lower == "open settings" || hasAnyTerm(lower, "সেটিংস")) {
            val ok = runCatching {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            return PhoneCommandOutcome.Executed(if (ok) "ফোন সেটিংস খুলে দিয়েছি বস।" else "সেটিংস খোলা যায়নি বস।")
        }

        // 2c. Media Playback Controls (Works natively on YouTube / Spotify / Reels)
        if (hasAnyTerm(lower, "skip ad", "skip", "অ্যাড স্কিপ", "স্কিপ")) {
            val ok = runAccessibilityAction("SKIP_AD") { it.skipAdIfPresent() }
            if (ok == true) return PhoneCommandOutcome.Executed("অ্যাড স্কিপ করে দিয়েছি বস।")
        }
        if (hasAnyTerm(lower, "next video", "next reel", "next short", "porer video", "পরের ভিডিও", "arekta")) {
            val fg = ZeeAccessibilityService.currentForegroundPackage()
            val handled = if (fg.contains("youtube") || fg.contains("musically") || fg.contains("instagram") || fg.contains("facebook")) {
                runAccessibilityAction("SCROLL", "DOWN") { it.swipeDirection("DOWN") } ?: false
            } else {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            }
            return PhoneCommandOutcome.Executed(if (handled) "পরের ভিডিও প্লে করেছি বস।" else "পরবর্তী আইটেমে যাওয়া যায়নি বস।")
        }
        if (hasAnyTerm(lower, "previous video", "ager video", "আগের ভিডিও")) {
            val fg = ZeeAccessibilityService.currentForegroundPackage()
            val handled = if (fg.contains("youtube") || fg.contains("musically") || fg.contains("instagram") || fg.contains("facebook")) {
                runAccessibilityAction("SCROLL", "UP") { it.swipeDirection("UP") } ?: false
            } else {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
            return PhoneCommandOutcome.Executed(if (handled) "আগের ভিডিওতে গেলাম বস।" else "আগের ভিডিওতে যাওয়া যায়নি বস।")
        }
        if (lower in listOf("pause", "pause video", "stop video", "stop song", "play", "resume", "resume video", "পজ", "থামাও", "প্লে করো")) {
            val keySent = dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            val accClicked = ZeeAccessibilityService.getInstance()?.let {
                it.clickNodeContainingText("pause") || it.clickNodeContainingText("play")
            } ?: false
            return PhoneCommandOutcome.Executed(if (keySent || accClicked) "মিডিয়া প্লে পজ করে দিয়েছি বস।" else "মিডিয়া কন্ট্রোল করা যায়নি বস।")
        }

        // 2d. YouTube Direct Search & Auto-Play ("yt e natok search koro", "youtube play lo-fi", "ইউটিউবে গান চালাও")
        if (hasAnyTerm(lower, "youtube", "yt", "ইউটিউব", "ইউটিউবে")) {
            val wantsPlay = hasAnyTerm(lower, "play", "chalao", "bajao", "shonao", "dekhao", "চালাও", "দেখাও", "শোনাও", "বাজাও")
            val wantsSearch = wantsPlay || hasAnyTerm(lower, "search", "find", "khujo", "khuj", "dekho", "সার্চ", "খোঁজো", "খুঁজো")
            val ytQuery = text
                .replace(Regex("""(?i)\b(?:youtube|yt|on|in|for|search|find|play|open|launch|kholo|chalao|bajao|shonao|dekhao|koro|karo|dao)\b"""), " ")
                .replace(Regex("""(?:^|\s)(?:ইউটিউবে|ইউটিউব|সার্চ|খোঁজো|খুঁজো|চালাও|দেখাও|শোনাও|করো|দাও|একটা|গান|ভিডিও)(?=\s|$)"""), " ")
                .replace(Regex("""(?i)\b(?:e|te)\b"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()

            if (wantsSearch && ytQuery.isNotBlank()) {
                val ok = openYouTubeSearch(ytQuery)
                if (ok && wantsPlay && ZeeAccessibilityService.isServiceEnabled(context)) {
                    ZeeAccessibilityService.sendControlCommand(context, "YT_PLAY_FIRST", "1900")
                }
                return PhoneCommandOutcome.Executed(
                    if (ok) "ইউটিউবে '$ytQuery' সার্চ করে দিয়েছি বস।" else "ইউটিউব খোলা যায়নি বস।"
                )
            }
        }

        // 2e. Google Search & Browser URLs ("google weather Dhaka", "google e search koro...", "গুগল করো...")
        if (hasAnyTerm(lower, "google", "গুগল", "গুগলে")) {
            val googleQuery = text
                .replace(Regex("""(?i)\b(?:google|chrome|on|in|for|search|find|open|kholo|koro|karo|dao)\b"""), " ")
                .replace(Regex("""(?:^|\s)(?:গুগলে|গুগল|ক্রোম|সার্চ|খোঁজো|খুঁজো|করো|খোলো|দাও)(?=\s|$)"""), " ")
                .replace(Regex("""(?i)\b(?:e|te)\b"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (googleQuery.isNotBlank()) {
                val ok = openGoogleSearch(googleQuery)
                return PhoneCommandOutcome.Executed(
                    if (ok) "গুগলে '$googleQuery' সার্চ করে দিয়েছি বস।" else "ব্রাউজার খোলা যায়নি বস।"
                )
            }
        }

        // Direct domain / URL launch ("open facebook.com", "google.com")
        Regex("""^(?:open|go to|launch|khulo|kholo|খোলো)?\s*([a-zA-Z0-9-]+\.(?:com|org|net|io|bd|in|tv|ai)(?:/\S*)?)$""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val domainUrl = "https://" + m.groupValues[1].trim()
            val ok = runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(domainUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            if (ok) return PhoneCommandOutcome.Executed("${m.groupValues[1]} ব্রাউজারে খুলে দিয়েছি বস।")
        }

        // 3. Post Story OR Feed Post ("facebook e post koro ...", "post ...", "story post")
        if (hasAnyTerm(lower, "story", "স্টোরি") &&
            hasAnyTerm(lower, "post", "dao", "chalu", "koro", "শেয়ার", "দাও", "পোস্ট")
        ) {
            val isInstagram = lower.contains("instagram") || lower.contains("insta")
            val isFacebook = lower.contains("facebook") || lower.contains("fb")

            return if (isInstagram) {
                launchApp("com.instagram.android")
                ZeeAccessibilityService.sendControlCommand(context, "OPEN_STORY")
                PhoneCommandOutcome.Executed("ইনস্টাগ্রাম স্টোরি খুলে দিয়েছি বস।")
            } else if (isFacebook) {
                launchApp("com.facebook.katana")
                ZeeAccessibilityService.sendControlCommand(context, "OPEN_STORY")
                PhoneCommandOutcome.Executed("ফেসবুক স্টোরি খুলে দিয়েছি বস।")
            } else {
                val launched = launchApp("com.instagram.android") || launchApp("com.facebook.katana")
                ZeeAccessibilityService.sendControlCommand(context, "OPEN_STORY")
                PhoneCommandOutcome.Executed(
                    if (launched) "স্টোরি ক্রিয়েটর খুলে দিয়েছি বস।"
                    else "স্টোরি পোস্ট করার জন্য ইনস্টাগ্রাম বা ফেসবুক অ্যাপ পাওয়া যায়নি বস।"
                )
            }
        }

        if (hasAnyTerm(lower, "post", "পোস্ট") &&
            (lower.contains("facebook") || lower.contains("fb") || hasAnyTerm(lower, "koro", "kor", "this", "post", "করো", "পোস্ট"))
        ) {
            val postBody = text
                .replace(Regex("""(?i)\b(?:facebook|fb|on|in|e|te|post|create|make|koro|kor|this|please)\b"""), " ")
                .replace(Regex("""(?:^|\s)(?:ফেসবুকে|ফেসবুক|পোস্ট|করো|দাও)(?=\s|$)"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()

            val fg = ZeeAccessibilityService.currentForegroundPackage()
            return if (lower.contains("facebook") || lower.contains("fb") || fg.isBlank() || fg == context.packageName) {
                val ok = launchApp("com.facebook.katana")
                if (ok && ZeeAccessibilityService.isServiceEnabled(context)) {
                    ZeeAccessibilityService.sendControlCommand(context, "FB_POST", postBody)
                }
                PhoneCommandOutcome.Executed(
                    if (ok && postBody.isNotBlank()) "ফেসবুকে '$postBody' পোস্ট রেডি করে দিয়েছি বস।"
                    else if (ok) "ফেসবুক পোস্ট বক্স খুলে দিয়েছি বস।"
                    else "ফেসবুক অ্যাপ পাওয়া যায়নি বস।"
                )
            } else {
                if (postBody.isNotBlank()) {
                    val ok = runAccessibilityAction("TYPE_AND_POST", postBody) { it.autoTypeAndSend(postBody, isPostOrComment = true) }
                        ?: return PhoneCommandOutcome.Executed("পোস্ট করতে অ্যাক্সেসিবিলিটি চালু করে দিন বস।")
                    PhoneCommandOutcome.Executed(if (ok) "পোস্ট লিখে পাবলিশ করে দিয়েছি বস।" else "অ্যাক্টিভ পোস্ট বক্স পাওয়া যায়নি বস।")
                } else {
                    runAccessibilityAction("CLICK_POST") { it.autoClickPostAfterDelay(50L); true }
                        ?: return PhoneCommandOutcome.Executed("পোস্ট বাটনে চাপতে অ্যাক্সেসিবিলিটি চালু করে দিন বস।")
                    PhoneCommandOutcome.Executed("পোস্ট বাটন প্রেস করে দিয়েছি বস।")
                }
            }
        }

        // 4. Autonomous Messaging: WhatsApp (Fixed Bangla/Banglish "[Name] ke [message]" extraction)
        if (lower.contains("whatsapp") || hasAnyTerm(lower, "হোয়াটসঅ্যাপ", "হোয়াটসঅ্যাপ")) {
            val digits = Regex("(\\+?\\d{7,15})").find(rawInput)?.groupValues?.get(1)
            val afterWhatsapp = text
                .replace(Regex("""(?i)\b(?:open|send|message|msg|text|chat|on|in|to|whatsapp|wa)\b"""), " ")
                .replace(Regex("""(?:হোয়াটসঅ্যাপে|হোয়াটসঅ্যাপে|হোয়াটসঅ্যাপ|হোয়াটসঅ্যাপ)"""), " ")
                .replace(Regex("""^\s*(?:e|te|এ)\s+""", RegexOption.IGNORE_CASE), "")
                .trim()

            // Check pattern: "<Name> ke [bolo|likho|pathao|message] <Payload>"
            val targetAndMsg = Regex(
                """^(.+?)\s+(?:ke|কে)\s+(?:message|msg|text|bolo|bol|likho|pathao|পাঠাও|বলো|বল|লিখো|যে)?\s*(.+)$""",
                RegexOption.IGNORE_CASE
            ).find(text.substringAfter("whatsapp", "").trim().ifBlank { afterWhatsapp })

            val targetName = targetAndMsg?.groupValues?.get(1)
                ?.replace(Regex("""(?i)^(?:e|te|to|for)\s+"""), "")
                ?.trim()
                .orEmpty()
            val extractedBody = targetAndMsg?.groupValues?.get(2)
                ?.replace(Regex("""(?i)^(?:message|msg|pathao|bolo|likho|পাঠাও|বলো|লিখো)\s+"""), "")
                ?.trim()
                ?: afterWhatsapp
                    .replace(digits ?: "", "")
                    .replace(Regex("""(?i)\b(?:pathao|bolo|likho)\b|পাঠাও|বলো|লিখো"""), "")
                    .trim()

            if (digits != null) {
                val cleanDigits = digits.replace(Regex("[^0-9]"), "")
                val payload = extractedBody.ifBlank { "Hello!" }
                val encoded = URLEncoder.encode(payload, "UTF-8")
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$cleanDigits?text=$encoded")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                ZeeAccessibilityService.sendControlCommand(context, "CLICK_SEND", "1000")
                return PhoneCommandOutcome.Executed("হোয়াটসঅ্যাপে $digits নম্বরে '$payload' পাঠিয়ে দিচ্ছি বস।")
            } else if (targetName.isNotBlank()) {
                val phone = findContactPhoneNumber(targetName)
                val payload = extractedBody.ifBlank { "Assalamu Alaikum!" }
                if (phone != null) {
                    val cleanDigits = phone.replace(Regex("[^0-9]"), "")
                    val encoded = URLEncoder.encode(payload, "UTF-8")
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$cleanDigits?text=$encoded")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    ZeeAccessibilityService.sendControlCommand(context, "CLICK_SEND", "1000")
                    return PhoneCommandOutcome.Executed("হোয়াটসঅ্যাপে $targetName কে মেসেজ পাঠিয়ে দিচ্ছি বস।")
                } else {
                    launchApp("com.whatsapp")
                    ZeeAccessibilityService.sendControlCommand(context, "SEARCH_IN_APP", targetName)
                    return PhoneCommandOutcome.Executed("হোয়াটসঅ্যাপ খুলে '$targetName' খুঁজে দিচ্ছি বস।")
                }
            } else if (extractedBody.isNotBlank() && ZeeAccessibilityService.currentForegroundPackage() == "com.whatsapp") {
                val ok = runAccessibilityAction("TYPE_AND_SEND", extractedBody) { it.autoTypeAndSend(extractedBody) }
                    ?: return PhoneCommandOutcome.Executed("হোয়াটসঅ্যাপে মেসেজ পাঠাতে অ্যাক্সেসিবিলিটি চালু করে দিন বস।")
                return PhoneCommandOutcome.Executed(if (ok) "হোয়াটসঅ্যাপে '$extractedBody' পাঠিয়ে দিয়েছি বস।" else "চ্যাট বক্স খোলা রেখে আবার বলুন বস।")
            } else if (hasAnyTerm(lower, "message", "msg", "pathao", "chat", "পাঠাও")) {
                launchApp("com.whatsapp")
                return PhoneCommandOutcome.Executed("হোয়াটসঅ্যাপ খুলে দিয়েছি বস, চ্যাট সিলেক্ট করলে মেসেজ পাঠাতে পারব।")
            }
        }

        // 5. Autonomous Messaging: Messenger / SMS / Active Chat
        if ((lower.contains("messenger") || lower.contains("facebook")) &&
            hasAnyTerm(lower, "message", "msg", "pathao", "chat", "পাঠাও", "inbox", "text")
        ) {
            launchApp("com.facebook.orca")
            val textToExtract = text
                .replace(Regex("""(?i)\b(?:open|messenger|facebook|message|msg|chat|pathao|inbox|text|on|in|e|te)\b|পাঠাও|মেসেঞ্জার"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (textToExtract.isNotBlank() && ZeeAccessibilityService.isServiceEnabled(context)) {
                ZeeAccessibilityService.sendControlCommand(context, "TYPE_AND_SEND", textToExtract)
            }
            return PhoneCommandOutcome.Executed("মেসেঞ্জার ইনবক্স খুলে দিয়েছি বস।")
        }

        if (hasAnyTerm(lower, "sms", "text message", "message", "msg", "মেসেজ") &&
            hasAnyTerm(lower, "pathao", "send", "bolo", "likho", "text", "পাঠাও", "লেখো")
        ) {
            val digits = Regex("(\\+?\\d{6,15})").find(rawInput)?.groupValues?.get(1)
            val cleanedSms = text
                .replace(Regex("""(?i)\b(?:sms|text|message|msg|send|pathao|koro|dao)\b|মেসেজ|পাঠাও"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (digits != null) {
                val body = cleanedSms.replace(digits, "").trim().ifBlank { "Hello!" }
                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$digits")).apply {
                    putExtra("sms_body", body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                ZeeAccessibilityService.sendControlCommand(context, "CLICK_SEND", "900")
                return PhoneCommandOutcome.Executed("$digits নম্বরে এসএমএস রেডি করেছি বস।")
            }
            val fg = ZeeAccessibilityService.currentForegroundPackage()
            if (fg.isNotBlank() && fg != context.packageName && cleanedSms.isNotBlank()) {
                val ok = runAccessibilityAction("TYPE_AND_SEND", cleanedSms) { it.autoTypeAndSend(cleanedSms) }
                    ?: return PhoneCommandOutcome.Executed("চ্যাটে মেসেজ পাঠাতে অ্যাক্সেসিবিলিটি চালু করে দিন বস।")
                return PhoneCommandOutcome.Executed(if (ok) "অ্যাক্টিভ চ্যাটে '$cleanedSms' পাঠিয়ে দিয়েছি বস।" else "অ্যাক্টিভ চ্যাট ইনপুট বক্স পাওয়া যায়নি বস।")
            }
        }

        // 6. Active Screen Auto-Type, Chat-Send, Find/Search, Click & Scroll
        Regex("""^(?:click|tap|press|চাপো|ক্লিক\s*করো|ট্যাপ\s*করো)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val targetLabel = m.groupValues[1].trim()
            val ok = runAccessibilityAction("CLICK_TEXT", targetLabel) { it.clickNodeContainingText(targetLabel) }
                ?: return PhoneCommandOutcome.Executed("স্ক্রিনে ক্লিক করতে অ্যাক্সেসিবিলিটি চালু করে দিন বস।")
            return PhoneCommandOutcome.Executed(if (ok) "'$targetLabel' এ ট্যাপ করে দিয়েছি বস।" else "স্ক্রিনে '$targetLabel' খুঁজে পাওয়া যায়নি বস।")
        }

        // Instant "Send" button tap inside open chat
        if (lower in listOf("send", "send koro", "পাঠাও", "পাঠিয়ে দাও", "পাঠিয়ে দাও")) {
            runAccessibilityAction("CLICK_SEND", "50") { it.autoClickSendAfterDelay(50L); true }
                ?: return PhoneCommandOutcome.Executed("সেন্ড বাটনে চাপতে অ্যাক্সেসিবিলিটি চালু করে দিন বস।")
            return PhoneCommandOutcome.Executed("সেন্ড বাটন প্রেস করে দিয়েছি বস।")
        }

        // Type & Send ("type and send ...", "chat ...", "reply ...", "likhe pathao ...")
        Regex("""^(?:type and send|send message|chat|reply|send|pathao|msg type|message type|এই মেসেজ পাঠাও|রিপ্লাই দাও|চ্যাটে লেখো)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val toType = m.groupValues[1].trim()
            if (toType.isNotBlank()) {
                val ok = runAccessibilityAction("TYPE_AND_SEND", toType) { it.autoTypeAndSend(toType) }
                    ?: return PhoneCommandOutcome.Executed("অটো সেন্ড করতে অ্যাক্সেসিবিলিটি চালু করে দিন বস।")
                return PhoneCommandOutcome.Executed(
                    if (ok) "ইনবক্সে '$toType' লিখে পাঠিয়ে দিয়েছি বস।"
                    else "কোনো চ্যাট ইনপুট বক্স পাওয়া যায়নি বস।"
                )
            }
        }

        // Direct text typing ("type ...", "write ...", "likho ...", "text likho ...", "লিখো ...")
        Regex("""^(?:type|write|text likho|txt likho|likho|lekh|লিখো|লেখো)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val payload = m.groupValues[1].trim()
            val ok = runAccessibilityAction("TYPE_TEXT", payload) { it.typeTextIntoFocusedField(payload) }
                ?: return PhoneCommandOutcome.Executed("টাইপ করতে অ্যাক্সেসিবিলিটি চালু থাকতে হবে বস।")
            return PhoneCommandOutcome.Executed(if (ok) "'$payload' টেক্সট বক্সে লিখে দিয়েছি বস।" else "কোনো টেক্সট বক্স পাওয়া যায়নি বস।")
        }

        // In-App Find / Search OR Fallback to Google Web Search
        Regex("""^(?:search|find|khujo|khuj|সার্চ করো|সার্চ|খোঁজো|খুঁজো)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val query = m.groupValues[1]
                .replace(Regex("""(?i)\s+(?:search|find|khujo|koro|kor|সার্চ|খোঁজো|করো)$"""), "")
                .trim()
            if (query.isNotBlank()) {
                val fg = ZeeAccessibilityService.currentForegroundPackage()
                val inExternalApp = fg.isNotBlank() && fg != context.packageName && !fg.contains("launcher")
                return if (inExternalApp && ZeeAccessibilityService.isServiceEnabled(context)) {
                    val isFind = lower.startsWith("find") || lower.startsWith("khuj") || lower.startsWith("খোঁজো") || lower.startsWith("খুঁজো")
                    if (isFind) {
                        ZeeAccessibilityService.sendControlCommand(context, "FIND_OR_SEARCH", query)
                    } else {
                        ZeeAccessibilityService.sendControlCommand(context, "SEARCH_IN_APP", query)
                    }
                    PhoneCommandOutcome.Executed("অ্যাপের ভেতর '$query' খুঁজে দিচ্ছি বস।")
                } else {
                    val ok = openGoogleSearch(query)
                    PhoneCommandOutcome.Executed(if (ok) "গুগলে '$query' সার্চ করে দিয়েছি বস।" else "সার্চ ওপেন করা যায়নি বস।")
                }
            }
        }

        // Expanded Scroll Variants (Down / Up / Left / Right)
        if (hasAnyTerm(lower, "scroll up", "upore scroll", "upore jao", "upore utho", "উপরে স্ক্রল", "উপরে যাও")) {
            val ok = runAccessibilityAction("SCROLL", "UP") { it.swipeDirection("UP") }
                ?: return PhoneCommandOutcome.Executed("স্ক্রল করতে অ্যাক্সেসিবিলিটি চালু করে দিন বস।")
            return PhoneCommandOutcome.Executed(if (ok) "স্ক্রিন উপরে স্ক্রল করে দিয়েছি বস।" else "স্ক্রল করা যায়নি বস।")
        }
        if (hasAnyTerm(lower, "scroll left", "swipe left", "বামে স্ক্রল")) {
            val ok = runAccessibilityAction("SCROLL", "LEFT") { it.swipeDirection("LEFT") }
                ?: return PhoneCommandOutcome.Executed("স্ক্রল করতে অ্যাক্সেসিবিলিটি চালু করে দিন বস।")
            return PhoneCommandOutcome.Executed(if (ok) "স্ক্রিন বামে সোয়াইপ করেছি বস।" else "সোয়াইপ করা যায়নি বস।")
        }
        if (hasAnyTerm(lower, "scroll right", "swipe right", "ডানে স্ক্রল")) {
            val ok = runAccessibilityAction("SCROLL", "RIGHT") { it.swipeDirection("RIGHT") }
                ?: return PhoneCommandOutcome.Executed("স্ক্রল করতে অ্যাক্সেসিবিলিটি চালু করে দিন বস।")
            return PhoneCommandOutcome.Executed(if (ok) "স্ক্রিন ডানে সোয়াইপ করেছি বস।" else "সোয়াইপ করা যায়নি বস।")
        }
        if (lower == "scroll" || lower == "স্ক্রল" || hasAnyTerm(lower, "scroll down", "niche scroll", "niche jao", "niche namo", "scroll more", "নিচে স্ক্রল", "নিচে যাও")) {
            val ok = runAccessibilityAction("SCROLL", "DOWN") { it.swipeDirection("DOWN") }
                ?: return PhoneCommandOutcome.Executed("স্ক্রল করতে অ্যাক্সেসিবিলিটি চালু করে দিন বস।")
            return PhoneCommandOutcome.Executed(if (ok) "স্ক্রিন নিচে স্ক্রল করে দিয়েছি বস।" else "স্ক্রল করা যায়নি বস।")
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
                return PhoneCommandOutcome.Executed("$digits নম্বর ডায়ালারে ওপেন করে দিয়েছি বস।")
            } else {
                val targetName = text
                    .replace(Regex("""(?i)(?:^|\s)(?:call|dial|phone|koro|dao|ke|amake|ফোন|কল|করো|দাও|কে|ডায়াল|ডায়াল)(?=\s|$)"""), " ")
                    .trim()

                if (targetName.isNotBlank()) {
                    val hasContactsPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                    if (!hasContactsPerm) {
                        val intent = Intent(Intent.ACTION_DIAL).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        context.startActivity(intent)
                        return PhoneCommandOutcome.Executed("কন্টাক্ট পারমিশন দেওয়া নেই বস, তাই ডায়ালার খুলে দিয়েছি।")
                    }
                    val phone = findContactPhoneNumber(targetName)
                    if (phone != null) {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        ZeeAccessibilityService.sendControlCommand(context, "CLICK_CALL", "500")
                        return PhoneCommandOutcome.Executed("$targetName কে কল ডায়াল করে দিয়েছি বস।")
                    } else {
                        val intent = Intent(Intent.ACTION_DIAL).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        context.startActivity(intent)
                        return PhoneCommandOutcome.Executed("$targetName কন্টাক্টসে পাওয়া যায়নি বস, ডায়ালার খুলে দিয়েছি।")
                    }
                }
            }
        }

        // 8. Screenshot & Lock Screen
        if (hasAnyTerm(lower, "screenshot", "screen shot", "স্ক্রিনশট")) {
            val ok = runAccessibilityAction("NAV", "SCREENSHOT") { it.navigate("screenshot") }
                ?: return PhoneCommandOutcome.Executed("স্ক্রিনশট নিতে অ্যাক্সেসিবিলিটি চালু করতে হবে বস।")
            return PhoneCommandOutcome.Executed(if (ok) "স্ক্রিনশট নিয়ে নিয়েছি বস।" else "স্ক্রিনশট নেওয়া যায়নি বস।")
        }
        if (lower.contains("lock phone") || lower.contains("lock screen") || lower.contains("phone lock") || lower.contains("screen lock") || lower.contains("স্ক্রিন লক") || lower.contains("ফোন লক")) {
            val ok = runAccessibilityAction("NAV", "LOCK") { it.navigate("lock") }
                ?: return PhoneCommandOutcome.Executed("ফোন লক করতে অ্যাক্সেসিবিলিটি চালু থাকতে হবে বস।")
            return PhoneCommandOutcome.Executed(if (ok) "ফোন লক করে দিয়েছি বস।" else "ফোন লক করা যায়নি বস।")
        }

        // 9. Read Screen Text
        if (Regex("(screen|স্ক্রিন).*(read|poro|পড়ো|পড়ো|dekho|দেখো)").containsMatchIn(lower) ||
            Regex("(read|poro|পড়ো|পড়ো|dekho|দেখো).*(screen|স্ক্রিন)").containsMatchIn(lower)
        ) {
            val service = ZeeAccessibilityService.getInstance()
            if (service != null) {
                val screenText = service.readActiveScreenText(1400)
                return PhoneCommandOutcome.Executed(
                    if (screenText.isBlank()) "স্ক্রিনে পড়ার মতো কোনো টেক্সট পাওয়া যায়নি বস।"
                    else "স্ক্রিনে যা দেখা যাচ্ছে বস:\n$screenText"
                )
            }
            if (!ZeeAccessibilityService.isServiceEnabled(context)) {
                return PhoneCommandOutcome.Executed("স্ক্রিন পড়তে অ্যাক্সেসিবিলিটি চালু করে দিন বস।")
            }
            ZeeAccessibilityService.sendControlCommand(context, "SNAPSHOT_SCREEN")
            return PhoneCommandOutcome.Executed("স্ক্রিন স্ক্যান রিকোয়েস্ট পাঠিয়েছি বস।")
        }

        // 10. Navigation (Back / Home / Recents / Notifications)
        if (lower == "back" || lower.contains("go back") || lower.contains("back jao") || lower.contains("পিছনে যাও") || lower.contains("পিছে যাও") || lower.contains("ব্যাকে যাও") || lower == "ব্যাক") {
            val ok = runAccessibilityAction("NAV", "BACK") { it.navigate("back") }
                ?: return PhoneCommandOutcome.Executed("ব্যাক করতে অ্যাক্সেসিবিলিটি চালু নেই বস।")
            return PhoneCommandOutcome.Executed(if (ok) "ব্যাকে গেলাম বস।" else "ব্যাক করা যায়নি বস।")
        }
        if (lower == "home" || lower.contains("go home") || lower.contains("home screen") || lower.contains("হোমে যাও") || lower.contains("হোম স্ক্রিন") || lower == "হোম") {
            val ok = runAccessibilityAction("NAV", "HOME") { it.navigate("home") }
                ?: return PhoneCommandOutcome.Executed("হোমে যেতে অ্যাক্সেসিবিলিটি চালু নেই বস।")
            return PhoneCommandOutcome.Executed(if (ok) "হোম স্ক্রিনে নিয়ে গেলাম বস।" else "হোম স্ক্রিনে যাওয়া যায়নি বস।")
        }
        if (hasAnyTerm(lower, "recents", "recent", "recent apps", "রিসেন্ট", "রিসেন্ট অ্যাপস")) {
            val ok = runAccessibilityAction("NAV", "RECENTS") { it.navigate("recents") }
                ?: return PhoneCommandOutcome.Executed("রিসেন্ট অ্যাপস খুলতে অ্যাক্সেসিবিলিটি চালু নেই বস।")
            return PhoneCommandOutcome.Executed(if (ok) "রিসেন্ট অ্যাপস খুলে দিয়েছি বস।" else "রিসেন্ট অ্যাপস খোলা যায়নি বস।")
        }
        if (hasAnyTerm(lower, "notifications", "notification", "নোটিফিকেশন")) {
            val ok = runAccessibilityAction("NAV", "NOTIFICATIONS") { it.navigate("notifications") }
                ?: return PhoneCommandOutcome.Executed("নোটিফিকেশন খুলতে অ্যাক্সেসিবিলিটি চালু নেই বস।")
            return PhoneCommandOutcome.Executed(if (ok) "নোটিফিকেশন প্যানেল খুলে দিয়েছি বস।" else "নোটিফিকেশন খোলা যায়নি বস।")
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
                    return PhoneCommandOutcome.Executed("খুলে দিয়েছি বস, $launched ওপেন হয়েছে।")
                }
            }
        }
        if (hasAnyTerm(lower, "open", "kholo", "khulo", "chalu", "chalaw", "start", "launch", "খোলো", "খুলুন", "চালু", "চালাও", "ওপেন")) {
            val launched = findAndLaunchApp(lower)
            if (launched != null) {
                return PhoneCommandOutcome.Executed("খুলে দিয়েছি বস, $launched ওপেন হয়েছে।")
            }
        }

        // 12. Alarms & Timers
        if (hasAnyTerm(lower, "alarm", "অ্যালার্ম", "এলার্ম")) {
            val timeMatch = Regex("(\\d{1,2})[:.](\\d{2})").find(lower)
            val hour = timeMatch?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 23) ?: 7
            val minute = timeMatch?.groupValues?.get(2)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
            val ok = openAlarmIntent(hour, minute, "ZEE AI Alarm")
            return PhoneCommandOutcome.Executed(
                if (ok) String.format("%02d:%02d এর অ্যালার্ম স্ক্রিন খুলে দিয়েছি বস।", hour, minute)
                else "ক্লক অ্যাপ অ্যালার্ম সাপোর্ট করছে না বস।"
            )
        }
        if (hasAnyTerm(lower, "timer", "টাইমার")) {
            val mins = Regex("(\\d+)").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 5
            val ok = openTimerIntent(mins * 60, "ZEE AI Timer")
            return PhoneCommandOutcome.Executed(if (ok) "$mins মিনিটের টাইমার সেট করে দিয়েছি বস।" else "টাইমার চালু করা যায়নি বস।")
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
            "ম্যাসেঞ্জার" to "messenger",
            "ইনস্টাগ্রাম" to "instagram",
            "ক্যালকুলেটর" to "calculator",
            "ক্যামেরা" to "camera",
            "সেটিংস" to "settings",
            "গ্যালারি" to "gallery",
            "ক্রোম" to "chrome",
            "গুগল" to "google",
            "ম্যাপ" to "maps",
            "জিমেইল" to "gmail",
            "ফোন" to "phone",
            "মেসেজ" to "messages",
            "টেলিগ্রাম" to "telegram",
            "প্লে স্টোর" to "play store"
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

    private fun openYouTubeSearch(query: String): Boolean {
        val clean = query.trim()
        val ytSearchIntent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage("com.google.android.youtube")
            putExtra("query", clean)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(ytSearchIntent); true }.getOrDefault(false)) {
            return true
        }
        val encoded = URLEncoder.encode(clean, "UTF-8")
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }

    private fun openGoogleSearch(query: String): Boolean {
        val clean = query.trim()
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val browserUrlIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encoded")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(browserUrlIntent); true }.getOrDefault(false)) {
            return true
        }
        val webSearchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(android.app.SearchManager.QUERY, clean)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(webSearchIntent); true }.getOrDefault(false)
    }

    private fun dispatchMediaKey(keyCode: Int): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        }.getOrDefault(false)
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
