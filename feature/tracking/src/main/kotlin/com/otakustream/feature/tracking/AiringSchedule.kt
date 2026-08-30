package com.otakustream.feature.tracking

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// When the next episode of everything you follow airs, and which of them you can already watch.
//
// None of this costs a request. `MEDIA_SELECTION` has always asked for
// `nextAiringEpisode { episode airingAt }`, and AniListModels has always parsed both — so every
// load of the viewer's lists already carried "episode N airs at T" for every show on them, and the
// app read exactly none of it. The timestamp was fetched and discarded on every refresh.
//
// Pure, with the clock and calendar passed in, so the day bucketing can be tested at a boundary
// rather than whenever the suite happens to run. Same shape as currentSeasonAndYear in
// AniListModels, and the same reason.

// AniList list statuses that mean "I am working through this right now". REPEATING is included
// because a rewatch is still a watch: the user has a position in the show and episodes ahead of it.
private val ACTIVE_STATUSES = setOf("CURRENT", "REPEATING")

private const val STATUS_FINISHED = "FINISHED"
private const val STATUS_NOT_YET_RELEASED = "NOT_YET_RELEASED"

// How many episodes of this show exist to watch right now.
//
// Derived rather than guessed. `nextAiringEpisode` is the episode that has *not* aired yet, so
// everything below it has — that is exact, and needs no assumption about weekly cadence. Only when
// the show has no next episode does the total apply, and only if it is actually finished.
//
// null means genuinely unknown — an announced show with no schedule, or a response missing both
// fields — and callers must not treat that as zero. "We don't know how many aired" and "none have"
// look the same to a subtraction and mean opposite things to a viewer.
fun AniListMedia.airedEpisodeCount(): Int? = when {
    nextAiringEpisode != null -> (nextAiringEpisode - 1).coerceAtLeast(0)
    status == STATUS_NOT_YET_RELEASED -> 0
    status == STATUS_FINISHED -> episodes
    else -> null
}

// A show on the viewer's list with episodes aired past where they stopped.
data class ReadyToWatch(
    val entry: AniListListEntry,
    val airedCount: Int,
    // The episode they would actually open next — progress + 1. Shown instead of the number of
    // episodes outstanding, because for a long-running show that number is a discouraging count of
    // how far behind they are rather than an invitation to watch anything.
    val nextEpisode: Int,
) {
    val media: AniListMedia get() = entry.media
    val behindBy: Int get() = airedCount - entry.progress
}

// Shows the viewer is part-way through that have something waiting.
//
// Sorted by when the *next* episode airs, soonest first: that puts currently-airing shows at the
// front, which is what "new episodes" means to someone following a season. Shows with no schedule
// (finished, or between seasons) sort last rather than being dropped — a finished show you are
// midway through still has episodes waiting, and hiding it would make the rail lie by omission.
fun readyToWatch(entries: List<AniListListEntry>): List<ReadyToWatch> =
    entries.asSequence()
        .filter { it.status in ACTIVE_STATUSES }
        .mapNotNull { entry ->
            val aired = entry.media.airedEpisodeCount() ?: return@mapNotNull null
            if (aired <= entry.progress) return@mapNotNull null
            ReadyToWatch(entry = entry, airedCount = aired, nextEpisode = entry.progress + 1)
        }
        .sortedWith(
            compareBy<ReadyToWatch> { it.media.nextAiringAtSeconds ?: Long.MAX_VALUE }
                // Stable across refreshes: without a tiebreak, two shows airing in the same slot
                // would swap places on every reload.
                .thenBy { it.media.displayTitle.lowercase() },
        )
        .toList()

// One upcoming episode.
data class AiringItem(
    val media: AniListMedia,
    val episode: Int,
    val airingAtSeconds: Long,
    // Carried so the schedule can mark an episode the viewer is not yet caught up to — knowing
    // episode 9 airs on Friday matters differently when you are still on episode 3.
    val progress: Int,
)

data class AiringDay(
    val label: String,
    // Days from today in the viewer's own timezone. 0 = today. Kept alongside the label so the UI
    // can style today without re-parsing a translated string.
    val daysFromToday: Int,
    val items: List<AiringItem>,
)

// Everything on the viewer's list with a scheduled next episode, grouped by the day it airs.
//
// Only the next episode of each show, because that is all AniList reports. This is a schedule of
// what is coming, not a calendar of a whole season.
fun airingSchedule(
    entries: List<AniListListEntry>,
    nowMs: Long,
    // Injected whole rather than as a timezone id: bucketing must happen in the viewer's own
    // calendar, and a test needs to pin both the zone and the day it is "run" on.
    calendar: Calendar = Calendar.getInstance(),
    locale: Locale = Locale.getDefault(),
): List<AiringDay> {
    val today = startOfDay(calendar, nowMs)
    return entries.asSequence()
        .filter { it.status in ACTIVE_STATUSES }
        .mapNotNull { entry ->
            val episode = entry.media.nextAiringEpisode ?: return@mapNotNull null
            val airingAt = entry.media.nextAiringAtSeconds ?: return@mapNotNull null
            // Already aired but not yet refreshed on AniList's side. Dropping it keeps the schedule
            // honest: a "schedule" listing something in the past is just wrong.
            if (airingAt * 1000L < nowMs) return@mapNotNull null
            AiringItem(entry.media, episode, airingAt, entry.progress)
        }
        .groupBy { daysBetween(calendar, today, it.airingAtSeconds * 1000L) }
        .toSortedMap()
        .map { (days, items) ->
            AiringDay(
                label = dayLabel(days, items.first().airingAtSeconds * 1000L, calendar, locale),
                daysFromToday = days,
                items = items.sortedWith(
                    compareBy<AiringItem> { it.airingAtSeconds }
                        .thenBy { it.media.displayTitle.lowercase() },
                ),
            )
        }
}

// "in 2d 4h" / "in 3h 10m" / "in 8m" / "airing now".
//
// Coarse on purpose, and coarser the further out it is: no one waiting six days for an episode
// needs the minutes, and rendering them would make the label change every time the screen redrew.
fun formatCountdown(airingAtSeconds: Long, nowMs: Long): String {
    val remainingMs = airingAtSeconds * 1000L - nowMs
    if (remainingMs <= 0) return "airing now"
    val days = TimeUnit.MILLISECONDS.toDays(remainingMs)
    val hours = TimeUnit.MILLISECONDS.toHours(remainingMs) % 24
    val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs) % 60
    return when {
        days > 0 && hours > 0 -> "in ${days}d ${hours}h"
        days > 0 -> "in ${days}d"
        hours > 0 && minutes > 0 -> "in ${hours}h ${minutes}m"
        hours > 0 -> "in ${hours}h"
        minutes > 0 -> "in ${minutes}m"
        // Under a minute. "in 0m" reads like a bug.
        else -> "airing now"
    }
}

// Midnight local time on the day containing nowMs.
private fun startOfDay(calendar: Calendar, atMs: Long): Long {
    // Cloned so the caller's Calendar is not mutated — it is shared across every call here, and a
    // helper that quietly rewinds it to midnight would corrupt the next one.
    val day = calendar.clone() as Calendar
    day.timeInMillis = atMs
    day.set(Calendar.HOUR_OF_DAY, 0)
    day.set(Calendar.MINUTE, 0)
    day.set(Calendar.SECOND, 0)
    day.set(Calendar.MILLISECOND, 0)
    return day.timeInMillis
}

// Whole calendar days between two instants, counted by local midnights rather than by dividing a
// duration. Days are not all 86,400 seconds long: across a DST change one is an hour shorter, and a
// division would put an episode airing tomorrow evening into "today" for that week.
private fun daysBetween(calendar: Calendar, fromStartOfDay: Long, toMs: Long): Int {
    val target = startOfDay(calendar, toMs)
    if (target <= fromStartOfDay) return 0
    var days = 0
    val cursor = calendar.clone() as Calendar
    cursor.timeInMillis = fromStartOfDay
    // Stepped a day at a time so each step crosses one real local midnight, whatever its length.
    // Bounded because this walks: beyond a couple of weeks the exact number stops mattering and the
    // label falls back to a date anyway.
    while (cursor.timeInMillis < target && days < MAX_SCHEDULE_DAYS) {
        cursor.add(Calendar.DAY_OF_YEAR, 1)
        days++
    }
    return days
}

// The formatter takes the bucketing calendar's timezone, not the JVM default. Those differ in a
// test that pins a zone, and the mismatch would name a different weekday than the one the episode
// was grouped under — a schedule disagreeing with its own headings.
private fun dayLabel(daysFromToday: Int, atMs: Long, calendar: Calendar, locale: Locale): String {
    val pattern = when (daysFromToday) {
        0 -> return "Today"
        1 -> return "Tomorrow"
        // Within the coming week a weekday name is unambiguous and reads better than a date.
        in 2..6 -> "EEEE"
        else -> "EEE d MMM"
    }
    return SimpleDateFormat(pattern, locale).apply { timeZone = calendar.timeZone }.format(Date(atMs))
}

// Anything further out than this is grouped together; AniList rarely schedules beyond it, and a
// schedule that scrolls for a month is not one anybody reads.
private const val MAX_SCHEDULE_DAYS = 21
