package app.vesper.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.vesper.KEY_LEGACY_IMPORT
import app.vesper.data.AppDatabase
import app.vesper.data.Idea
import app.vesper.data.Lobe
import app.vesper.data.Note
import app.vesper.data.SYNC_TRIGGERS_CALLBACK
import app.vesper.data.Task
import app.vesper.importLegacy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The one-time 2.4.1 -> 3.0 move of Scrolls and Flames into the Brain, alone and between two syncing devices. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyImportTest {
    @get:Rule val tmp = TemporaryFolder()

    private class MemSettings : app.vesper.Settings {
        val m = HashMap<String, String>()
        override fun getString(key: String) = m[key]
        override fun putString(key: String, value: String) { m[key] = value }
        override fun getBoolean(key: String, default: Boolean) = m[key]?.toBoolean() ?: default
        override fun putBoolean(key: String, value: Boolean) { m[key] = value.toString() }
    }

    private val drive = MemoryDrive()
    private val key = newDataKey()
    private var vault = ""

    private inner class Device {
        val db: AppDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .addCallback(SYNC_TRIGGERS_CALLBACK).build()
        val store = SyncStore(tmp.newFolder()).also { it.state = it.state.copy(vaultId = vault) }
        val settings = MemSettings()
        suspend fun sync() = syncRound(db, store, drive, key)
        suspend fun import() = importLegacy(db, settings)
        suspend fun ideas() = db.syncDao().ideas()
        /** What the same old data looks like on every device that ran 2.4.1 and synced. */
        suspend fun seedOld() {
            db.syncDao().insert(Note(title = "Groceries", content = "milk", uid = "n1", updatedAt = 1234))
            db.syncDao().insert(Note(title = "Idea", content = "", uid = "n2"))
            db.syncDao().insert(Task(text = "call mum", uid = "t1"))
            db.syncDao().insert(Task(text = "old chore", uid = "t2", snuffedAt = 99))
        }
    }

    private val devices = mutableListOf<Device>()
    private fun device() = Device().also { devices += it }
    @After fun close() = devices.forEach { it.db.close() }

    private fun test(block: suspend () -> Unit) = runBlocking(Dispatchers.IO) {
        vault = drive.create(VAULT_FILE, wrapKey(key, "correct horse battery", 100_000)).id
        block()
    }

    private suspend fun Device.lobeName(i: Idea) = db.syncDao().lobes().single { it.id == i.lobeId }.name
    private suspend fun Device.view() = ideas().map { lobeName(it) to it.title + "|" + it.body }.sortedBy { it.second }

    @Test fun oldNotesAndLitTasksLandInTheRightBrains() = test {
        val a = device(); a.seedOld()
        assertEquals(3, a.import())
        assertEquals(listOf("" to "Groceries|milk", "" to "Idea|", "Tasks" to "call mum|"), a.view().sortedBy { it.first })
        assertEquals(1234L, a.ideas().single { it.uid == "legacy-note-n1" }.updatedAt)
        assertEquals("flag saved", true, a.settings.getBoolean(KEY_LEGACY_IMPORT, false))
        assertEquals("old rows untouched", 2, a.db.syncDao().notes().size)
        assertEquals(2, a.db.syncDao().tasks().size)
    }

    @Test fun runningTwiceAddsNothing() = test {
        val a = device(); a.seedOld()
        a.import()
        assertEquals(0, a.import())
        a.settings.m.clear() // flag lost: the uid checks alone still stop a repeat
        assertEquals(0, a.import())
        assertEquals(3, a.ideas().size)
    }

    @Test fun finishedTasksAndBlankNotesAreSkipped() = test {
        val a = device()
        a.db.syncDao().insert(Task(text = "done", uid = "t1", snuffedAt = 5))
        a.db.syncDao().insert(Note(title = " ", content = "", uid = "n1"))
        assertEquals(0, a.import())
        assertTrue(a.ideas().isEmpty())
        assertTrue("no empty sub brain left behind", a.db.syncDao().lobes().isEmpty())
    }

    @Test fun twoDevicesImportingTheSameDataEndWithOneCopyEach() = test {
        val a = device(); val b = device()
        a.seedOld(); b.seedOld()
        // both already share their sub brains, as after any earlier sync
        a.db.syncDao().insert(Lobe(name = "", colorIndex = 0, slot = 0, uid = "main"))
        a.db.syncDao().insert(Lobe(name = "Tasks", colorIndex = 1, slot = 1, uid = "tasks"))
        b.db.syncDao().insert(Lobe(name = "", colorIndex = 0, slot = 0, uid = "main"))
        b.db.syncDao().insert(Lobe(name = "Tasks", colorIndex = 1, slot = 1, uid = "tasks"))
        a.import(); b.import()
        repeat(3) { a.sync(); b.sync() }
        assertEquals(3, a.ideas().size)
        assertEquals(a.view(), b.view())
        assertFalse(a.ideas().any { it.title.contains("(other copy)") })
        assertFalse(b.ideas().any { it.title.contains("(other copy)") })
    }

    @Test fun twoDevicesThatEachMadeTheirOwnSubBrainsStillGetOneCopyOfEachIdea() = test {
        val a = device(); val b = device()
        a.seedOld(); b.seedOld()
        a.import(); b.import()
        repeat(3) { a.sync(); b.sync() }
        assertEquals(3, a.ideas().size)
        assertEquals(3, b.ideas().size)
        assertFalse(a.ideas().any { it.title.contains("(other copy)") })
    }

    @Test fun anImportBurnedOnOneDeviceIsNotBroughtBackByTheOther() = test {
        val a = device(); val b = device()
        a.seedOld(); b.seedOld()
        a.import()
        a.db.brainDao().deleteIdea(a.ideas().single { it.uid == "legacy-note-n1" }.id) // burned on A
        a.sync(); b.sync()
        b.import() // B starts late: A's two live ideas and the tombstone arrived first
        repeat(3) { a.sync(); b.sync() }
        assertEquals(setOf("legacy-note-n2", "legacy-task-t1"), a.ideas().map { it.uid }.toSet())
        assertEquals(a.ideas().map { it.uid }.toSet(), b.ideas().map { it.uid }.toSet())
    }

    @Test fun aTombstonedImportIsNeverMadeAgain() = test {
        val a = device(); a.seedOld()
        a.db.syncDao().insert(listOf(app.vesper.data.SyncTombstone("ideas", "legacy-note-n1", 1)))
        assertEquals(2, a.import())
        assertFalse(a.ideas().any { it.uid == "legacy-note-n1" })
    }
}
