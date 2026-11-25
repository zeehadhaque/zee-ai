package com.zeeai.companion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.zeeai.companion.data.local.SecureKeyVault
import com.zeeai.companion.data.local.ZeeDatabase
import com.zeeai.companion.offline.LocalLlmInferenceEngine
import com.zeeai.companion.offline.OfflineModelStoreManager
import com.zeeai.companion.ui.ZeeNativeApp
import com.zeeai.companion.voice.WakeWordForegroundService

class MainActivity : ComponentActivity() {
    private lateinit var localLlm: LocalLlmInferenceEngine

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
        val modelStore = OfflineModelStoreManager(applicationContext)
        localLlm = LocalLlmInferenceEngine(applicationContext, modelStore)

        setContent {
            ZeeNativeApp(
                database = db,
                keyVault = vault,
                modelStore = modelStore,
                localLlm = localLlm,
                openMicOnLaunch = openMicOnLaunch
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        localLlm.close()
        super.onDestroy()
    }
}
