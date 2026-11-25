package com.zeeai.companion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.zeeai.companion.data.local.SecureKeyVault
import com.zeeai.companion.data.local.ZeeDatabase
import com.zeeai.companion.ui.ZeeNativeApp
import com.zeeai.companion.voice.WakeWordForegroundService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = android.graphics.Color.parseColor("#02040A")
        window.navigationBarColor = android.graphics.Color.parseColor("#02040A")

        // Ensure background wake word releases the microphone hardware for the interactive UI
        runCatching { WakeWordForegroundService.stop(applicationContext) }

        val openMicOnLaunch = intent?.getBooleanExtra("open_hud_mic", false) == true

        val db = ZeeDatabase.get(applicationContext)
        val vault = SecureKeyVault(applicationContext)

        setContent {
            ZeeNativeApp(
                database = db,
                keyVault = vault,
                openMicOnLaunch = openMicOnLaunch
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
