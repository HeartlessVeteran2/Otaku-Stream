# Architecture

Otaku Stream is a multi-module Gradle project: a thin `:app` shell over `core` (infrastructure)
and `feature` (screens) modules. Kotlin throughout, Jetpack Compose for all UI, Hilt for DI,
Room for persistence, Media3/ExoPlayer for playback.

## Module map

```
:app                      shell — navigation, theme, bottom bar, Settings, splash, crash reporter,
                          intent handling (ACTION_VIEW, stremio://, otakustream://anilist-auth)

:core:common              tiny pure-Kotlin helpers shared everywhere (runCatchingCancellable,
                          JSON extensions)
:core:ui                  the handful of composables used by every feature (CoverImage, EmptyState)
:core:network             the app-wide OkHttpClient: timeouts, desktop UA, cookie jar, disk cache,
                          and the WebView-based Cloudflare challenge interceptor

:core:sources-api         the VideoSource contract + shared models (pure Kotlin/JVM).
                          Also PendingPlayback, PlaybackQueue, PlaybackCompletion and UiMessages —
                          small process-global hand-off channels between the browse UIs, the
                          player, and the snackbar host. Deliberately coroutine-free, so these are
                          plain callback registries rather than flows.
:core:player              Media3 wrapper (PlayerController), gestures, PiP, background audio,
                          Chromecast, skip markers (manual + AniSkip), equalizer, subtitle styling,
                          track selection, external subtitle loading
:core:database            Room database: playback progress, skip segments, scripted sources,
                          Stremio add-ons, Mangayomi extensions, library/watch history, AniList
                          tracking. Also the Keystore-backed encrypted stores for the AniList and
                          Stremio credentials.
:core:sources-scripting   Rhino JS engine, ScriptedVideoSource, script installer
:core:sources-stremio     Stremio add-on client: manifest/catalog/meta/stream parsing, installer,
                          bootstrapper, account sync, StremioVideoSource
:core:sources-mangayomi   QuickJS runtime for AnymeX/Mangayomi extensions: the MProvider host API,
                          crypto/deobfuscation helpers, video extractors, per-source preferences,
                          repo client, installer, bootstrapper

:sources:example          built-in reference source (public-domain sample videos), debug builds only

:feature:sources          SourceRegistry, home rails, Browse/search, media details, the Sources hub
                          and all install/manage UI, AniList discovery + detail + watch bridge
:feature:library          watchlist status buckets / history / continue-watching + on-device
                          (MediaStore) library
:feature:tracking         AniList GraphQL client, OAuth sign-in, link dialog, status mirror
```

Dependency direction is strictly `app → feature → core`; `core` modules never depend on
`feature` modules, and `:core:sources-api` depends on nothing Android at all so source
implementations stay testable on the JVM.

## Key flows

### Playback pipeline

Every play path converges on `PlayerController.play(url)`:

1. A browse UI resolves an episode to a `Video` (url + headers + subtitle tracks) and stashes it
   in `PendingPlayback`, then navigates to the player route with the url.
2. `PlayerController.play()` consumes the stash — matching by url — to build the `MediaItem`
   with the right headers, subtitle configurations, and MIME hints. A play with no stash (local
   file, pasted link, "Open with") is a **direct play**.
3. Progress is persisted per url; `content://` URIs from MediaStore are stable, so resume works
   for local files too.

High-frequency player state (position, duration, the currently active skip segment) lives in its own
`StateFlow`, separate from `PlayerUiState`, and is collected only by the controls overlay. The
overlay is gated behind `controlsVisible`, so the 500 ms position ticker recomposes nothing at all
while the controls are hidden.

### Unified watch history

`PlayerController.play()` is the single choke point, so history is recorded there. Browse/catalog flows
record their own richer entry (title, episode, cover) and stash with `historyHandled = true`;
direct plays get recorded by the controller itself under the sentinel
`DIRECT_PLAY_SOURCE_ID = -1L`, with a display title derived from the content resolver or url.
The Library screen routes sentinel entries straight back into the player and everything else to
the details page.

### Sources

`SourceRegistry` holds all `VideoSource` implementations: built-ins bound by `:app` (debug only),
plus dynamic ones — scripted, Stremio, Mangayomi — registered by their bootstrappers from the
database. Registration dedupes by stable id, so multiple screens can safely bootstrap.

`SourceBootstrapper` runs the rehydrate exactly once per process and hands every caller the same
`Deferred`. Inside it, the three source kinds load **concurrently** under a `supervisorScope`, each
in its own failure boundary — a broken extension of one kind can't cancel the others or blank the
registry. Browse and home then fan out across the registered sources in parallel, and one failing
source degrades gracefully instead of blanking the screen.

### Extension pipeline (AnymeX/Mangayomi)

1. `MangayomiRepoClient` fetches an extension index from a repo URL.
2. Installing one stores its script text and metadata in `mangayomi_sources`, validating it by
   bringing the engine up once.
3. On later cold starts `MangayomiBootstrapper` rebuilds sources from the cached text **without**
   forcing engine bringup (`forceBringup = false`) — the extension was already validated at install
   time, so the QuickJS context is created on first real use instead of during startup. Cold start
   therefore stops scaling with the number of installed extensions.
4. At runtime the extension calls into the `MProvider` host API: HTTP through the shared
   OkHttpClient, crypto/deobfuscation helpers, the common video extractors, and its own
   preferences.

### AniList status mirror

Saving a title locally and tracking it on AniList are one action, not two. `LibraryStatusMapping`
maps a local watchlist status to an AniList one, and `decideStatusMirror` decides whether to push
it — notably it **never downgrades** a `COMPLETED` or `REPEATING` AniList entry because the local
row says "watching". The mapping is pure and unit-tested (`LibraryStatusMappingTest`).

The same status/score/progress editor (`AniListListControls`) is used by both the AniList detail
screen and a source's own details screen, so editing works wherever you found the title.

### Auto-play next

`PlaybackQueue` holds a resolver closure ("what comes after the current episode?") that the
details screen arms when starting catalog playback and the player invokes on completion. Direct
plays clear the queue so a finished local file never chains into a stale catalog episode.

## Persistence

Single Room database (`:core:database`), **schema version 11**, with exported schemas under
`core/database/schemas/` as the migration baseline. Policy: **destructive migration only on
downgrade** — upgrades must ship explicit `Migration`s from v6 onward. Adding an index or column
means a schema bump; small additive queries that read existing columns don't.

`MigrationSchemaGuardTest` is a pure-JVM guard over this: it diffs consecutive committed schema
JSONs and asserts each migration's delta is exactly what its SQL does, and that every registered
migration has both its schemas committed. Add a migration → add it to that test too.

## Conventions

- **Shared UI lives in `:core:ui`.** It's kept deliberately small — a composable earns its way in
  by being needed by more than one feature module, not by looking reusable.
- **Pickers over typing.** Anything on-device is selected through system pickers/MediaStore;
  free-text input is reserved for inherently remote URLs.
- **Friendly strings, hardcoded.** User-facing copy avoids jargon but intentionally lives in
  code, not `strings.xml` — localization, if it happens, will be done wholesale.
- **One vocabulary for sources.** Add-ons, extensions, and scripts are *kinds of sources*; user
  copy says "sources" and puts the kind in supporting text.
- **`CancellationException` is always rethrown** from `runCatching` blocks inside coroutines —
  `core:common`'s `runCatchingCancellable` exists for this. Where a failure boundary should let
  genuine `Error`s through, catch `Exception` rather than `Throwable`.
- **R8 is off, on purpose.** The app isn't on the Play Store, and shrinking a codebase that
  evaluates third-party JavaScript through two different engines trades a smaller APK for a class
  of breakage that only shows up in release builds.
- **Cleartext HTTP is permitted.** Many third-party source hosts still serve plain HTTP; refusing
  it would silently break sources rather than protect anyone.
- CI (`.github/workflows/ci.yml`) runs `testDebugUnitTest`, `lintDebug`, `:app:assembleDebug`, and
  `:app:assembleRelease` on every PR; lint is blocking. `release.yml` builds and publishes the APK
  on a `v*` tag.
