# Enabling R8 / minification for release builds

R8 shrinks and obfuscates the release APK (smaller download, a little faster, harder to reverse).
It's currently **off** because the app runs untrusted JavaScript source extensions through two JS
engines (QuickJS for Mangayomi/AnymeX, Rhino for scripted sources), and those engines call the
app's Kotlin **host-API bridge** methods *by name via reflection*. If R8 renames or removes those
methods, every installed source breaks at runtime — and that can't be caught by the compiler or by
CI, only on a device.

Everything needed to turn it on is already in place; you just flip the switch and run one on-device
smoke test.

## What's already prepared

`app/proguard-rules.pro` keeps the reflectively-reached surface:

- the JS engines themselves (`com.whl.quickjs.**`, `org.mozilla.javascript.**`),
- the libraries they reach (`org.json.**`, `org.jsoup.**`, Media3, Room),
- and **the app's own bridge classes** the scripts call across the JS↔native boundary:
  `com.otakustream.core.sources.scripting.**`, `com.otakustream.core.sources.mangayomi.runtime.**`,
  and `com.otakustream.core.sources.mangayomi.model.**`.

Those app-bridge keeps are the ones that make enabling R8 safe.

## How to enable it

1. In `app/build.gradle.kts`, in the `release { }` block, change:
   ```kotlin
   isMinifyEnabled = false
   ```
   to
   ```kotlin
   isMinifyEnabled = true
   // optional — also strips unused resources:
   isShrinkResources = true
   ```
2. Build the signed release APK:
   ```
   ./gradlew :app:assembleRelease
   ```
   A green build proves the R8 configuration is **valid** (no missing classes, no rule errors). It
   does **not** prove the JS sources still work — that's the next step.

## The smoke test (required before trusting a minified build)

Install the release APK on a device and exercise a real source end-to-end:

1. Install a **Mangayomi/AnymeX extension** (Settings → the sources screen → add from a repo) and a
   **scripted source**.
2. Browse each source's catalog — results must load.
3. Open a title, resolve an episode, and **play it** — the stream must resolve.
4. If a source is now broken (empty catalog, "no stream", a crash logged from the JS bridge), R8
   stripped or renamed something the script reaches.

## If a source breaks

- Get the release mapping (`app/build/outputs/mapping/release/mapping.txt`) and the usage list
  (add `-printusage build/r8-usage.txt` to `proguard-rules.pro`, rebuild) and look for a bridge
  method or model class that was removed/renamed.
- Add a targeted keep to `app/proguard-rules.pro`, e.g.:
  ```
  -keep class com.otakustream.core.sources.<the.broken.package>.** { *; }
  ```
  Prefer narrow keeps over `-keep class com.otakustream.** { *; }` (that would negate most of R8's
  benefit).
- Rebuild and re-run the smoke test.

## Rolling back

Set `isMinifyEnabled = false` again — no other change is needed; the keep rules are harmless when
minification is off.
