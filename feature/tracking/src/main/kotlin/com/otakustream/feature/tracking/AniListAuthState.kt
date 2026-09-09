package com.otakustream.feature.tracking

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

// The OAuth `state` parameter: a nonce the app mints before sending the user to AniList and demands
// back, unchanged, on the redirect.
//
// Without one, `otakustream://anilist-auth#access_token=...` is a bare instruction that any app or
// web page on the device can issue. Nothing about the redirect proves it came from a sign-in this
// app started, so an attacker can hand the app *their* AniList token; the app stores it and from
// then on quietly writes the victim's watch history into the attacker's account, where they can
// read it. The victim sees "signed in" and no sign anything is wrong. That is the whole attack, and
// it costs one link.
//
// A nonce closes it: a redirect that doesn't carry the value this app generated for a sign-in it is
// actually waiting on is refused.
//
// Persisted rather than held in memory, because the round trip leaves the app — the browser is a
// separate process and Android is free to kill this one while the user is looking at the consent
// page. A nonce that lived in a field would be gone exactly when the real redirect came back, which
// would turn this from a security control into an intermittent sign-in failure.
@Singleton
class AniListAuthState internal constructor(
    context: Context,
    // A clock, so the expiry below can be tested without waiting out the window or sleeping.
    //
    // A secondary @Inject constructor rather than a default argument, because Dagger does not see
    // Kotlin defaults and would demand a Function0<Long> binding — and rather than a mutable
    // property, because nothing in the app should ever read a different clock. Hilt gets the
    // one-argument constructor; tests get this one.
    private val nowMs: () -> Long,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, System::currentTimeMillis)


    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Mints a nonce for a sign-in about to start, replacing any previous one. Replacing is
    // deliberate: only the most recent sign-in the user actually initiated should be able to
    // complete, so starting a second attempt invalidates the first.
    //
    // Stamped with the time, because the nonce is only meant to outlive the app's process, not the
    // sign-in. Without a stamp an abandoned attempt left a valid nonce on disk indefinitely — the
    // user taps Connect, changes their mind, and months later a `state` value that is still
    // accepted is sitting in shared preferences. It is not much of an exposure on its own: an
    // attacker who could read it could read the token too. But a credential with no expiry is worth
    // giving one when it costs a long.
    fun begin(): String {
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        prefs.edit {
            putString(KEY_PENDING, nonce)
            putLong(KEY_MINTED_AT, nowMs())
        }
        return nonce
    }

    // True only for a redirect carrying the nonce from a sign-in this app started.
    //
    // A null or blank pending value must never match a null or blank state — which a plain `==`
    // would happily do, and which is exactly the shape of a forged redirect arriving when no
    // sign-in is in progress.
    //
    // Cleared on success only. Clearing on every call would make it single-use in the wrong
    // direction: a forged redirect fired while the user is on the consent page would burn the
    // pending nonce, and the genuine redirect arriving a moment later would then be refused. That
    // hands anyone who can open a link a way to block sign-in indefinitely. Leaving a failed
    // attempt's nonce in place costs nothing — it is 256 bits of randomness that only the real
    // redirect carries.
    fun consume(state: String?): Boolean {
        val pending = prefs.getString(KEY_PENDING, null)
        if (pending.isNullOrEmpty() || state.isNullOrEmpty()) return false

        // Expiry is checked before the comparison, and clearing here does not reopen the
        // denial-of-service the comment above rules out. That argument is about a *failed* match
        // burning a nonce the real redirect still needs; this nonce is already past use, so nothing
        // that arrives later could have been accepted anyway.
        //
        // A missing stamp is treated as expired rather than as valid forever — that is a nonce
        // written by a build before this existed, and its whole sign-in is long over.
        val mintedAt = prefs.getLong(KEY_MINTED_AT, 0L)
        if (mintedAt <= 0L || nowMs() - mintedAt > VALIDITY_MS) {
            clear()
            return false
        }

        if (pending != state) return false
        // Matched, so retire it: re-opening the same redirect URL must not sign in a second time.
        clear()
        return true
    }

    private fun clear() = prefs.edit {
        remove(KEY_PENDING)
        remove(KEY_MINTED_AT)
    }

    private companion object {
        const val PREFS_NAME = "anilist_auth_state"
        const val KEY_PENDING = "pending_state"
        const val KEY_MINTED_AT = "pending_state_minted_at"

        // How long a sign-in may take. Generous — the round trip goes through a browser, a login
        // form and possibly a password manager, and Android may kill this process in the middle —
        // but finite, which is the point. Fifteen minutes is far longer than any real sign-in and
        // far shorter than "until the app is uninstalled".
        const val VALIDITY_MS = 15 * 60 * 1000L
    }
}
