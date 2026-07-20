# Architecture

Otaku Stream is a multi-module Gradle project: a thin `:app` shell over `core` (infrastructure)
and `feature` (screens) modules. Kotlin throughout, Jetpack Compose for all UI, Hilt for DI,
Room for persistence, Media3/ExoPlayer for playback.

## Module map

```
:app                      shell — navigation, theme, bottom bar, built-in source registration,
                          intent handling (ACTION_VIEW, stremio://, otakustream://anilist-auth)

:core:sources-api         the VideoSource contract + shared models (pure Kotlin/JVM).
                          Also PendingPlayback and PlaybackQueue — small process-global
                          hand-off channels between the browse UIs and the player.
:core:player              Media3 wrapper (PlayerController), gestures, PiP, background audio,
                          skip markers, equalizer, track selection, external subtitle loading
:core:database            Room database: playback progress, skip segments, scripted sources,
                          Stremio add-ons, library/watch history, AniList tracking
:core:sources-scripting   Rhino JS engine, ScriptedVideoSource, script installer
:core:sources-stremio     Stremio add-on client: manifest/catalog/meta/stream parsing,
                          installer, bootstrapper, StremioVideoSource

:sources:example          built-in reference source (public-domain sample videos)

:feature:sources          SourceRegistry, home rails, catalog/search, media details,
                          manage-sources + Stremio add-on management UI
:feature:library          watchlist / history / continue-watching + on-device (MediaStore) library
:feature:tracking         AniList GraphQL client, OAuth sign-in, link dialog, auto-sync
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

### Unified watch history

`PlayerController.play()` is the single choke point, so history is recorded there. Catalog flows
record their own richer entry (title, episode, cover) and stash with `historyHandled = true`;
direct plays get recorded by the controller itself under the sentinel
`DIRECT_PLAY_SOURCE_ID = -1L`, with a display title derived from the content resolver or url.
The Library screen routes sentinel entries straight back into the player and everything else to
the details page.

### Sources

`SourceRegistry` holds all `VideoSource` implementations: built-ins registered at startup by
`:app`, plus dynamic ones (scripted + Stremio) registered by their bootstrappers from the
database. Registration dedupes by stable id, so multiple screens can safely bootstrap. Catalog
and home fan out across sources in parallel; one failing source degrades gracefully instead of
blanking the screen.

### Auto-play next

`PlaybackQueue` holds a resolver closure ("what comes after the current episode?") that the
details screen arms when starting catalog playback and the player invokes on completion. Direct
plays clear the queue so a finished local file never chains into a stale catalog episode.

## Persistence

Single Room database (`:core:database`), schema version 6, with exported schemas under
`core/database/schemas/` as the migration baseline. Policy: **destructive migration only on
downgrade** — upgrades must ship explicit `Migration`s from v6 onward. Adding an index or column
means a schema bump; small additive queries that read existing columns don't.

## Conventions

- **No shared UI module.** Small composables (`CoverImage`, `EmptyState`) are duplicated per
  feature module rather than creating a `core:ui` dependency magnet.
- **Pickers over typing.** Anything on-device is selected through system pickers/MediaStore;
  free-text input is reserved for inherently remote URLs.
- **Friendly strings, hardcoded.** User-facing copy avoids jargon but intentionally lives in
  code, not `strings.xml` — localization, if it happens, will be done wholesale.
- **CancellationException is always rethrown** from `runCatching` blocks inside coroutines.
- CI (`.github/workflows/ci.yml`) runs `lintDebug`, `:app:assembleDebug`, and
  `:app:assembleRelease` on every PR; lint is blocking.
