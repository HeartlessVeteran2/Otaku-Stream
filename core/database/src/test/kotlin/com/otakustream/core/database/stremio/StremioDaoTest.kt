package com.otakustream.core.database.stremio

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.otakustream.core.database.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The two add-on writes that used to undo the user's own choices, against a real SQLite database.
//
// Both were invisible at the moment they happened. Re-installing an add-on silently switched it
// back on and moved it in the list; reordering silently did nothing at all once two rows tied on
// priority. Neither produced an error, and both only showed up the next time someone looked at the
// list and found it wasn't what they left.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StremioDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: StremioDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        dao = db.stremioDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun addon(
        name: String,
        enabled: Boolean = true,
        priority: Int = 0,
        manifestJson: String = """{"name":"$name","version":"1.0.0"}""",
    ) = StremioAddonEntity(
        manifestUrl = "https://$name.example/manifest.json",
        manifestJson = manifestJson,
        name = name,
        enabled = enabled,
        priority = priority,
    )

    private suspend fun order() = dao.getAllAddons().map { it.name }

    @Test
    fun `re-installing keeps an add-on the user switched off switched off`() = runTest {
        dao.upsertAddon(addon("torrentio", enabled = false, priority = 3))

        // What the installer builds from a freshly fetched manifest: enabled by default, priority
        // from whatever the caller passed.
        dao.upsertAddonKeepingUserSettings(
            addon("torrentio", enabled = true, priority = 0, manifestJson = """{"name":"torrentio","version":"2.0.0"}"""),
        )

        val stored = dao.getAllAddons().single()
        assertFalse("an add-on the user turned off must stay off", stored.enabled)
        assertEquals("and must not be moved in the list", 3, stored.priority)
        // The point of re-installing is still served: the manifest itself is refreshed.
        assertEquals("""{"name":"torrentio","version":"2.0.0"}""", stored.manifestJson)
    }

    @Test
    fun `a genuinely new add-on takes the settings it was installed with`() = runTest {
        dao.upsertAddonKeepingUserSettings(addon("comet", enabled = true, priority = 5))

        val stored = dao.getAllAddons().single()
        assertEquals(5, stored.priority)
        assertEquals(true, stored.enabled)
    }

    @Test
    fun `reordering renumbers the whole list so no two rows tie`() = runTest {
        // Every add-on at priority 0 — exactly what "Add by URL" produced, since it installs with
        // the default priority and never looked at what was already there.
        dao.upsertAddon(addon("aaa"))
        dao.upsertAddon(addon("bbb"))
        dao.upsertAddon(addon("ccc"))
        assertEquals(listOf("aaa", "bbb", "ccc"), order())

        dao.setAddonOrder(listOf("https://ccc.example/manifest.json", "https://aaa.example/manifest.json", "https://bbb.example/manifest.json"))

        assertEquals(listOf("ccc", "aaa", "bbb"), order())
        // Distinct afterwards, which is what makes the *next* move work — the pairwise swap this
        // replaced could never break a tie, because swapping two equal values changes nothing.
        assertEquals(listOf(0, 1, 2), dao.getAllAddons().map { it.priority })
    }

    @Test
    fun `moving the top add-on down and back leaves the original order`() = runTest {
        dao.upsertAddon(addon("aaa", priority = 0))
        dao.upsertAddon(addon("bbb", priority = 1))
        dao.upsertAddon(addon("ccc", priority = 2))

        val urls = dao.getAllAddons().map { it.manifestUrl }.toMutableList()
        urls.add(1, urls.removeAt(0))
        dao.setAddonOrder(urls)
        assertEquals(listOf("bbb", "aaa", "ccc"), order())

        val back = dao.getAllAddons().map { it.manifestUrl }.toMutableList()
        back.add(0, back.removeAt(1))
        dao.setAddonOrder(back)
        assertEquals(listOf("aaa", "bbb", "ccc"), order())
    }

    @Test
    fun `tied priorities still list in a stable order`() = runTest {
        // Until a reorder renumbers them, rows installed with the default priority all tie. Without
        // the manifestUrl tiebreak SQLite may return them in any order, and the reorder buttons
        // index into that list — so "move this one up" could move a different row than the one the
        // user tapped.
        dao.upsertAddon(addon("zzz"))
        dao.upsertAddon(addon("aaa"))
        dao.upsertAddon(addon("mmm"))

        assertEquals(listOf("aaa", "mmm", "zzz"), order())
        assertEquals(order(), order())
    }
}
