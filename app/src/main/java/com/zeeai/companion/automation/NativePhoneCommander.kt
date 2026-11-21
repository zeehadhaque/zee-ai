package com.zeeai.companion.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
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

    private val knownApps = mapOf(
        "whatsapp" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "instagram" to "com.instagram.android",
        "chrome" to "com.android.chrome",
        "camera" to "com.android.camera2"
    )

    fun tryHandleCommand(rawInput: String): PhoneCommandOutcome {
        val lower = rawInput.lowercase().trim()

        // 1. Open installed Android apps ("Zee, WhatsApp kholo" / "open YouTube")
        if (Regex("\\b(open|kholo|খোলো|chalu kor|চালু কর)\\b").containsMatchIn(lower)) {
            for ((keyword, pkg) in knownApps) {
                if (lower.contains(keyword)) {
                    val launched = launchApp(pkg)
                    return PhoneCommandOutcome.Executed(
                        if (launched) "${keyword.replaceFirstChar { it.uppercase() }} open kore diyechi."
                        else "$keyword app-ta ei phone-e pawa jayni."
                    )
                }
            }
        }

        // 2. Read current screen via ZeeAccessibilityService
        if (Regex("(screen|স্ক্রিন).*(read|poro|পড়ো|পড়ো|dekho)").containsMatchIn(lower)) {
            val service = ZeeAccessibilityService.getInstance()
                ?: return PhoneCommandOutcome.Executed(
                    "Screen porte hole Models & Control tab theke 'ZEE AI Phone Control' Accessibility ON kore dao."
                )
            val text = service.readActiveScreenText(1200)
            return PhoneCommandOutcome.Executed(
                if (text.isBlank()) "Screen-e kono readable text pawa jayni."
                else "Screen-e ja dekha jachhe:\n$text"
            )
        }

        // 3. Global Navigation (Back / Home / Scroll)
        if (Regex("\\b(go back|back jao|পিছনে যাও)\\b").containsMatchIn(lower)) {
            val ok = ZeeAccessibilityService.getInstance()?.navigate("back") ?: false
            return PhoneCommandOutcome.Executed(if (ok) "Back gelam." else "Accessibility permission ON nei.")
        }
        if (Regex("\\b(go home|home screen|হোমে যাও)\\b").containsMatchIn(lower)) {
            val ok = ZeeAccessibilityService.getInstance()?.navigate("home") ?: false
            return PhoneCommandOutcome.Executed(if (ok) "Home screen-e niye gelam." else "Accessibility permission ON nei.")
        }
        if (Regex("\\b(scroll down|niche scroll|নিচে নামাও)\\b").containsMatchIn(lower)) {
            val ok = ZeeAccessibilityService.getInstance()?.scrollActiveContainer(true) ?: false
            return PhoneCommandOutcome.Executed(if (ok) "Niche scroll korechi." else "Scroll kora jayni (Accessibility check koro).")
        }

        // 4. Set Alarm ("alarm set kor 7:30")
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

        // 5. Set Timer ("timer 5 minute")
        if (Regex("\\b(timer|টাইমার)\\b").containsMatchIn(lower)) {
            val mins = Regex("(\\d+)").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 5
            val ok = openTimerIntent(mins * 60, "ZEE AI Timer")
            return PhoneCommandOutcome.Executed(
                if (ok) "$mins minute-er timer set korechi."
                else "Timer start kora jayni."
            )
        }

        // 6. WhatsApp Message Draft (Requires Confirmation Gate)
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

        // 7. Phone Call Draft (Requires Confirmation Gate — uses ACTION_DIAL safely)
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
