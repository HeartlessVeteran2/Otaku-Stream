# AniList sign-in setup

Otaku Stream's "Sign in with AniList" button (Settings → AniList tracking) uses OAuth so users
never have to hand-build a token. It needs a **one-time, developer-side setup**: registering an
API client with AniList and pasting its ID into the app. Until that's done, the app builds and
runs fine — the sign-in screen simply explains that sign-in isn't configured in this build.

## Register the API client

1. Sign in at [anilist.co](https://anilist.co) and open
   [Settings → Developer](https://anilist.co/settings/developer).
2. Click **Create New Client**.
3. Fill in:
   - **Name**: `Otaku Stream` (any name works — users see it on the consent screen).
   - **Redirect URL**: `otakustream://anilist-auth`
     — this must match **exactly**; it's how AniList hands the token back to the app.
4. Save, and copy the numeric **Client ID** shown for the new client.

## Put the Client ID in the app

Open
[`feature/tracking/src/main/kotlin/com/otakustream/feature/tracking/AniListAuth.kt`](../feature/tracking/src/main/kotlin/com/otakustream/feature/tracking/AniListAuth.kt)
and replace the placeholder:

```kotlin
object AniListAuth {
    const val CLIENT_ID = "12345"                          // ← your numeric client id
    const val REDIRECT_URI = "otakustream://anilist-auth"  // do not change
    ...
}
```

Rebuild (`./gradlew :app:assembleDebug`) and the sign-in button goes live.

## Verify it works

1. Install the build, open **Settings → AniList tracking**, tap **Sign in with AniList**.
2. Your browser opens AniList's consent page; approve it.
3. The browser bounces back into Otaku Stream via `otakustream://anilist-auth` and the screen
   shows you as signed in.
4. Open any show's details page — you should now see **Link to AniList**. Link it, play an
   episode, and the progress update appears on your AniList profile.

## How it works (for the curious)

- The flow is OAuth 2.0 **implicit grant**: `authorizeUrl()` opens
  `https://anilist.co/api/v2/oauth/authorize?client_id=…&response_type=token`, and AniList
  redirects to `otakustream://anilist-auth#access_token=…`. `MainActivity` catches that intent
  (the scheme is registered in the tracking module's manifest) and stores the token.
- The token is parsed from the URL **fragment** (`encodedFragment`, decoded exactly once) — it
  never touches AniList's servers as a query parameter and never leaves the device. It's stored
  locally in the app's Room database.
- **The client ID is not a secret.** Implicit-grant client IDs are public by design (they're
  visible in the browser URL on every sign-in), which is why it lives in source rather than in
  secret storage. There is no client secret in this flow.
- Tokens are long-lived (about a year). Sign out and back in to refresh.

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| Button says sign-in isn't configured | `CLIENT_ID` is still the placeholder — do the steps above. |
| Browser opens but never returns to the app | Redirect URL on the AniList client doesn't exactly match `otakustream://anilist-auth`. |
| "No browser found" message | The device has no browser app installed; install one or paste a token manually via ADB is not supported — a browser is required. |
| Signed in but "Link to AniList" is missing on details pages | The token didn't save — sign out and in again, and check Logcat for `TrackingManager` warnings. |
