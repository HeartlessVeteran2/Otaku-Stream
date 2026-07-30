package com.otakustream.core.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The bound is the point of this: the store grows by one entry per torrent ever played, and
// SharedPreferences reads and parses its whole file on first access.
class TrackerRetentionPolicyTest {

    @Test
    fun `a new key goes to the front`() {
        val result = TrackerRetentionPolicy.retain(order = listOf("b", "c"), touched = "a")
        assertEquals(listOf("a", "b", "c"), result.order)
        assertTrue(result.evicted.isEmpty())
    }

    @Test
    fun `touching an existing key promotes rather than duplicates it`() {
        val result = TrackerRetentionPolicy.retain(order = listOf("a", "b", "c"), touched = "c")
        assertEquals(listOf("c", "a", "b"), result.order)
        assertTrue(result.evicted.isEmpty())
    }

    @Test
    fun `evicts the least recently used past the bound`() {
        val result = TrackerRetentionPolicy.retain(order = listOf("a", "b", "c"), touched = "d", max = 3)
        assertEquals(listOf("d", "a", "b"), result.order)
        assertEquals(listOf("c"), result.evicted)
    }

    @Test
    fun `re-touching at the bound evicts nothing`() {
        // Replaying a torrent already in the list must not push another one out.
        val result = TrackerRetentionPolicy.retain(order = listOf("a", "b", "c"), touched = "b", max = 3)
        assertEquals(listOf("b", "a", "c"), result.order)
        assertTrue(result.evicted.isEmpty())
    }

    @Test
    fun `trims an oversized stored order back to the bound`() {
        // A list saved by a build with a larger bound must come back down rather than staying over it
        // forever, since only the entry being touched is ever re-examined.
        val result = TrackerRetentionPolicy.retain(order = listOf("a", "b", "c", "d", "e"), touched = "f", max = 3)
        assertEquals(listOf("f", "a", "b"), result.order)
        assertEquals(listOf("c", "d", "e"), result.evicted)
    }

    @Test
    fun `starts from empty`() {
        val result = TrackerRetentionPolicy.retain(order = emptyList(), touched = "a")
        assertEquals(listOf("a"), result.order)
        assertTrue(result.evicted.isEmpty())
    }
}
