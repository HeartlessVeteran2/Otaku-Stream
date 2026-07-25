package com.otakustream.feature.tracking

import com.otakustream.core.database.library.LIBRARY_STATUS_COMPLETED
import com.otakustream.core.database.library.LIBRARY_STATUS_PLANNED
import com.otakustream.core.database.library.LIBRARY_STATUS_WATCHING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryStatusMappingTest {

    @Test
    fun `maps each library bucket to its AniList status`() {
        assertEquals("PLANNING", libraryStatusToAniList(LIBRARY_STATUS_PLANNED))
        assertEquals("CURRENT", libraryStatusToAniList(LIBRARY_STATUS_WATCHING))
        assertEquals("COMPLETED", libraryStatusToAniList(LIBRARY_STATUS_COMPLETED))
    }

    @Test
    fun `unknown bucket maps to null`() {
        assertNull(libraryStatusToAniList("SOMETHING_ELSE"))
    }

    @Test
    fun `mirror writes the desired status when moving forward`() {
        assertEquals("CURRENT", decideStatusMirror(currentAniStatus = "PLANNING", desiredAniStatus = "CURRENT"))
        assertEquals("COMPLETED", decideStatusMirror(currentAniStatus = "CURRENT", desiredAniStatus = "COMPLETED"))
        assertEquals("CURRENT", decideStatusMirror(currentAniStatus = null, desiredAniStatus = "CURRENT"))
    }

    @Test
    fun `mirror skips a no-op`() {
        assertNull(decideStatusMirror(currentAniStatus = "CURRENT", desiredAniStatus = "CURRENT"))
    }

    @Test
    fun `mirror never downgrades a finished or rewatching entry`() {
        assertNull(decideStatusMirror(currentAniStatus = "COMPLETED", desiredAniStatus = "CURRENT"))
        assertNull(decideStatusMirror(currentAniStatus = "COMPLETED", desiredAniStatus = "PLANNING"))
        assertNull(decideStatusMirror(currentAniStatus = "REPEATING", desiredAniStatus = "CURRENT"))
    }

    @Test
    fun `mirror still allows marking a rewatching entry complete`() {
        assertEquals("COMPLETED", decideStatusMirror(currentAniStatus = "REPEATING", desiredAniStatus = "COMPLETED"))
    }
}
