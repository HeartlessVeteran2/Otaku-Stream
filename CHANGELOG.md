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
- **Torrent streaming, on device** (#86, #89, #91, #94, #96). A libtorrent4j session behind a Media3
  data source: a `torrent://` URL is a stable identity, so resume position, skip markers and history
  key off it exactly as they do for an HTTP stream. Sequential piece selection for playback, a
  foreground service while a torrent is active, a storage quota, and a session that stops when the
  last reader closes. Magnet links resolve their metadata before playing, subtitles found inside the
  torrent are offered as tracks, and trackers are remembered per torrent so a resumed playback finds
  peers again.
- **Offline downloads** (#115). Save an episode for later; a Media3 download store serves it back
  through the same URL, so nothing at the call site knows or cares whether an episode is local.
  Per-row progress, pause/resume, retry for a failed download, and a Downloads tab in Library.
- **Streams pooled across every source that has the show.** The details screen used to
  ask only the source you happened to be browsing, so a title present in four add-ons still failed if
  the one you opened had nothing. Every source linked to the same AniList entry is asked, results
  merged and labelled by origin, and a source that contributes nothing says why.
- **An airing schedule and a "New episodes" rail**, built from AniList data the home screen was
  already fetching.
- **A curated extension-repository directory.** The Mangayomi/AnymeX side had no equivalent of the
  Stremio add-on directory: with no repo URL set it showed a single sample extension pointing at
  `example.invalid`, and asked you to paste a URL it never told you where to find. Three real
  anime repositories are now one tap each, loaded and merged together rather than one at a time,
  with counts for what the index carried that this app cannot run.
- **Adult content behind a switch** that is off by default and covers both ecosystems, with a bundled
  community add-on index.
- **Playback settings in Settings** (#133). Auto-skip, seek step, default speed and subtitle styling
  were reachable only from inside the player, mid-playback.
- **Library search, sort, and per-row history deletion** (#135). History was the only list where the
  sole way to remove one thing was to clear all of it. The per-row delete has an undo, and the undo
  expires when the history it came from is wiped — the Clear dialog says it cannot be undone, and
  that has to stay true even while an older snackbar is still on screen.
- **A season pack's other episodes are reachable from the player.** A magnet often points at a whole
  season, and the app opened whichever file was largest with no way to get to the rest — so a viewer
  who wanted episode 7 had to go back and hope a different magnet held only that episode. The track
  sheet now offers what else is in the torrent, in the order a person counts episodes rather than
  the order the strings sort in. Picking one starts a new playback of that file, so resume position,
  skip markers and history follow the episode instead of the pack.
- **Pull down to reload** (#142, #144) on the three screens that fetch over the network — Play,
  Browse, and the Stremio account library. There was no way to ask for fresh data short of leaving
  the tab and coming back. Each screen tracks refreshing separately from its first load, so the
  indicator does not appear on every cold start on top of the spinner already there. A rail that no
  source answered keeps what it had rather than blanking, and the fan-out runs on a scope the
  awaiting coroutine does not parent, so a wedged source cannot hold the indicator forever.
- **Colour taken from the cover art.** A show's page tints itself from its poster, clamped for
  contrast against the surface it sits on and recomputed per colour scheme.
- **A light scheme**, and a theme choice that holds from the first frame and inside the player.
- **Tablet layout for the details screen**: the episode list sits beside the show rather than below
  it (#116).
- Licensing and project documentation: `LICENSE` (GPL-3.0-or-later, with a Google Cast linking
  exception), `CONTRIBUTING.md`, `SECURITY.md`, `docs/building-and-releasing.md`, and this changelog.

### Changed

- **Source failures are named.** A source that fails now says which one and why, instead of an empty
  list that reads identically to "nothing matched".
- **Shared components across screens**: one poster tile, one back bar, one empty state, one loading
  state, one confirmation dialog — replacing near-identical private copies that had already drifted.
- **Undo, everywhere it belongs**, and confirmation dialogs for the destructive actions that cannot
  have one (deleting a download's bytes, clearing watch history).
- Executable Room migration tests: the harness now runs every migration against a real database
  rather than only diffing the exported schemas (#99).
- **Screen readers are told which rows can be tapped** (#138). Sixteen clickable rows carried no
  semantic role, so TalkBack read each as a plain container — the label, then nothing. Nothing
  looked different on screen, which is why it survived every review. Track-selection rows are
  `Role.RadioButton` inside a `selectableGroup`, so TalkBack can say "2 of 5" and that picking one
  unpicks the rest.
- **Nine hand-rolled empty and loading states** across eight screens now use the shared components
  (#139), including a byte-for-byte duplicate pair in sibling AniList files. Clearing watch history
  finally gets the error-tinted confirm every other destructive action already had (#140) — on the
  one screen where the irreversible button looked exactly like Cancel.
- **The accent extractor stopped reimplementing `InFlightCache`** (#137). The version it replaced
  was not broken; it was correct by lock ordering, and that argument lived nowhere.
- **`core/common` depends on JSR-330 rather than the whole Hilt Android runtime** (#143) for the one
  annotation it actually uses.
- **Regression tests where bugs kept shipping.** The screens that had produced repeat defects had no
  tests, because their collaborators were concrete classes built on the network, Media3 or the
  Android Keystore and could not be constructed on a JVM runner. Seven are interfaces now — the
  interface keeps the name, the implementation takes `Impl` — with no call site changed:
  `TrackingManager`, `EpisodeDownloads`, `SourceBootstrapper`, `StremioAccountStore`,
  `StremioAccountClient`, `AniListClient`, `AniSkipClient` (#141, #147, #149). Robolectric now
  reaches `core/player` (#136), `core/ui` (#142), `feature/library` (#152) and `app` (#151), so Compose
  screens and SharedPreferences-backed settings are exercised on the JVM. What each test pins is a
  rule that is invisible in a type signature: that an undo which cannot restore says so (#150), that
  a search matching nothing does not claim the library is empty (#152), that the theme is known
  before the first frame is drawn (#151). Every one was checked by breaking the behaviour it guards
  and confirming it fails.

### Fixed

- **Saved episodes from a Referer-gated source complete instead of stalling at 0%** (#155). Request
  headers are resolved per *request* but were stored per *video*. For a progressive MP4 those are the
  same string, so it worked. For HLS they are not — Media3 fetches the playlist and then every
  segment, each with its own URL, and none of the segment URLs are in the downloads table — so every
  segment went out with no Referer, no cookie and no auth header, and a source that requires one
  served the playlist and then refused everything after it. A request that is not itself a download
  is now matched to one whose path contains it, and only to one that does: sharing a host is not a
  relationship, and treating it as one handed a video's credentials to unrelated requests on the same
  CDN. Not a complete identity — two downloads in the *same* directory with different headers are
  still indistinguishable, because a segment URL carries nothing saying which of them asked for it —
  but that is the case a host issuing per-video credentials does not produce, since those give each
  video its own path. The same bug's other half was a leak: every segment cached its own miss under
  its own URL, so one episode left thousands of entries behind that removing the download could never
  reach.
- **A runaway extension no longer takes its source down until the app is restarted** (#156). QuickJS
  is single-threaded and the wrapper offers no interrupt hook, so `while (true) {}` in an extension
  owned that thread permanently and every later call to it queued behind — silently, with no error,
  for the life of the process. Rhino's half of the app had had a deadline since the same failure was
  found there; this one never got one. The thread still cannot be taken back — that is not available
  without an interrupt hook — but the caller no longer waits on it and later callers are told rather
  than queued, with a message that says to reload the extension rather than "try again", which is the
  one thing that cannot work. An extension that was merely slow is not disabled for it: a call that
  does come back clears its own mark.
- **A Rhino source that loops on fetches is now actually stopped by its deadline** (#156). The
  instruction observer runs between interpreter instructions, and a native call is one instruction
  however long it takes — so `while (true) { httpGet(url) }` issued hundreds of requests, each up to
  the call timeout, with the source's lock held, before the deadline was consulted once. It is now
  checked in the HTTP bridge, where such a loop demonstrably spends its time.
- **A dead host costs a Mangayomi extension eight seconds rather than twenty** (#156). The connect,
  read and write timeouts added to the Rhino bridge in #136 were never added to the Mangayomi one,
  which inherited the app-wide defaults.
- **An abandoned AniList sign-in no longer leaves a usable `state` value on disk** (#157). The OAuth
  nonce is single-use and replaced by the next attempt, but carried no timestamp — so tapping Connect
  and changing your mind left one that was still accepted months later. Fifteen minutes now; a nonce
  from a build before the stamp existed, or one whose clock has moved backwards, reads as expired.
- **A crash in the background is no longer lost entirely** (#157). Since Android 10 a background
  process may not start an activity, and the refusal is a log line rather than an exception — so the
  crash screen silently never appeared, and the fallback to the platform's handler sat inside a
  `catch` that never fired. The crash now goes to the platform handler whenever the screen is not
  certain to show, which costs a logcat trace instead of nothing at all.
- **A Cloudflare challenge is still recognised if the `Server` header is missing** (#157). Detection
  gated every signal on `Server: cloudflare`, including `cf-mitigated: challenge`, which Cloudflare
  only ever sends about its own challenges. One header stopping being emitted would have made a
  gated source fail forever with the solver never asked to run.
- **A hung extension no longer disables its source for the life of the process** (#127). Every
  scripted-source entry point holds a mutex across a *blocking* interpreter call, which coroutine
  cancellation cannot interrupt — so a timeout returned on schedule while the lock stayed held, and
  every later search or episode resolve for that source blocked forever. Retrying could not help,
  because the retry queued behind the same lock. There is now a wall-clock deadline, checked by the
  interpreter's instruction observer, that unwinds the interpreter and releases the lock — and both
  HTTP bridges bound their calls with timeouts. (Corrected: this said "cancellable calls". They are
  not cancellable and cannot be — a host function called synchronously by a JS engine has no way to
  suspend, so both bridges block on `.execute()`. "Bounded" is what is true, and the bound is what
  the fix relies on.)
- **"Push my saves" no longer resets your Stremio watch progress** (#128). It wrote a complete
  library item with a zeroed `state` and a fresh `_mtime`, so it won last-write-wins against every
  other Stremio client: one press flattened resume positions, watched-episode marks and
  season/episode pointers on your TV, desktop and the web. Existing items now round-trip the
  server's own state untouched.
- **Signing out no longer un-signs-out.** `clear()` used `apply()`, so a process kill in that window
  left the credential on disk to be read back next launch.
- **Bookmarking a Completed show no longer downgrades it to Plan-to-watch** (#129), and AniList sync
  no longer dies silently on an expired token while Settings keeps saying "signed in".
- **Removing a download no longer strands its bytes** (#130). The metadata row was deleted before
  the service confirmed the removal, leaving the file in the cache with nothing able to reach it.
- **Eight regressions introduced by #133 and #134** (#136), sharing one shape: each earlier fix
  landed the mechanism and missed the state around it. Retry stayed disabled for the rest of the
  session after one refused torrent; the first video of every session played at 1x; the subtitle
  style had three bugs at once from two ViewModels each rebuilding what a bare load/save pair did
  not provide; one failed download removal was erased by the next successful one; a disabled Stremio
  add-on assumed there was nothing to unregister; and the scripted-source call timeout was quietly a
  size limit, aborting a large page that arrived slowly.
- **A sign-in that landed during a sign-out was thrown away** (#148). Both credential stores drop
  their in-memory state twice on a clear — once immediately, once on a deferred half ordered after
  the disk load that would otherwise restore the old credential — and the second drop was
  unconditional. Signing in during that window left the screen signed out with no explanation until
  the next launch.
- **AniSkip's failure contract is now kept by AniSkip** (#149). Its interface promised an empty list
  on any failure; in fact `org.json` threw on anything it did not recognise and an `IOException`
  came straight out of the request. Playback survived only because both callers happened to defend
  themselves.
- **Three bugs from the Stremio account screen** (#147), two of which put one account's data in
  front of another.
- **Extension ids no longer depend on which repositories happened to load** (#134), which had made
  the same extension a different source depending on fetch order and reachability — and could crash
  the merged directory on a duplicate key.
- **"You have no sources" is somewhere you can see it** (#131). It rendered after three rails of
  posters, off-screen on every phone, and was suppressed entirely if you had any watch history.
- **The player survives an episode** (#125): a chain of fixes to leaks, races, and a player that
  would not stop.
- Security hardening across deep links, the OAuth redirect, the challenge WebView's cookie scope,
  torrent path containment, and where the app will accept executable code from (#100, #102, #105).
- Account traffic runs on its own HTTP client, so source-host settings cannot reach it (#104).

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
