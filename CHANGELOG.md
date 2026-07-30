# Changelog

All notable changes to Otaku Stream are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[semantic versioning](https://semver.org/). `versionCode` is derived from `versionName` as
`MAJOR * 10000 + MINOR * 100 + PATCH` — see
[docs/building-and-releasing.md](docs/building-and-releasing.md).

## [Unreleased]

### Added

- **Season-aware AniList tracking** (#82, closes #9). AniList models each season of a show as its own
  media entry, so a title can now hold one tracker link per season, and watch progress is pushed to
  the entry for the season that actually played. Additive by design: a whole-series link (season `0`)
  remains the fallback, so single-season shows and every link made before this behave exactly as
  before. Database schema 12.
- **User-supplied add-on lists, with provenance labels** (#80, closes #10). Point the add-on browser
  at any list that uses the standard Stremio collection format, and see whether each add-on came from
  the Official, Community, or your Custom list — the three differ in how vetted they are, so that
  distinction is now visible rather than implied.
- **Subtitles aggregated across every installed add-on** (#79, closes #12). Previously only the
  add-on you were browsing was asked for subtitles, which meant a subtitle-only add-on such as
  OpenSubtitles would install successfully and then never be queried. Tracks from other providers are
  labelled with their source so several "English" entries are distinguishable.
- Licensing and project documentation: `LICENSE` (GPL-3.0-or-later, with a Google Cast linking
  exception), `CONTRIBUTING.md`, `SECURITY.md`, `docs/building-and-releasing.md`, and this changelog.

### Fixed

- A Stremio special (season 0) no longer advances the whole-series AniList entry. Specials carry
  ordinary positive episode numbers, so watching one was pushing that number at the series (#82).
- Switching seasons mid-request can no longer land a stale AniList result on the newly selected
  season's editor, and the link row no longer briefly shows the previous season's link (#82).
- Removed four redundant parenthesis pairs flagged by CodeFactor (#78).

## [1.0.0]

First complete release — the point at which the app became a coherent product rather than a set of
features. Not yet tagged.

### Player

Media3/ExoPlayer playback with resume-from-position, unified watch history, and auto-play next.
Subtitle support (embedded, sidecar auto-detect, mid-playback loading) with full styling control —
size, outline, colour, background, margin. Track selection, variable speed with a remembered default,
volume boost, audio equalizer with presets, resize modes, Picture-in-Picture, background audio via a
media session, and a stats overlay. Gesture controls with an on-screen HUD, seek thumbnails, manual
skip markers, and AniSkip-powered automatic intro/outro skipping with timeline highlights. Chromecast
support. Decoder fallback for awkward files, and an in-app crash reporter with a copyable stack trace.

### Sources

Four ways to get content, all installed by the user at runtime — the app ships none:

- **Stremio add-ons** — catalogs, streams, richer metadata, subtitles, `stremio://` install links,
  enable/disable/reorder, per-catalog toggles, filters, and a browsable add-on directory with
  one-tap install. Catalog-less stream add-ons (Torrentio and similar) contribute streams to any
  title, surfaced through a stream picker.
- **Mangayomi/AnymeX extensions** — a QuickJS runtime with a host API covering HTTP, crypto,
  deobfuscation, video extractors, and per-source preferences, plus a repository browser.
- **JavaScript sources** — a Rhino-backed runtime for hand-written, runtime-installable sources.
- **On-device video** — a MediaStore-backed local library.

Plus a source picker that scopes search to one source, results labelled by origin, a WebView
Cloudflare bypass for gated sources, and deliberate cleartext-HTTP support because many real source
hosts don't offer TLS (see [SECURITY.md](SECURITY.md)).

### Library and tracking

A library with watch-status buckets, saving straight from the catalog grid, and Continue Watching
rails on the home tab. Full AniList integration: in-app OAuth sign-in, discovery rails (Trending,
This Season, Popular) that work signed out, a detail screen with your-list controls for
status/score/progress, cross-source "watch from AniList" with remembered mappings, and forward-only
two-way progress sync that never downgrades a completed entry. Local library status mirrors up to
AniList when a title is linked. Optional Stremio account sync pulls and pushes your library.

### Security

The AniList access token is held in Keystore-backed `EncryptedSharedPreferences` and excluded from
cloud backup and device transfer, with a one-time migration off the earlier plaintext storage. The
AniList client id is supplied at build time and never committed.

### Foundation

Multi-module Gradle build (`:app`, six `:core:*` modules, three `:feature:*` modules) with Hilt
throughout. Room persistence with hand-written migrations, exported schemas, and a guard test that
asserts every migration matches Room's generated schema. Material 3 theme, adaptive launcher icon,
splash screen. Cold-start and playback-hot-path performance work, HTTP and AniList response caching.
CI running unit tests, blocking lint, and both debug and release builds on every PR, plus a
tag-triggered release workflow that publishes a signed APK to GitHub Releases.

[Unreleased]: https://github.com/HeartlessVeteran2/Otaku-Stream/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/HeartlessVeteran2/Otaku-Stream/releases/tag/v1.0.0
