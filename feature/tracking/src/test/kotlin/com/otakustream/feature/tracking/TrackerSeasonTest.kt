package com.otakustream.feature.tracking

import com.otakustream.core.database.tracking.TRACKER_SEASON_WHOLE_SERIES
import com.otakustream.core.database.tracking.toTrackerSeason
import org.junit.Assert.assertEquals
import org.junit.Test

// The rule that decides which AniList entry an episode's progress is pushed to. It's the difference
// between "season 3 episode 4" landing on the season-3 entry and landing on season 1 forever, and
// it's pure, so it's tested directly rather than through the database.
class TrackerSeasonTest {

    @Test
    fun `a real season number is used as the key`() {
        assertEquals(1, 1.toTrackerSeason())
        assertEquals(2, 2.toTrackerSeason())
        assertEquals(17, 17.toTrackerSeason())
    }

    @Test
    fun `no season data falls back to the whole series`() {
        // Scripted sources, local files, and single-season Stremio metas report no season at all.
        // They must keep resolving through the whole-series link, which is what every pre-existing
        // link migrated to — this is the case that keeps season-awareness additive.
        assertEquals(TRACKER_SEASON_WHOLE_SERIES, null.toTrackerSeason())
    }

    @Test
    fun `season zero is specials, not a season, so it falls back too`() {
        // Stremio uses season 0 for specials/OVAs. Treating it as "season 0, the season" would make
        // it collide with the whole-series sentinel and give specials their own phantom link, so it
        // resolves to the fallback instead.
        assertEquals(TRACKER_SEASON_WHOLE_SERIES, 0.toTrackerSeason())
    }

    @Test
    fun `a negative season is treated as no season rather than trusted`() {
        // Nothing should produce this, but a malformed extension or add-on manifest could. Coercing
        // it to the fallback is safer than writing a negative primary-key component.
        assertEquals(TRACKER_SEASON_WHOLE_SERIES, (-1).toTrackerSeason())
    }
}
