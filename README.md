<div align="center">

# Otaku Stream

**A VLC-style video player and Stremio-style streaming hub for Android, with an anime focus.**

[![CI](https://github.com/HeartlessVeteran2/Otaku-Stream/actions/workflows/ci.yml/badge.svg)](https://github.com/HeartlessVeteran2/Otaku-Stream/actions/workflows/ci.yml)
![Platform](https://img.shields.io/badge/platform-Android%207.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
![Media3](https://img.shields.io/badge/player-Media3%20ExoPlayer-red)

</div>

Otaku Stream plays whatever you point it at — files on your device, direct links, or streams
resolved through Stremio add-ons — and remembers where you were across all of them. One player,
one library, one watch history.

## Features

### 🎬 Player
- **Gesture-driven controls** — drag to seek, swipe for volume (right) and brightness (left),
  double-tap to skip, long-press for a temporary speed boost. A one-time overlay teaches the
  gestures on first playback.
- **External subtitles** — load an `.srt`/`.ass`/`.ssa`/`.vtt` file mid-playback from the track
  sheet, or drop one next to a local video (`Show.mkv` + `Show.srt`, or `Show.en.srt`) and it's
  picked up automatically, VLC-style.
- **Track selection** for audio, subtitles, and quality; variable playback speed; resizeable video
  (fit / zoom / stretch); an audio equalizer with presets; and a nerd-stats overlay.
- **Resume everywhere** — playback position is persisted per video and auto-cleared on completion.
- **Picture-in-Picture** sized to the video's real aspect ratio, and **background audio** via
  `MediaSessionService` with notification controls.
- **Skip intro/outro** — mark segments once, skip them with a button on every rewatch.
- **Auto-play next episode**, toggleable per show.
- Broad format support: HLS, DASH, RTSP, progressive — plus `stremio://` deep links and
  Android's "Open with" for local files.

### 🧩 Sources & add-ons
- **Stremio add-ons** — install any Stremio add-on by URL or browse the official catalog in-app.
  Catalogs, metadata, streams, and add-on subtitles all flow through.
- **Content-forward home** — the Play tab opens to Continue Watching, Popular, and Latest rails
  aggregated across your add-ons, Stremio-style.
- **Scripted sources** — add a source at runtime by pasting a `.js` URL. Scripts run sandboxed in
  an embedded Rhino interpreter. See [docs/scripted-sources.md](docs/scripted-sources.md).
- **Built-in sources** compile in as Gradle modules against the `VideoSource` contract
  (`:sources:example` ships as a reference).

### 📚 Library & tracking
- **On-device library** — browse and play the videos already on your phone, no file-manager typing.
- **Unified watch history** — every play (local file, pasted link, catalog episode) lands in one
  History and Continue Watching, and reopens to the right place when tapped.
- **Watched-state** — episode lists show checkmarks on what you've started and an
  "N of M watched" count per season.
- **AniList sync** — sign in with AniList in Settings, link a show once, and progress updates
  automatically as you watch. Requires a one-time developer setup — see
  [docs/anilist-setup.md](docs/anilist-setup.md).

## Getting started

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

The debug APK lands in `app/build/outputs/apk/debug/`. CI runs `lintDebug`, `assembleDebug`, and
`assembleRelease` on every PR.

## Documentation

| Doc | What it covers |
| --- | --- |
| [docs/anilist-setup.md](docs/anilist-setup.md) | Registering the AniList API client that powers in-app sign-in |
| [docs/architecture.md](docs/architecture.md) | Module map, layering rules, and how playback/history/sources fit together |
| [docs/scripted-sources.md](docs/scripted-sources.md) | Writing and hosting a runtime-installable JavaScript source |

## Tech stack

Kotlin · Jetpack Compose · Material 3 · Hilt · Room · Media3 (ExoPlayer) · Coil · Mozilla Rhino

## Acknowledgments

Inspired by [AnymeX](https://github.com/RyanYuuki/AnymeX), [VLC](https://www.videolan.org/vlc/),
and [Stremio](https://www.stremio.com/). Not affiliated with any of them, or with AniList.

Otaku Stream is a player and library app: it ships no content and no third-party add-ons. What you
play with it is up to you.
