package com.zeeai.companion.jarvis

import android.app.Notification
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

data class CapturedNotificationItem(
    val id: String,
    val packageName: String,
    val title: String,
    val text: String,
    val subText: String,
    val timestamp: Long,
    val category: String // "email" | "message" | "other"
)

/**
 * Real-time Notification Listener for email & messaging triage without dangerous SMS permissions.
 */
class ZeeInboxNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        var isConnected: Boolean = false
            private set

        private val capturedItems = CopyOnWriteArrayList<CapturedNotificationItem>()
        private const val MAX_ITEMS = 40

        fun isEnabledInSettings(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ).orEmpty()
            return flat.contains(context.packageName)
        }

        fun getRecentEmails(): List<CapturedNotificationItem> =
            capturedItems.filter { it.category == "email" }

        fun getRecentMessages(): List<CapturedNotificationItem> =
            capturedItems.filter { it.category == "message" }

        fun getAllCaptured(): List<CapturedNotificationItem> = capturedItems.toList()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        runCatching {
            activeNotifications?.forEach { processNotification(it) }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn != null) processNotification(sbn)
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val extras = sbn.notification?.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val lowerPkg = pkg.lowercase()
        val category = when {
            lowerPkg.contains("mail") || lowerPkg.contains("outlook") ||
                lowerPkg == "com.google.android.gm" || lowerPkg.contains("yahoo") -> "email"
            lowerPkg.contains("messaging") || lowerPkg.contains("mms") ||
                lowerPkg.contains("whatsapp") || lowerPkg.contains("telegram") ||
                lowerPkg.contains("orca") || lowerPkg.contains("messenger") -> "message"
            else -> "other"
        }

        val item = CapturedNotificationItem(
            id = "${sbn.id}_${sbn.postTime}",
            packageName = pkg,
            title = title,
            text = text,
            subText = subText,
            timestamp = sbn.postTime,
            category = category
        )
        capturedItems.removeAll { it.title == title && it.text == text && it.packageName == pkg }
        capturedItems.add(0, item)
        while (capturedItems.size > MAX_ITEMS) {
            capturedItems.removeAt(capturedItems.size - 1)
        }
    }
}
