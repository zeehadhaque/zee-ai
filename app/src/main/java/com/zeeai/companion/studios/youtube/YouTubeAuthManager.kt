package com.zeeai.companion.studios.youtube

import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.zeeai.companion.data.local.SecureKeyVault

sealed class YouTubeAuthOutcome {
    data class Authorized(val accessToken: String, val hasMonetaryScope: Boolean) : YouTubeAuthOutcome()
    data class NeedsConsent(val intentSenderRequest: IntentSenderRequest) : YouTubeAuthOutcome()
    data class Error(val message: String) : YouTubeAuthOutcome()
}

class YouTubeAuthManager(
    private val context: Context,
    private val keyVault: SecureKeyVault
) {
    private val authClient = Identity.getAuthorizationClient(context)

    private val baseScopes = listOf(
        Scope("https://www.googleapis.com/auth/youtube"),
        Scope("https://www.googleapis.com/auth/youtube.upload"),
        Scope("https://www.googleapis.com/auth/youtube.force-ssl"),
        Scope("https://www.googleapis.com/auth/yt-analytics.readonly")
    )

    private val monetaryScope =
        Scope("https://www.googleapis.com/auth/yt-analytics-monetary.readonly")

    fun getSavedAccessToken(): String = keyVault.getKey(KEY_ACCESS_TOKEN)

    fun hasSavedMonetaryScope(): Boolean = keyVault.getKey(KEY_MONETARY_SCOPE) == "true"

    fun requestAuthorization(
        includeMonetary: Boolean = false,
        onResult: (YouTubeAuthOutcome) -> Unit
    ) {
        val scopes = if (includeMonetary) baseScopes + monetaryScope else baseScopes
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(scopes)
            .build()

        authClient.authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent != null) {
                        val senderReq = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        onResult(YouTubeAuthOutcome.NeedsConsent(senderReq))
                    } else {
                        onResult(YouTubeAuthOutcome.Error("Google OAuth resolution intent was null."))
                    }
                } else {
                    val token = result.accessToken.orEmpty()
                    if (token.isNotBlank()) {
                        val grantedMonetary = result.grantedScopes.contains(monetaryScope.scopeUri)
                        saveSession(token, grantedMonetary)
                        onResult(YouTubeAuthOutcome.Authorized(token, grantedMonetary))
                    } else {
                        onResult(YouTubeAuthOutcome.Error("Authorization succeeded but returned an empty token."))
                    }
                }
            }
            .addOnFailureListener { e ->
                onResult(
                    YouTubeAuthOutcome.Error(
                        e.message ?: "YouTube OAuth failed. Verify Android OAuth Client ID & SHA-1 in Google Cloud Console."
                    )
                )
            }
    }

    fun handleConsentResult(data: Intent?): YouTubeAuthOutcome {
        return try {
            val result = authClient.getAuthorizationResultFromIntent(data)
            val token = result.accessToken.orEmpty()
            if (token.isNotBlank()) {
                val grantedMonetary = result.grantedScopes.contains(monetaryScope.scopeUri)
                saveSession(token, grantedMonetary)
                YouTubeAuthOutcome.Authorized(token, grantedMonetary)
            } else {
                YouTubeAuthOutcome.Error("Consent completed without an access token.")
            }
        } catch (e: Exception) {
            YouTubeAuthOutcome.Error(e.message ?: "OAuth consent cancelled or failed.")
        }
    }

    fun disconnect() {
        keyVault.saveKey(KEY_ACCESS_TOKEN, "")
        keyVault.saveKey(KEY_MONETARY_SCOPE, "false")
    }

    private fun saveSession(token: String, hasMonetary: Boolean) {
        keyVault.saveKey(KEY_ACCESS_TOKEN, token)
        keyVault.saveKey(KEY_MONETARY_SCOPE, hasMonetary.toString())
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "yt_native_access_token"
        private const val KEY_MONETARY_SCOPE = "yt_native_monetary_scope"
    }
}
