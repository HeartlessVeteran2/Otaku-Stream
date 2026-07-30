# Security Policy

## Reporting a vulnerability

**Please don't open a public issue for a security problem.** Use GitHub's private reporting instead:
the **Security** tab → **Report a vulnerability**, which opens a private advisory visible only to the
maintainers.

Helpful things to include: what an attacker can actually do, the steps to reproduce it, the app
version (Settings → About), and your Android version.

This is a hobby project maintained by one person, so please don't expect a same-day reply. Fixes ship
in the next release; there is no backport channel — only the latest release is supported.

## Scope

Otaku Stream is a **player and library app**. It ships no content, no add-ons, and no extensions:
sources are installed by the user at runtime. That shapes what counts as a vulnerability here.

**In scope**

- Leaking the user's AniList access token or Stremio credentials.
- A source, add-on, or extension escaping its intended limits — reading app-private files it
  shouldn't, exfiltrating credentials, or reaching app internals beyond the documented host API.
- A crafted add-on manifest, stream response, or extension payload causing memory corruption or
  arbitrary code execution.
- A malicious deep link (`stremio://`, `otakustream://`, `magnet:`) doing something the user didn't
  ask for.
- Anything that lets one installed source read or tamper with another's data.

**Out of scope** — known and accepted, documented below: cleartext HTTP to source hosts, the fact
that installed JavaScript extensions run arbitrary code by design, and the absence of code
minification.

## Current posture

Being upfront about the design decisions a reviewer will notice, and why they are what they are.

### Credentials

The AniList access token is a bearer credential, so it gets the strongest storage the platform
offers:

- Stored in **Keystore-backed `EncryptedSharedPreferences` (AES256)** — `otaku_secure_prefs` — never
  in the Room database.
- **Excluded from cloud backup and device-to-device transfer** for both API 31+
  (`data_extraction_rules.xml`) and API 30 and below (`backup_rules.xml`). The encryption key lives
  in the Android Keystore and never leaves the device, so a restored copy elsewhere couldn't be
  decrypted anyway — the exclusion means no bearer credential leaves the device at all.
- An earlier version stored it as plaintext in Room. On first read the app migrates any legacy token
  into the encrypted store and **wipes the Room row**, so no plaintext copy is left behind.
- The AniList **client id** is a public value by design, but it is still kept out of git: it is
  supplied via `local.properties`, `-PanilistClientId`, or the `ANILIST_CLIENT_ID` environment
  variable, and reaches the app through `BuildConfig`.

### Cleartext HTTP is deliberately permitted

`network_security_config.xml` sets `cleartextTrafficPermitted="true"` app-wide. This is a real
tradeoff, made knowingly:

Anime source mirrors, self-hosted Stremio add-ons, and the video/subtitle URLs they return are
frequently plain HTTP. `targetSdk` 28+ blocks cleartext by default, which made those sources fail
silently — the app looked broken rather than blocked. Permitting cleartext is what makes real sources
work, and TLS is still used wherever a source offers it.

The cost is honest: **traffic to those hosts can be observed and modified in transit.** What that
does *not* include is credentials — AniList and Stremio account traffic goes to HTTPS endpoints.

### Installed extensions run arbitrary code — by design

The app can install and run third-party JavaScript sources (Mozilla Rhino) and Mangayomi extensions
(QuickJS). That is the feature. Those scripts get a deliberately narrow host API (HTTP, an unpacker,
one extractor, per-source preferences) rather than general access to app internals, but **installing
an extension is a trust decision the user makes**, comparable to installing a browser extension.
Install from sources you trust.

A bug that lets an extension exceed that host API *is* a vulnerability — please report it.

### No code minification

Release builds set `isMinifyEnabled = false`. Extensions call app code by name via reflection, which
R8 would rename or strip, and the app isn't distributed through the Play Store. Obfuscation is not a
security boundary, so nothing here depends on it.
