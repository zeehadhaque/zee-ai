package com.zeeai.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.zeeai.companion.data.local.SecureKeyVault
import com.zeeai.companion.data.local.ZeeDatabase
import com.zeeai.companion.offline.LocalLlmInferenceEngine
import com.zeeai.companion.offline.OfflineModelStoreManager
import com.zeeai.companion.ui.ZeeNativeApp

class MainActivity : ComponentActivity() {
    private lateinit var localLlm: LocalLlmInferenceEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = ZeeDatabase.get(applicationContext)
        val vault = SecureKeyVault(applicationContext)
        val modelStore = OfflineModelStoreManager(applicationContext)
        localLlm = LocalLlmInferenceEngine(applicationContext, modelStore)

        setContent {
            ZeeNativeApp(
                database = db,
                keyVault = vault,
                modelStore = modelStore,
                localLlm = localLlm
            )
        }
    }

    override fun onDestroy() {
        localLlm.close()
        super.onDestroy()
    }
}
