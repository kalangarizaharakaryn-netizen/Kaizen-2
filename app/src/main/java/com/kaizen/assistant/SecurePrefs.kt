package com.kaizen.assistant

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the user's own Anthropic API key on-device, encrypted.
 *
 * This app has no backend and no baked-in key — you paste your own key in
 * (from console.anthropic.com) via the gear icon, and it never leaves your
 * phone except in the direct HTTPS call to Anthropic's API.
 */
object SecurePrefs {
    private const val FILE_NAME = "kaizen_secure_prefs"
    private const val KEY_API_KEY = "anthropic_api_key"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getApiKey(context: Context): String? =
        prefs(context).getString(KEY_API_KEY, null)

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API_KEY, key).apply()
    }
}
