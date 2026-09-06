package com.otakustream.core.sources.mangayomi.repo

// The extension repositories that actually have anime in them, which the app had no way to tell you
// about.
//
// Worth being precise about the problem this solves, because the screen did not look broken. With
// no repo URL set, MangayomiRepoClient returned a single sample extension pointing at
// example.invalid, and the screen said "paste a repo's anime index URL" — which only helps someone
// who already knows one. Meanwhile the Stremio side of the app ships a curated directory
// (RecommendedAddons) and installs in one tap. So Stremio worked out of the box and this ecosystem
// looked empty, and the obvious conclusion was that the app only supports Stremio.
//
// It never was empty. These three repos carry 52 installable anime extensions between them today.
// This list is that knowledge, written down, reviewable in the diff, and one tap to load — exactly
// what RecommendedAddons is for add-ons.
//
// Same posture as the Stremio directory: the app still ships no scrapers. These are published
// third-party indexes, and every URL goes through RemoteCodeUrl.require like any other source of
// executable code.
//
// One repo that is *not* here: kodjodevf/mangayomi-extensions, which used to be the obvious answer
// and is now manga and novels only — its anime_index.json 404s. Anime moved to m2k3a. Anyone who
// set that URL earlier has a dead screen and no way to find that out from inside the app.
object RecommendedExtensionRepos {

    data class Repo(
        val name: String,
        val description: String,
        val indexUrl: String,
    )

    val repos: List<Repo> = listOf(
        Repo(
            name = "m2k3a",
            description = "The main Mangayomi anime index, and where anime moved to when the " +
                "original repository became manga-only. Carries Torrentio, NetMirror, AllAnime " +
                "and SubsPlease among others. Most of its entries are Dart, which this app " +
                "cannot run — the JavaScript ones are the ones that appear.",
            indexUrl = "https://m2k3a.github.io/mangayomi-extensions/anime_index.json",
        ),
        Repo(
            name = "Mallyd11",
            description = "A small, regularly tested set of streaming extensions — HiAnime, " +
                "Miruro, AniWave, JustAnime. All JavaScript, so all of them are usable here.",
            indexUrl = "https://raw.githubusercontent.com/Mallyd11/mangayomi-anime-extensions/main/anime_index.json",
        ),
        Repo(
            name = "Swakshan",
            description = "Another all-JavaScript set, with less overlap than the two above — " +
                "KickAssAnime, AnimeParadise, Animeonsen, Sudatchi.",
            indexUrl = "https://raw.githubusercontent.com/Swakshan/mangayomi-swak-extensions/refs/heads/main/anime_index.json",
        ),
    )
}
