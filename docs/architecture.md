# Architecture

Otaku Stream is a multi-module Gradle project: a thin `:app` shell over `core` (infrastructure)
and `feature` (screens) modules. Kotlin throughout, Jetpack Compose for all UI, Hilt for DI,
Room for persistence, Media3/ExoPlayer for playback.

## Module map

```
:app                      shell — navigation, theme, bottom bar, Settings, splash, crash reporter,
                          intent handling (ACTION_VIEW, stremio://, otakustream://anilist-auth)

:core:common              tiny pure-Kotlin helpers available to any module that needs them
                          (runCatchingCancellable, JSON extensions)
:core:ui                  composables shared by more than one feature — currently :feature:sources
                          and :feature:library (CoverImage, EmptyState)
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
:core:torrent             libtorrent4j session, the torrent:// scheme, piece strategy, the
                          foreground download service, the cache quota, and magnet parsing. All
                          libtorrent types stay inside this module — :core:player adapts a torrent
                          to Media3 through TorrentEngine.openFile() alone

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

Position and duration — the two fields that change twice a second for the whole length of a video —
live in their own `PlaybackProgress` `StateFlow`, separate from `PlayerUiState`, and are collected
only by the controls overlay. The overlay is gated behind `controlsVisible`, so the 500 ms ticker
recomposes nothing at all while the controls are hidden. Everything else, including
`activeSkipSegment`, stays in `PlayerUiState`, which only changes on real events.

### Torrent playback

A torrent-backed stream plays one of two ways, in this order of preference: through a Stremio
streaming server if the user has configured one, or on-device via `:core:torrent`. Before either
existed, a stream carrying only an `infoHash` was silently dropped — the list looked populated and
played nothing.

**The URL is `torrent://<infoHash>/<fileIdx>`, and that choice is load-bearing.** The obvious
alternative is a localhost HTTP server, which is how most apps bridge a torrent into a player. It is
wrong here because the whole playback stack keys on the media URL string: resume position, skip
segments, the `PendingPlayback` stash, the AniList completion handler and watch history are all
looked up by url. A localhost url carries a port, and a port that changes between sessions — or after
a collision retry — silently breaks every one of those, as bugs that look nothing like torrent bugs.
A url derived purely from the torrent's own identity is stable forever, and there is no listening
socket for other apps on the device to reach.

The cost is that a tracker list can't live in the url — it varies between add-on responses, and
embedding it would make the identity unstable. Trackers travel out-of-band on `Video.trackers`
through `PendingPlayback`, the same channel headers already use, and `TorrentTrackerStore` remembers
the last set per infoHash so a replay from watch history isn't left with DHT alone.

Reads come from the partially-downloaded file on disk rather than through libtorrent's async
`readPiece`: libtorrent writes and verifies each piece to the save path as it completes, so once
`havePiece(i)` is true the bytes are on disk and correct. That makes a torrent read an ordinary file
read guarded by a wait. `PieceStrategy` — pure, unit-tested — decides what to ask for: sequential
with a priority window ahead of the read head, deadlines on the next few pieces, and the container's
first and last pieces up front, because MP4's `moov` atom is often trailing and nothing can report a
duration or seek without it.

Lifecycle is **reference-counted on open readers**, not driven off player events: the engine can't see
player lifecycle, auto-play chains one playback into the next, and Media3 may open several
`DataSource`s for one item. The count reaching one starts a `dataSync` foreground service; reaching
zero removes the torrent, stops the session and sweeps the cache back under quota. Readers also carry
a generation, so a reader retired by the notification's Stop action can't decrement a later
playback's count. **Nothing seeds** — upload is capped to what tit-for-tat needs, and the torrent
leaves the session when playback ends.

Only the arm64 native library is bundled, so the feature degrades to "unavailable, here's why" on a
32-bit device rather than crashing. Subtitle files carried inside the torrent are fetched alongside
the video and offered as tracks once complete. Casting a `torrent://` url is refused with an
explanation: the receiver fetches the url itself, and only this device can resolve one.

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

`SourceBootstrapper` runs the rehydrate once and hands every concurrent and later caller the same
`Deferred`, so it isn't repeated per screen. A failed or cancelled attempt is deliberately *not*
cached — the `Deferred` is cleared so the next caller retries, rather than every future caller
awaiting the same dead result until the process restarts. Inside it, the three source kinds load **concurrently** under a `supervisorScope`, each
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
   OkHttpClient, crypto/deobfuscation helpers, a `p.a.c.k.e.r` unpacker with a stream extractor
   built on it, and its own preferences. This is a subset of Mangayomi's full host surface, so an
   extension calling something unimplemented fails at that call rather than at install.

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
- **Cleartext HTTP is permitted**, and that is a real tradeoff rather than a free one: traffic to
  plain-HTTP source hosts can be intercepted and tampered with. Many third-party hosts still serve
  only HTTP, so refusing it would silently break those sources; the app accepts that exposure for
  source traffic instead. Nothing sensitive travels over it — credentials go to AniList and Stremio
  over HTTPS.
- CI (`.github/workflows/ci.yml`) runs `testDebugUnitTest`, `lintDebug`, `:app:assembleDebug`, and
  `:app:assembleRelease` on every PR; lint is blocking. `release.yml` builds and publishes the APK
  on a `v*` tag.
