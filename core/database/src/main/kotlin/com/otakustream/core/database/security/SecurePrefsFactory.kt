package com.otakustream.core.database.security

import android.content.SharedPreferences

// Opens a secure preferences file by name.
//
// A seam, and a narrow one. The credential stores used to call openEncryptedPrefs themselves, which
// made the disk half of their clear/save ordering untestable: the Android Keystore is unavailable
// under Robolectric, openEncryptedPrefs returns null there, and both stores degrade to in-memory —
// so every assertion about what ends up on disk passed vacuously. A test supplies ordinary
// SharedPreferences instead, which Robolectric does provide, and the ordering the stores care about
// is the same either way.
//
// Not a general-purpose abstraction: it exists so those orderings can be written down, and the real
// binding does exactly what the stores did before.
fun interface SecurePrefsFactory {
    // Null when the Keystore is unavailable, which callers must treat as "degrade to in-memory"
    // rather than as an error — see openEncryptedPrefs.
    fun open(fileName: String): SharedPreferences?
}
