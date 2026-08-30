package com.otakustream.feature.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24 * HOUR_MS

class AiringScheduleTest {

    // A fixed instant, so "today" is a decision of the test rather than of the day it runs.
    // 2026-04-15 08:00 UTC, a Wednesday. The time of day matters as much as the date: an offset of
    // 20 hours from here lands on Thursday, not "later today", and a test that assumes otherwise is
    // testing its own arithmetic rather than the bucketing.
    private val nowMs = 1_776_240_000_000L

    private fun utc() = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

    private fun media(
        id: Long = 1,
        title: String = "Show",
        episodes: Int? = 12,
        nextEpisode: Int? = null,
        airsInMs: Long? = null,
        status: String? = "RELEASING",
    ) = AniListMedia(
        id = id,
        title = title,
        episodes = episodes,
        englishTitle = title,
        status = status,
        nextAiringEpisode = nextEpisode,
        nextAiringAtSeconds = airsInMs?.let { (nowMs + it) / 1000 },
    )

    private fun entry(media: AniListMedia, progress: Int, status: String? = "CURRENT") =
        AniListListEntry(media = media, status = status, score = null, progress = progress)

    // ---- airedEpisodeCount ----

    // Derived from the next episode, not guessed from a weekly cadence: if episode 7 is next, six
    // have aired, and that is exact.
    @Test
    fun `aired count comes from the next airing episode`() {
        assertEquals(6, media(nextEpisode = 7).airedEpisodeCount())
    }

    @Test
    fun `a finished show has all of its episodes aired`() {
        assertEquals(12, media(episodes = 12, status = "FINISHED").airedEpisodeCount())
    }

    @Test
    fun `an unreleased show has none aired`() {
        assertEquals(0, media(episodes = null, status = "NOT_YET_RELEASED").airedEpisodeCount())
    }

    // "We don't know" and "none" mean opposite things to a viewer but look identical to a
    // subtraction, so the unknown case must stay null rather than collapsing to zero.
    @Test
    fun `an unknown schedule is null rather than zero`() {
        assertNull(media(episodes = null, status = "RELEASING").airedEpisodeCount())
        assertNull(media(episodes = null, status = null).airedEpisodeCount())
    }

    @Test
    fun `episode one being next means nothing has aired`() {
        assertEquals(0, media(nextEpisode = 1).airedEpisodeCount())
    }

    // ---- readyToWatch ----

    @Test
    fun `a show with episodes past your progress is ready to watch`() {
        val ready = readyToWatch(listOf(entry(media(nextEpisode = 7), progress = 3)))
        assertEquals(1, ready.size)
        assertEquals(6, ready.first().airedCount)
        assertEquals(3, ready.first().behindBy)
        // The episode they would open, not how far behind they are.
        assertEquals(4, ready.first().nextEpisode)
    }

    @Test
    fun `a caught up show is not ready to watch`() {
        assertTrue(readyToWatch(listOf(entry(media(nextEpisode = 7), progress = 6))).isEmpty())
    }

    // AniList data lags, and people watch ahead elsewhere. Progress past the aired count must not
    // produce a negative "behind" or a phantom entry.
    @Test
    fun `progress ahead of the aired count is not ready to watch`() {
        assertTrue(readyToWatch(listOf(entry(media(nextEpisode = 3), progress = 9))).isEmpty())
    }

    @Test
    fun `only shows you are actively watching count`() {
        val behind = media(nextEpisode = 7)
        assertTrue(readyToWatch(listOf(entry(behind, 0, status = "PLANNING"))).isEmpty())
        assertTrue(readyToWatch(listOf(entry(behind, 0, status = "PAUSED"))).isEmpty())
        assertTrue(readyToWatch(listOf(entry(behind, 0, status = "DROPPED"))).isEmpty())
        // A rewatch is still a watch: there is a position and episodes ahead of it.
        assertEquals(1, readyToWatch(listOf(entry(behind, 0, status = "REPEATING"))).size)
    }

    // Sorted so what is airing soonest leads, which is what "new episodes" means to someone
    // following a season.
    @Test
    fun `airing soonest leads`() {
        val soon = media(id = 1, title = "Soon", nextEpisode = 5, airsInMs = 2 * HOUR_MS)
        val later = media(id = 2, title = "Later", nextEpisode = 5, airsInMs = 5 * DAY_MS)
        val ready = readyToWatch(listOf(entry(later, 0), entry(soon, 0)))
        assertEquals(listOf("Soon", "Later"), ready.map { it.media.displayTitle })
    }

    // A finished show you are midway through has episodes waiting too. Dropping it because it has
    // no schedule would make the rail lie by omission.
    @Test
    fun `a finished show you are behind on still appears, sorted last`() {
        val airing = media(id = 1, title = "Airing", nextEpisode = 5, airsInMs = HOUR_MS)
        val finished = media(id = 2, title = "Finished", episodes = 24, status = "FINISHED")
        val ready = readyToWatch(listOf(entry(finished, 2), entry(airing, 0)))
        assertEquals(listOf("Airing", "Finished"), ready.map { it.media.displayTitle })
    }

    // Without a tiebreak two shows in the same slot would swap places on every refresh.
    @Test
    fun `ordering is stable for shows airing at the same time`() {
        val b = media(id = 1, title = "Beta", nextEpisode = 2, airsInMs = HOUR_MS)
        val a = media(id = 2, title = "Alpha", nextEpisode = 2, airsInMs = HOUR_MS)
        assertEquals(
            listOf("Alpha", "Beta"),
            readyToWatch(listOf(entry(b, 0), entry(a, 0))).map { it.media.displayTitle },
        )
    }

    // ---- airingSchedule ----

    @Test
    fun `groups episodes by the day they air`() {
        val today = media(id = 1, title = "Today", nextEpisode = 3, airsInMs = 2 * HOUR_MS)
        val tomorrow = media(id = 2, title = "Tomorrow", nextEpisode = 4, airsInMs = DAY_MS + 2 * HOUR_MS)
        val days = airingSchedule(listOf(entry(today, 1), entry(tomorrow, 2)), nowMs, utc(), Locale.UK)
        assertEquals(listOf("Today", "Tomorrow"), days.map { it.label })
        assertEquals(listOf(0, 1), days.map { it.daysFromToday })
        assertEquals("Today", days.first().items.single().media.displayTitle)
    }

    @Test
    fun `a day later in the week is named`() {
        // Wednesday + 3 days = Saturday.
        val show = media(nextEpisode = 3, airsInMs = 3 * DAY_MS)
        val days = airingSchedule(listOf(entry(show, 1)), nowMs, utc(), Locale.UK)
        assertEquals("Saturday", days.single().label)
    }

    // A "schedule" listing something in the past is simply wrong; AniList's own data lags after an
    // episode airs.
    @Test
    fun `an episode whose time has passed is not scheduled`() {
        val stale = media(nextEpisode = 3, airsInMs = -HOUR_MS)
        assertTrue(airingSchedule(listOf(entry(stale, 1)), nowMs, utc(), Locale.UK).isEmpty())
    }

    @Test
    fun `a show with no schedule is not in the schedule`() {
        val finished = media(episodes = 12, status = "FINISHED")
        assertTrue(airingSchedule(listOf(entry(finished, 1)), nowMs, utc(), Locale.UK).isEmpty())
    }

    @Test
    fun `days are ordered soonest first and episodes within a day by time`() {
        // 08:00 + 14h = 22:00 the same day, so this stays inside "today".
        val late = media(id = 1, title = "Late", nextEpisode = 3, airsInMs = 14 * HOUR_MS)
        val early = media(id = 2, title = "Early", nextEpisode = 3, airsInMs = 3 * HOUR_MS)
        val nextWeek = media(id = 3, title = "NextWeek", nextEpisode = 3, airsInMs = 8 * DAY_MS)
        val days = airingSchedule(
            listOf(entry(nextWeek, 1), entry(late, 1), entry(early, 1)),
            nowMs,
            utc(),
            Locale.UK,
        )
        assertEquals(listOf(0, 8), days.map { it.daysFromToday })
        assertEquals(listOf("Early", "Late"), days.first().items.map { it.media.displayTitle })
    }

    // Days are not all 86,400 seconds long. Dividing a duration would put an episode airing
    // tomorrow evening into "today" on the week the clocks go forward.
    @Test
    fun `day bucketing survives a daylight saving change`() {
        val london = Calendar.getInstance(TimeZone.getTimeZone("Europe/London"))
        // 2026-03-28 12:00 UTC — the Saturday before the UK clocks go forward at 01:00 on the 29th.
        val beforeDst = 1_774_699_200_000L
        // 26 hours later is Sunday afternoon, one calendar day on, despite the day being 23h long.
        val show = AniListMedia(
            id = 1,
            title = "Show",
            episodes = 12,
            englishTitle = "Show",
            status = "RELEASING",
            nextAiringEpisode = 5,
            nextAiringAtSeconds = (beforeDst + 26 * HOUR_MS) / 1000,
        )
        val days = airingSchedule(listOf(entry(show, 1)), beforeDst, london, Locale.UK)
        assertEquals(1, days.single().daysFromToday)
        assertEquals("Tomorrow", days.single().label)
    }

    // ---- formatCountdown ----

    @Test
    fun `countdown is coarser the further out it is`() {
        assertEquals("in 2d 4h", formatCountdown((nowMs + 2 * DAY_MS + 4 * HOUR_MS) / 1000, nowMs))
        assertEquals("in 3d", formatCountdown((nowMs + 3 * DAY_MS) / 1000, nowMs))
        assertEquals("in 3h 10m", formatCountdown((nowMs + 3 * HOUR_MS + 600_000) / 1000, nowMs))
        assertEquals("in 5h", formatCountdown((nowMs + 5 * HOUR_MS) / 1000, nowMs))
        assertEquals("in 8m", formatCountdown((nowMs + 480_000) / 1000, nowMs))
    }

    // "in 0m" reads like a bug rather than like an episode being seconds away.
    @Test
    fun `an episode due now or overdue says so`() {
        assertEquals("airing now", formatCountdown(nowMs / 1000, nowMs))
        assertEquals("airing now", formatCountdown((nowMs + 30_000) / 1000, nowMs))
        assertEquals("airing now", formatCountdown((nowMs - DAY_MS) / 1000, nowMs))
    }
}
