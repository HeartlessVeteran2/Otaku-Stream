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
  ask for. None of the three acts on its own any more: a `stremio://` link fills the add-on field
  and waits for a tap, a `magnet:` link is described and confirmed before anything joins a swarm,
  and an `otakustream://anilist-auth` redirect is refused unless it carries the `state` nonce from a
  sign-in this app started. Before that nonce existed, any app or web page could hand the app an
  attacker's AniList token and silently redirect the user's watch history into the attacker's
  account.
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
  into the encrypted store and **wipes the Room row**, then runs `VACUUM`. The delete alone was not
  enough: SQLite marks the page free rather than erasing it, so the plaintext token could survive in
  the database file until something happened to reuse that page. The database is also excluded from
  backup now, which covers anyone whose backup ran before they updated.
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

The cost is honest: **traffic to those hosts can be observed and modified in transit.**

Two things are carved out of that allowance, because they are not content:

- **Credential hosts.** `anilist.co` and `strem.io` carry the AniList bearer token and the Stremio
  account password, and both now have `cleartextTrafficPermitted="false"` domain-configs. This used
  to be true only because no code happened to build an `http://` URL for them — a code-review
  guarantee. It is now enforced by the platform: such a request fails at the socket.
- **Executable code.** Scripts, Mangayomi extensions and the repository indexes that list them must
  be served over `https` (loopback excepted, so a source can still be developed locally). A script
  fetched over cleartext can be replaced in flight by anyone on the path, and the app then runs it.
  A *repo index* is worse, because it supplies the download URL for every extension in it — one
  rewritten response redirects every later install.

### Installed extensions run arbitrary code — by design

The app can install and run third-party JavaScript sources (Mozilla Rhino) and Mangayomi extensions
(QuickJS). That is the feature. Those scripts get a deliberately narrow host API (HTTP, an unpacker,
one extractor, per-source preferences) rather than general access to app internals, but **installing
an extension is a trust decision the user makes**, comparable to installing a browser extension.
Install from sources you trust.

A bug that lets an extension exceed that host API *is* a vulnerability — please report it.

How each engine is confined:

- **Rhino (scripted sources)** — scopes are built with `initSafeStandardObjects()` and every context
  carries a `ClassShutter` denying all classes, so the Java interop bridge is unreachable. Covered by
  `ScriptEngineSandboxTest`. Before this was in place the promised sandbox did not exist: a source
  could reach `java.lang.Class`, and through it the filesystem and the stored AniList token. If you
  installed a scripted source from a host you do not fully trust before this fix, treat that token as
  compromised and revoke it from your AniList account settings.
- **QuickJS (Mangayomi extensions)** — no ambient Java bridge exists; the only reachable host
  functions are the ones explicitly injected. The HTML helper is parse-only and never touches the
  network or filesystem, and the crypto helpers reach nothing but the arguments they are given and
  the platform's own randomness — no app state, no storage.

Both engines can make arbitrary HTTP requests, including to hosts on your local network. That is the
capability they exist to have, and it is the reason installing one is a trust decision.

### No code minification

Release builds set `isMinifyEnabled = false`. Extensions call app code by name via reflection, which
R8 would rename or strip, and the app isn't distributed through the Play Store. Obfuscation is not a
security boundary, so nothing here depends on it.
