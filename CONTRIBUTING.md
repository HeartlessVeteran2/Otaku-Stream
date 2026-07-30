# Contributing to Otaku Stream

Thanks for looking. This is a Kotlin/Compose Android app built as a multi-module Gradle project;
everything below is the actual workflow the repo uses, not aspiration.

By contributing you agree your changes ship under the project's licence
(**GPL-3.0-or-later** — see [LICENSE](LICENSE)).

## Getting a build running

```bash
git clone https://github.com/HeartlessVeteran2/Otaku-Stream.git
cd Otaku-Stream
./gradlew :app:assembleDebug
```

| Requirement | Version |
| --- | --- |
| JDK | 17 |
| Android SDK | compileSdk 35 |
| Min Android version | 7.0 (API 24) |

The APK lands in `app/build/outputs/apk/debug/`. Debug builds bundle a small example source backed
by public-domain clips, so you can exercise the catalog and playback paths without installing
anything; release builds omit it.

No emulator? Most of the codebase is testable on the JVM — see the verification command below.

### AniList sign-in (optional)

AniList features need an API client id. The app reads it from `BuildConfig.ANILIST_CLIENT_ID`, and
the build accepts it three ways:

1. `local.properties` (gitignored — the normal choice for local work):
   ```properties
   anilistClientId=12345
   ```
2. `./gradlew :app:assembleDebug -PanilistClientId=12345`
3. The `ANILIST_CLIENT_ID` environment variable (this is what CI uses).

Without one, the app builds and runs fine with AniList sign-in disabled. **Never commit a real
client id** — it's a public value by design, but it still doesn't belong in git. See
[docs/anilist-setup.md](docs/anilist-setup.md) for registering a client.

## Before you open a PR

Run the same four checks CI does. Lint is blocking, so treat a lint failure as a build failure:

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug :app:assembleRelease
```

Touching Room entities? The schema is exported to `core/database/schemas/`, and
`MigrationSchemaGuardTest` asserts every hand-written migration matches Room's generated schema. If
you change an entity you must bump the version, write the migration, export the schema, and extend
that test — all in the same commit. A mismatch there only surfaces as a crash on a real user's
device.

## Code conventions

[docs/architecture.md](docs/architecture.md) is the reference — module map, layering rules, and the
conventions section at the end. The short version:

- **Match the surrounding code.** Comment density, naming, and idiom are fairly consistent; new code
  should be indistinguishable from what's around it.
- **Comments explain *why*, not *what*.** The repo leans on this heavily. If a line looks odd but is
  deliberate, say why it's deliberate — the next person will otherwise "fix" it.
- **Always rethrow `CancellationException`.** Catch `Exception`, not `Throwable`, so `Error`s
  propagate. `runCatching` swallows cancellation, so it needs an explicit rethrow.
- **Respect module boundaries.** `:core:*` must not depend on `:feature:*`. Anything shared between
  features belongs in `:core:ui` or `:core:common`.
- **No new dependency without a reason** that survives being written down in the PR.

## Pull requests

- Branch off `main`; don't commit to `main` directly.
- One concern per PR. If you find an unrelated bug, file it or send it separately.
- Explain *why* in the body, not just what changed — the diff already says what.
- Draft PRs are fine and encouraged while CI runs.
- Automated reviewers (CodeFactor, cubic, Qodo, Sourcery) comment on PRs. They find real problems
  often enough to be worth reading, and are wrong often enough that you should push back with a
  reason rather than complying by reflex.

## Reporting bugs

Include your Android version, the app version (Settings → About), and what you were doing. The app
has a built-in crash reporter — a crash lands you on a screen with a copyable stack trace, which is
far more useful than a description.

Security issues go through [SECURITY.md](SECURITY.md) instead, not a public issue.
