package com.otakustream.core.database.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

// Opens (or creates) a Keystore-backed EncryptedSharedPreferences file — AES256, with the master key
// held in the Android Keystore. Degrades to null if the Keystore is somehow unavailable (rare OEM
// breakage) so callers fall back to in-memory rather than crashing. Shared by every secure store
// (the AniList token, the Stremio account authKey) so the create block lives in exactly one place.
fun openEncryptedPrefs(context: Context, fileName: String): SharedPreferences? = runCatching {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    EncryptedSharedPreferences.create(
        context,
        fileName,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}.getOrNull()
