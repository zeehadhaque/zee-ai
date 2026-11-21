package com.zeeai.companion.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureKeyVault(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "zee_ai_encrypted_keys",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveKey(name: String, value: String) {
        prefs.edit().putString(name, value.trim()).apply()
    }

    fun getKey(name: String): String = prefs.getString(name, "") ?: ""

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
