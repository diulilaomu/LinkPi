package com.example.link_pi.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Creates an EncryptedSharedPreferences instance with AES-256-GCM encryption.
 * Falls back to plain SharedPreferences if encryption is unavailable.
 *
 * @param context Application context
 * @param name Base name for the preferences file
 * @return Pair of (SharedPreferences, isPlainFallback)
 */
fun createEncryptedPrefs(context: Context, name: String): Pair<SharedPreferences, Boolean> {
    return try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            "${name}_encrypted",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        Pair(prefs, false)
    } catch (e: Exception) {
        android.util.Log.e("SecurePrefs", "Encrypted storage unavailable for '$name'", e)
        Pair(context.getSharedPreferences("${name}_fallback", Context.MODE_PRIVATE), true)
    }
}
