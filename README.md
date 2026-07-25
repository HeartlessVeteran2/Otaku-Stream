<div align="center">

<img src="docs/assets/logo.png" width="160" alt="Otaku Stream">

# Otaku Stream

**A VLC-style video player and Stremio-style streaming hub for Android, with an anime focus.**

[![CI](https://github.com/HeartlessVeteran2/Otaku-Stream/actions/workflows/ci.yml/badge.svg)](https://github.com/HeartlessVeteran2/Otaku-Stream/actions/workflows/ci.yml)
![Platform](https://img.shields.io/badge/platform-Android%207.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
![Media3](https://img.shields.io/badge/player-Media3%20ExoPlayer-red)

</div>

Otaku Stream plays whatever you point it at — files on your device, direct links, or streams
resolved through the sources you install — and remembers where you were across all of them. One
player, one library, one watch history.

## Features

### 🎬 Player
- **Gesture-driven controls** — drag to seek, swipe for volume (right) and brightness (left),
  double-tap to skip, long-press for a temporary speed boost. A one-time overlay teaches the
  gestures on first playback.
- **External subtitles** — load an `.srt`/`.ass`/`.ssa`/`.vtt` file mid-playback from the track
  sheet, or drop one next to a local video (`Show.mkv` + `Show.srt`, or `Show.en.srt`) and it's
  picked up automatically, VLC-style. Size, colour, and background are all adjustable.
- **Track selection** for audio, subtitles, and quality; variable playback speed; resizeable video
  (fit / zoom / stretch); an audio equalizer with presets and volume boost; and a nerd-stats
  overlay for codec, bitrate, and dropped frames.
- **Skip intro/outro** — segments come from [AniSkip](https://aniskip.com) automatically for
  matched anime, or mark them yourself once and skip on every rewatch. Auto-skip is optional.
- **Resume everywhere** — playback position is persisted per video and auto-cleared on completion.
- **Cast to a TV** — Chromecast support via the Cast button in the player.
- **Picture-in-Picture** sized to the video's real aspect ratio, and **background audio** via
  `MediaSessionService` with notification controls.
- **Auto-play next episode**, toggleable.
- Broad format support: HLS, DASH, RTSP, progressive — plus `stremio://` deep links and
  Android's "Open with" for local files.

### 🧩 Sources
Everything installable lives under one roof: **Settings → Sources**.

- **Stremio add-ons** — install any add-on by URL, or browse a built-in directory. Catalogs,
  metadata, streams, and add-on subtitles all flow through. Enable, disable, and reorder them.
- **Anime extensions** — install extensions from AnymeX/Mangayomi repositories, which run in an
  embedded QuickJS engine with the host API they expect (HTTP, crypto and deobfuscation helpers,
  common video extractors, per-extension preferences).
- **A curated source directory** for one-tap installs.
- **Custom sources** — paste a `.js` URL and it becomes a full source at runtime, sandboxed in an
  embedded Rhino interpreter. See [docs/scripted-sources.md](docs/scripted-sources.md).
- **Cloudflare challenges** are solved in a hidden WebView when a host demands it, so a source
  that returns "Just a moment…" still works. Optional, and off-switchable in Settings.
- **Content-forward home** — the Play tab opens to Continue Watching plus discovery rails, and
  Browse searches every installed source in parallel; one failing source degrades instead of
  blanking the screen.

### 📚 Library & tracking
- **Watchlist with status buckets** — Watching / Plan to watch / Completed, set from a title's
  page or straight from a poster in Browse.
- **On-device library** — browse and play the videos already on your phone, no file-manager typing.
- **Unified watch history** — every play (local file, pasted link, catalog episode) lands in one
  History and Continue Watching, and reopens to the right place when tapped.
- **Watched state** — episode lists show checkmarks on what you've started and an
  "N of M watched" count per season.
- **AniList** — sign in and get discovery rails from AniList (trending, this season, all-time
  popular), edit status/score/progress from either an AniList title or one of your own sources, and
  have progress pushed automatically as you watch. Your local watchlist status mirrors up to
  AniList without ever downgrading a Completed entry. Requires a one-time developer setup —
  see [docs/anilist-setup.md](docs/anilist-setup.md).
- **Stremio account sync** — sign in to pull your Stremio library for reference and push your local
  saves up to it.

### 🛟 When things go wrong
A crash shows an in-app report with the stack trace and a copy button, rather than the system
"app keeps stopping" dialog — so a bug in a source or an extension is something you can actually
report.

## Install

Grab the latest APK from the [**Releases**](https://github.com/HeartlessVeteran2/Otaku-Stream/releases)
page and open it on your phone. Android will ask you to allow installing apps from outside the Play
Store the first time — that's expected; Otaku Stream isn't distributed there.

Requires Android 7.0 (API 24) or newer.

**A fresh install ships no sources.** That's deliberate — the app is a player and a library, not a
content catalog. The Play and Browse tabs will point you at **Settings → Sources** to add the first
one.

## Building it yourself

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

The debug APK lands in `app/build/outputs/apk/debug/`. Debug builds also include a small example
source backed by public-domain clips, so the catalog and playback paths are exercisable without
installing anything; release builds don't ship it.

CI runs `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `assembleRelease` on every PR; lint
is blocking.

To publish a release, push a tag matching the app's `versionName` (`v1.0.0`): the release workflow
builds the APK and attaches it to a GitHub Release. Signing keys and the AniList client id come
from repository secrets — without them the build still succeeds, using the debug key (the release
notes say so) and with AniList sign-in disabled.

## Documentation

| Doc | What it covers |
| --- | --- |
| [docs/anilist-setup.md](docs/anilist-setup.md) | Registering the AniList API client that powers in-app sign-in |
| [docs/architecture.md](docs/architecture.md) | Module map, layering rules, and how playback/history/sources fit together |
| [docs/scripted-sources.md](docs/scripted-sources.md) | Writing and hosting a runtime-installable JavaScript source |

## Tech stack

Kotlin · Jetpack Compose · Material 3 · Hilt · Room · Media3 (ExoPlayer) · Cast · Coil · OkHttp ·
Mozilla Rhino · QuickJS

## Acknowledgments

Inspired by [AnymeX](https://github.com/RyanYuuki/AnymeX), [Mangayomi](https://github.com/kodjodevf/mangayomi),
[VLC](https://www.videolan.org/vlc/), and [Stremio](https://www.stremio.com/). Not affiliated with
any of them, or with AniList.

Otaku Stream is a player and library app: it ships no content, no add-ons, and no extensions. What
you play with it is up to you.
