# Building and releasing

How to build the app locally and how a release actually gets published. For code conventions see
[architecture.md](architecture.md); for contribution workflow see [../CONTRIBUTING.md](../CONTRIBUTING.md).

## Requirements

| Requirement | Version |
| --- | --- |
| JDK | 17 |
| Android SDK | compileSdk 35 |
| Min Android version | 7.0 (API 24) |
| Gradle | via the bundled wrapper (`./gradlew`) — don't install it separately |

## Local builds

```bash
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/
```

Debug builds include an example source backed by public-domain clips so the catalog and playback
paths work without installing anything. Release builds omit it (`debugImplementation` in
`app/build.gradle.kts`).

`assembleRelease` works with no secrets configured — see [Signing](#signing) for what you get.

### The full check

The same four tasks CI runs on every PR. Lint is blocking.

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug :app:assembleRelease
```

### Build-time configuration

| Input | How to supply it | Effect if absent |
| --- | --- | --- |
| `anilistClientId` / `ANILIST_CLIENT_ID` | `local.properties`, `-PanilistClientId=…`, or env var | App builds and runs; AniList sign-in disabled |
| `RELEASE_STORE_FILE` and friends | Gradle properties or env vars | Release APK falls back to the debug key |

Both reach the app through `BuildConfig`. Neither belongs in git — `local.properties` is gitignored.

## Versioning

Set in `app/build.gradle.kts`:

- **`versionName`** is semver — `1.0.0`.
- **`versionCode`** is derived: `MAJOR * 10000 + MINOR * 100 + PATCH`, so `1.0.0` → `10000`,
  `1.2.3` → `10203`. This increases monotonically across releases, which Android requires for
  in-place upgrades, without anyone having to remember to bump a counter.

Bump both in the same commit as the release, and add the version's entry to
[../CHANGELOG.md](../CHANGELOG.md).

## Signing

`signingConfigs.release` reads four values, each from a Gradle property or the matching environment
variable:

| Value | Property / env var |
| --- | --- |
| Keystore path | `RELEASE_STORE_FILE` |
| Keystore password | `RELEASE_STORE_PASSWORD` |
| Key alias | `RELEASE_KEY_ALIAS` |
| Key password | `RELEASE_KEY_PASSWORD` |

When `RELEASE_STORE_FILE` is unset the release build **falls back to the debug key**, so
`assembleRelease` still produces an installable APK for a fork or a local test. The catch is real: a
debug-signed APK **cannot be upgraded in place** by a later properly-signed release — users have to
uninstall first. The release workflow says so in the release notes when it happens.

Keys never live in the repo. For CI they are repository secrets, with the keystore itself stored
base64-encoded in `RELEASE_KEYSTORE_BASE64`.

## Publishing a release

Releases are deliberate: **push a tag matching `versionName`, prefixed with `v`.**

```bash
# after versionName/versionCode are bumped and merged to main
git tag v1.0.1
git push origin v1.0.1
```

`.github/workflows/release.yml` then:

1. Decodes `RELEASE_KEYSTORE_BASE64` into a keystore, or warns and continues unsigned.
2. Runs `./gradlew clean :app:assembleRelease` with the signing and AniList secrets in the environment.
3. Renames the APK to `otaku-stream-<version>.apk`, so a downloaded file is self-describing instead
   of being one more `app-release.apk` in Downloads.
4. Creates a GitHub Release with auto-generated notes and attaches the APK — installable without a
   GitHub login and without expiring, which a CI artifact would.

### Dry run

Trigger the workflow manually (`workflow_dispatch`) to build and upload the APK as a **workflow
artifact without publishing a Release**. The version is labelled `dev-<short sha>`. Use this to check
a release build before committing to a tag.

### Required secrets

| Secret | Needed for |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | Proper signing (base64 of the keystore file) |
| `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` | Proper signing |
| `ANILIST_CLIENT_ID` | AniList sign-in in the published build |

All optional. A fork can tag a release with none of them configured and still get a working,
debug-signed APK.

## CI

`.github/workflows/ci.yml` runs on every PR: unit tests, lint, debug APK, release APK — as four
separate jobs, so a failure names itself rather than making you read a log. Lint is blocking.
