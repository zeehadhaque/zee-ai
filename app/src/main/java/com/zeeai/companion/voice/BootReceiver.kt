package com.zeeai.companion.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zeeai.companion.data.local.SecureKeyVault

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON") {

            val keyVault = SecureKeyVault(context)
            val alwaysOn = keyVault.getKey("background_always_listen")
            // Only start wake word service if user explicitly enabled it
            if (alwaysOn == "true") {
                runCatching {
                    WakeWordForegroundService.start(context)
                }
            }
        }
    }
}
