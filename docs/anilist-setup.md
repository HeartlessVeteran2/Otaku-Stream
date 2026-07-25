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

## Give the build your Client ID

The client ID is supplied at build time and kept out of git, so a fork never ships someone else's
client. Pick whichever of these fits how you build — they're checked in this order:

**1. `local.properties`** (easiest for local builds; the file is already gitignored):

```properties
anilistClientId=12345
```

**2. A Gradle property** on the command line:

```bash
./gradlew :app:assembleRelease -PanilistClientId=12345
```

**3. The `ANILIST_CLIENT_ID` environment variable** — use this in CI, where it can come from a
repository secret:

```yaml
env:
  ANILIST_CLIENT_ID: ${{ secrets.ANILIST_CLIENT_ID }}
```

Rebuild and the sign-in button goes live. Supply nothing and the app still builds and runs — the
AniList screen just explains that sign-in isn't configured in this build.

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
  on the device in EncryptedSharedPreferences (AES256, with the key held in the Android Keystore)
  and excluded from cloud backup — not in the app's database.
- **The client ID is not a secret.** Implicit-grant client IDs are public by design (they're
  visible in the browser URL on every sign-in), so there's no client secret to protect in this
  flow. It's kept out of git for hygiene — so forks don't accidentally ship your client — not for
  secrecy, and it's baked into the APK as `BuildConfig.ANILIST_CLIENT_ID`.
- Tokens are long-lived (about a year). Sign out and back in to refresh.

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| Button says sign-in isn't configured | No client ID reached the build — set one of the three options above, then rebuild. |
| Browser opens but never returns to the app | Redirect URL on the AniList client doesn't exactly match `otakustream://anilist-auth`. |
| "No browser found" message | The device has no browser app installed. Install one — sign-in requires a browser; there is no manual token entry. |
| Signed in but "Link to AniList" is missing on details pages | The token didn't save — sign out and in again, and check Logcat for `TrackingManager` warnings. |
