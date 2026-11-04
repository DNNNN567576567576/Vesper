package app.vesper.sync

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.vesper.BrainViewModel
import app.vesper.MoonsViewModel
import app.vesper.NoteEditorViewModel
import app.vesper.Settings
import app.vesper.ShrineMedia
import app.vesper.Vault
import app.vesper.VesperHost
import app.vesper.data.AppDatabase
import app.vesper.data.Idea
import app.vesper.data.Lobe
import app.vesper.data.Note
import app.vesper.data.Plan
import app.vesper.data.SYNC_TRIGGERS_CALLBACK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The real ViewModels over a real Room with the triggers. Remote changes are written the way merge writes them: explicit, higher syncAt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorSafetyTest {
    @get:Rule val tmp = TemporaryFolder()

    private val drive = MemoryDrive()
    private val key = newDataKey()
    private val platform by lazy { FakePlatform(tmp.newFolder()).also { it.key = key } }

    private val host by lazy {
        object : VesperHost {
            override val db: AppDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
                .addCallback(SYNC_TRIGGERS_CALLBACK).build()
            override val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            override fun reportError(what: String, e: Throwable) { throw AssertionError("could not $what", e) }
            override val settings: Settings get() = error("unused")
            override val shrineMedia: ShrineMedia get() = error("unused")
            override val vault: Vault get() = error("unused")
            override val sync by lazy { SyncManager(db, appScope, platform, ::reportError, { drive }) }
        }
    }
    private val dao get() = host.db.noteDao()
    private val rows get() = host.db.syncDao()

    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun until(what: String, done: () -> Boolean) = waitUntil(what, ::idle, done)
    // Pumps the looper: a save can wait on a refresh that resumes on the main thread, which is this test thread.
    private fun Job.done() = until("save") { isCompleted }

    private fun open(title: String = "t", content: String = "base"): Pair<NoteEditorViewModel, Long> {
        val id = io { dao.insert(Note(title = title, content = content)) }
        val vm = NoteEditorViewModel(host, id)
        until("loaded") { vm.loaded }
        return vm to id
    }

    /** What merge does to a row a peer changed. */
    private fun remoteEdit(id: Long, content: String) = io {
        val n = dao.get(id)!!
        rows.update(n.copy(content = content, syncAt = n.syncAt + 1000))
    }

    @After fun close() {
        host.appScope.cancel()
        host.db.close()
    }

    @Test fun anIdleEditorShowsTheRemoteChange() {
        val (vm, id) = open()
        remoteEdit(id, "from the phone")
        until("refresh") { vm.content == "from the phone" }
        vm.flush().done()
        assertEquals("from the phone", io { dao.get(id) }!!.content)
        assertEquals(1, io { rows.notes() }.size) // no copy, no conflict
    }

    @Test fun anIdleEditorThatTypesAfterARefreshBuildsOnTheNewText() {
        val (vm, id) = open()
        remoteEdit(id, "from the phone")
        until("refresh") { vm.content == "from the phone" }
        vm.onContent("from the phone, and more")
        vm.flush().done()
        assertEquals("from the phone, and more", io { dao.get(id) }!!.content)
        assertEquals(1, io { rows.notes() }.size)
    }

    @Test fun typingSurvivesARemoteChangeAndTheOtherSideBecomesACopy() {
        val (vm, id) = open()
        vm.onContent("my typing")
        remoteEdit(id, "from the phone")
        repeat(20) { idle(); Thread.sleep(5) }
        assertEquals("my typing", vm.content)
        vm.flush().done()
        assertEquals("my typing", vm.content)
        val all = io { rows.notes() }
        assertEquals("my typing", all.single { it.id == id }.content)
        val copy = all.single { it.id != id }
        assertEquals("from the phone", copy.content)
        assertEquals("t (other copy)", copy.title)
    }

    @Test fun aSecondSaveMakesNoSecondCopy() {
        val (vm, id) = open()
        vm.onContent("one")
        remoteEdit(id, "from the phone")
        vm.flush().done()
        vm.onContent("two")
        vm.flush().done()
        assertEquals(2, io { rows.notes() }.size)
        assertEquals("two", io { dao.get(id) }!!.content)
    }

    @Test fun noteDeletedElsewhereWhileTypingGetsARowBack() {
        val (vm, id) = open()
        vm.onContent("my typing")
        io { dao.delete(id) }
        vm.flush().done()
        val n = io { rows.notes() }.single()
        assertEquals("my typing", n.content)
        vm.onContent("my typing, more")
        vm.flush().done()
        assertEquals("my typing, more", io { rows.notes() }.single().content)
    }

    @Test fun noteDeletedElsewhereWithNoTypingStaysDeleted() {
        val (vm, id) = open()
        io { dao.delete(id) }
        repeat(20) { idle(); Thread.sleep(5) }
        assertEquals("base", vm.content)
        vm.flush().done()
        assertTrue(io { rows.notes() }.isEmpty())
    }

    @Test fun aPageWhoseScrollIsGoneComesBackAtTheTopLevel() {
        val parent = io { dao.insert(Note(title = "scroll", content = "")) }
        val page = io { dao.insert(Note(title = "page", content = "p", parentId = parent)) }
        val vm = NoteEditorViewModel(host, page)
        until("loaded") { vm.loaded }
        vm.onContent("typed")
        io { dao.delete(parent) }
        vm.flush().done()
        assertNull(io { rows.notes() }.single().parentId) // anything else would be invisible in the list
    }

    @Test fun aRoundSavesPendingTypingAndBothSidesOfAConflictSurvive() {
        platform.key = null
        val (vm, id) = open()
        until("signed in") { host.sync.ui.value.signedIn }
        host.sync.unlock("correct horse battery")
        until("unlocked") { host.sync.ui.value.unlocked }
        val key = platform.key!!
        val state = SyncStore(tmp.newFolder())
        val vaultId = drive.list().single().id
        val other = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).addCallback(SYNC_TRIGGERS_CALLBACK).build()
        state.state = state.state.copy(vaultId = vaultId)

        fun round() {
            val before = SyncStore(platform.stateDir).state.lastSyncAt
            host.sync.syncNow()
            until("round") { SyncStore(platform.stateDir).state.lastSyncAt != before }
            until("idle") { host.sync.ui.value.working == null }
        }

        round()
        io { syncRound(other, state, drive, key) }
        io { other.syncDao().notes().single().let { other.noteDao().updateText(it.id, it.title, "from the phone", 1) } }
        io { syncRound(other, state, drive, key) }
        Thread.sleep(3)

        vm.onContent("typed, not saved yet")
        round()
        assertEquals("typed, not saved yet", vm.content)
        assertEquals("typed, not saved yet", io { dao.get(id) }!!.content)
        assertEquals(setOf("typed, not saved yet", "from the phone"), io { rows.notes() }.map { it.content }.toSet())

        io { syncRound(other, state, drive, key) }
        assertEquals(setOf("typed, not saved yet", "from the phone"), io { other.syncDao().notes() }.map { it.content }.toSet())
        other.close()
    }

    @Test fun anEditorKeptOpenAfterAMergedChangeDoesNotPutTheOldTextBack() {
        val (vm, id) = open()
        remoteEdit(id, "newer")
        until("refresh") { vm.content == "newer" }
        val at = io { dao.get(id) }!!.syncAt
        vm.flush().done() // nothing typed
        assertEquals(at, io { dao.get(id) }!!.syncAt)
        assertEquals("newer", io { dao.get(id) }!!.content)
    }

    private fun lobe() = io { host.db.brainDao().insertLobe(Lobe(name = "L", colorIndex = 0, slot = 0)) }
    private fun heldIdea(lobe: Long) = io {
        val id = host.db.brainDao().insertIdea(Idea(lobeId = lobe, title = "held", body = "old body"))
        rows.ideas().single { it.id == id }
    }

    @Test fun savingAHeldIdeaKeepsABodyAndLinkChangedBySync() {
        val vm = BrainViewModel(host)
        val held = heldIdea(lobe())
        io { rows.update(held.copy(body = "newer body", noteId = 7, syncAt = held.syncAt + 1000)) }
        vm.saveIdea(held, "held, edited").done()
        val row = io { rows.ideas() }.single()
        assertEquals("held, edited", row.title)
        assertEquals("newer body", row.body)
        assertEquals(7L, row.noteId)
    }

    @Test fun anUnchangedIdeaDialogWritesNothing() {
        val vm = BrainViewModel(host)
        val held = heldIdea(lobe())
        io { rows.update(held.copy(title = "newer", syncAt = held.syncAt + 1000)) }
        val at = io { rows.ideas() }.single().syncAt
        vm.saveIdea(held, "newer").done()
        assertEquals(at, io { rows.ideas() }.single().syncAt)
    }

    @Test fun aHeldIdeaWhoseTitleChangedBySyncKeepsBothTitles() {
        val vm = BrainViewModel(host)
        val held = heldIdea(lobe())
        io { rows.update(held.copy(title = "from the phone", syncAt = held.syncAt + 1000)) }
        vm.saveIdea(held, "mine").done()
        assertEquals(setOf("mine", "from the phone (other copy)"), io { rows.ideas() }.map { it.title }.toSet())
    }

    @Test fun anIdeaDeletedElsewhereGetsARowBackAndWithItsLobeGoneBecomesANote() {
        val vm = BrainViewModel(host)
        val l = lobe()
        val held = heldIdea(l)
        io { host.db.brainDao().deleteIdea(held.id) }
        vm.saveIdea(held, "still wanted").done()
        assertEquals("still wanted", io { rows.ideas() }.single().title)
        val again = io { rows.ideas() }.single()
        io { host.db.brainDao().deleteLobe(l) }
        vm.saveIdea(again, "really wanted").done()
        assertEquals("really wanted", io { rows.notes() }.single().title)
    }

    private fun heldPlan() = io {
        val id = host.db.moonsDao().insertPlan(Plan(day = 20000, text = "held"))
        rows.plans().single { it.id == id }
    }

    @Test fun tickingAStalePlanKeepsItsNewerText() {
        val vm = MoonsViewModel(host)
        val held = heldPlan()
        io { rows.update(held.copy(text = "newer", syncAt = held.syncAt + 1000)) }
        vm.setDone(held, true).done()
        val row = io { rows.plans() }.single()
        assertEquals("newer", row.text)
        assertTrue(row.done)
    }

    @Test fun editingAStalePlanKeepsBothTextsAndItsDoneMark() {
        val vm = MoonsViewModel(host)
        val held = heldPlan()
        io { rows.update(held.copy(text = "from the phone", done = true, syncAt = held.syncAt + 1000)) }
        vm.editPlan(held, "mine").done()
        val all = io { rows.plans() }
        assertTrue(all.single { it.id == held.id }.done)
        assertEquals("mine", all.single { it.id == held.id }.text)
        assertEquals("from the phone (other copy)", all.single { it.id != held.id }.text)
    }

    @Test fun aPlanDeletedElsewhereKeepsTheEditAsARow() {
        val vm = MoonsViewModel(host)
        val held = heldPlan()
        io { host.db.moonsDao().deletePlan(held.id) }
        vm.editPlan(held, "still wanted").done()
        assertNotNull(io { rows.plans() }.singleOrNull { it.text == "still wanted" })
    }
}
