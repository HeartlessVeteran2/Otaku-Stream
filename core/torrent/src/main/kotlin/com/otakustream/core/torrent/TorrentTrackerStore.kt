package com.otakustream.core.torrent

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// What to keep and what to drop when remembering another torrent's trackers.
data class TrackerRetention(
    val order: List<String>,
    val evicted: List<String>,
)

// Bounded most-recently-used retention, kept pure so the eviction rule is testable.
//
// A bound is necessary rather than tidy: this grows by one entry per torrent ever played, and an
// unbounded SharedPreferences file is read and parsed in full on first access.
internal object TrackerRetentionPolicy {
    const val MAX_ENTRIES = 128

    fun retain(order: List<String>, touched: String, max: Int = MAX_ENTRIES): TrackerRetention {
        // Most recent first, and the touched entry moves to the front rather than being duplicated.
        val promoted = listOf(touched) + order.filterNot { it == touched }
        return if (promoted.size <= max) {
            TrackerRetention(order = promoted, evicted = emptyList())
        } else {
            TrackerRetention(order = promoted.take(max), evicted = promoted.drop(max))
        }
    }
}

// Remembers which trackers a torrent was last played with.
//
// Trackers deliberately don't live in the torrent:// url — the url is the playback identity that
// resume position and watch history key on, and the tracker list varies between add-on responses, so
// putting it in the url would make that identity unstable. The consequence is that replaying from
// watch history has no tracker list at all and falls back to DHT alone, which is slow to bootstrap
// and often finds no peers. This closes that gap: the trackers that worked last time are reused.
//
// SharedPreferences rather than Room. This is recoverable cache data — losing it costs a slower
// start, not correctness — so it isn't worth a schema version and a migration.
@Singleton
class TorrentTrackerStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    fun trackersFor(infoHash: String): List<String> {
        val key = keyFor(infoHash) ?: return emptyList()
        return prefs.getString(key, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    // No-op for an empty list: overwriting a remembered set with nothing would turn one DHT-only
    // playback into every later one being DHT-only too.
    fun remember(infoHash: String, trackers: List<String>) {
        if (trackers.isEmpty()) return
        val key = keyFor(infoHash) ?: return
        val order = prefs.getString(KEY_ORDER, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val retention = TrackerRetentionPolicy.retain(order, key)
        prefs.edit {
            putString(key, trackers.distinct().joinToString("\n"))
            putString(KEY_ORDER, retention.order.joinToString("\n"))
            retention.evicted.forEach { remove(it) }
        }
    }

    // Normalized through TorrentUri so the key matches however the caller spelled the hash — an
    // uppercase hash and a lowercase one are one torrent and must share one entry.
    private fun keyFor(infoHash: String): String? {
        val url = TorrentUri.build(infoHash, 0) ?: return null
        val normalized = TorrentUri.parse(url) ?: return null
        return "$KEY_PREFIX${normalized.infoHash}"
    }

    private companion object {
        const val PREFS_NAME = "torrent_trackers"

        // The retention order lives in the same file, so it must not collide with a hash key.
        const val KEY_ORDER = "__order"
        const val KEY_PREFIX = "tr_"
    }
}
