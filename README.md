# Otaku Stream

A general-purpose Android video player app inspired by [AnymeX](https://github.com/RyanYuuki/AnymeX) —
a gesture-driven player, a pluggable source system with **user-addable JavaScript sources**, a local
library, and AniList progress tracking. Kotlin, Jetpack Compose, Hilt, Room, Media3.

## Features

**Player**
- Gesture controls: horizontal drag to seek, vertical drag for volume (right) / brightness (left), tap to toggle chrome
- Subtitles: external SRT/VTT sideloading + embedded HLS/DASH tracks
- Audio / subtitle / quality track selection, variable playback speed
- Resume-from-position (persisted, auto-cleared when a video is finished)
- Picture-in-Picture sized to the actual video aspect ratio
- Background audio via `MediaSessionService` with system notification controls
- Manual skip-intro/outro markers with a "Skip" button

**Sources**
- `VideoSource` contract (`:core:sources-api`): search / browse / details / episodes / video resolution
- Built-in sources compile in as Gradle modules (`:sources:example` ships as a reference)
- **Scripted sources**: add a source at runtime by pasting a `.js` URL in Settings → Manage sources.
  Scripts run in an embedded Mozilla Rhino interpreter (interpreted mode, sandboxed — the only
  capability exposed is a `httpGet(url, headersJson?)` global). See
  `sources/scripted-example/example-source.js` for the contract.

**Library & tracking**
- Watchlist, watch history, and a continue-watching rail (Library tab)
- AniList sync: paste an access token in Settings → AniList tracking, link a show from its details
  page, and watch progress updates automatically as you play episodes

## Module map

```
:app                      shell, navigation, theme, built-in source registration
:core:player              Media3 wrapper, gestures, PiP, background audio, skip markers
:core:database            Room: progress, skip segments, scripted sources, library, tracking
:core:sources-api         the VideoSource contract (pure JVM)
:core:sources-scripting   Rhino engine, ScriptedVideoSource, script installer
:sources:example          built-in reference source (public-domain sample videos)
:feature:sources          source registry, catalog/search, details, manage-sources UI
:feature:library          watchlist / history / continue-watching UI
:feature:tracking         AniList GraphQL client, token settings, link dialog
```

## Building

```
./gradlew :app:assembleDebug
```

Requires JDK 17 and an Android SDK (compileSdk 35). Min SDK 24.

## Writing a scripted source

A source script defines `SOURCE_NAME`, `SOURCE_LANG`, and six functions that each return
`JSON.stringify(...)` of the corresponding shape:

```js
var SOURCE_NAME = "My Source";
var SOURCE_LANG = "en";
function getPopular(page) { return JSON.stringify({ items: [{url, title, coverUrl}], hasNextPage: false }); }
function getLatest(page) { /* same shape as getPopular */ }
function search(query, page) { /* same shape as getPopular */ }
function getMediaDetails(mediaUrl) { return JSON.stringify({ description, genres: [], status: "COMPLETED" }); }
function getEpisodeList(mediaUrl) { return JSON.stringify([{url, name, episodeNumber}]); }
function getVideoList(episodeUrl) { return JSON.stringify([{url, quality, isM3U8, headers: {}}]); }
```

Host it anywhere (raw GitHub works), then add its URL under Settings → Manage sources.
