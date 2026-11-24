package app.vesper.sync

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.vesper.BrainViewModel
import app.vesper.IdeaEditorViewModel
import app.vesper.Settings
import app.vesper.ShrineMedia
import app.vesper.Vault
import app.vesper.VesperHost
import app.vesper.data.AppDatabase
import app.vesper.data.Branch
import app.vesper.data.Idea
import app.vesper.data.Lobe
import app.vesper.data.SYNC_TRIGGERS_CALLBACK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
    private val rows get() = host.db.syncDao()

    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun until(what: String, done: () -> Boolean) = waitUntil(what, ::idle, done)
    // Pumps the looper: a save can wait on a refresh that resumes on the main thread, which is this test thread.
    private fun Job.done() = until("save") { isCompleted }

    @After fun close() {
        host.appScope.cancel()
        host.db.close()
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

    @Test fun theDialogSavesNoteLinkAndGroup() {
        val vm = BrainViewModel(host)
        val l = lobe()
        val held = heldIdea(l)
        val group = io { host.db.brainDao().insertBranch(Branch(lobeId = l, name = "Read")) }
        vm.saveIdea(held, "held", " new note ", " example.org ", group).done()
        val row = io { rows.ideas() }.single()
        assertEquals(listOf("new note", "example.org"), listOf(row.body, row.link))
        assertEquals(group, row.branchId)
    }

    @Test fun moveToAGroupDeletedMeanwhileLandsOnTheLobe() {
        val vm = BrainViewModel(host)
        val l = lobe()
        val held = heldIdea(l)
        val group = io { host.db.brainDao().insertBranch(Branch(lobeId = l, name = "Read")) }
        io { host.db.brainDao().deleteBranch(group) }
        vm.saveIdea(held, "held", held.body, "x", group).done()
        val row = io { rows.ideas() }.single()
        assertNull(row.branchId)
        assertEquals("x", row.link)
    }

    @Test fun aHeldIdeaWhoseNoteChangedBySyncKeepsBothNotes() {
        val vm = BrainViewModel(host)
        val held = heldIdea(lobe())
        io { rows.update(held.copy(body = "from the phone", syncAt = held.syncAt + 1000)) }
        vm.saveIdea(held, "held", "mine").done()
        assertEquals(setOf("mine", "from the phone"), io { rows.ideas() }.map { it.body }.toSet())
    }

    @Test fun deletingAGroupKeepsItsItems() {
        val vm = BrainViewModel(host)
        val l = lobe()
        val held = heldIdea(l)
        val group = io { host.db.brainDao().insertBranch(Branch(lobeId = l, name = "Read")) }
        vm.saveIdea(held, "held", held.body, null, group).done()
        io { host.db.brainDao().deleteBranch(group) }
        val row = io { rows.ideas() }.single()
        assertEquals("held" to null, row.title to row.branchId)
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

    // ---- an item's own page (IdeaEditorViewModel) ----
    private fun openIdea(): Pair<IdeaEditorViewModel, Long> {
        val l = lobe()
        val id = io { host.db.brainDao().insertIdea(Idea(lobeId = l, title = "held", body = "old body")) }
        val vm = IdeaEditorViewModel(host, id, l, null)
        until("loaded") { vm.loaded }
        return vm to id
    }

    private fun remoteIdea(id: Long, body: String) = io {
        val n = rows.ideas().single { it.id == id }
        rows.update(n.copy(body = body, syncAt = n.syncAt + 1000))
    }

    @Test fun anIdlePageShowsTheRemoteChange() {
        val (vm, id) = openIdea()
        remoteIdea(id, "from the phone")
        until("refresh") { vm.body == "from the phone" }
        vm.flush().done()
        assertEquals("from the phone", io { rows.ideas() }.single().body) // no copy, no conflict
    }

    @Test fun typingOnAnOpenPageSurvivesARemoteChangeAndTheOtherSideBecomesACopy() {
        val (vm, id) = openIdea()
        vm.onBody("my typing")
        remoteIdea(id, "from the phone")
        repeat(20) { idle(); Thread.sleep(5) }
        assertEquals("my typing", vm.body)
        vm.flush().done()
        assertEquals("my typing", vm.body)
        val all = io { rows.ideas() }
        assertEquals("my typing", all.single { it.id == id }.body)
        val copy = all.single { it.id != id }
        assertEquals("from the phone", copy.body)
        assertEquals("held (other copy)", copy.title)
    }

    @Test fun aPageThatTypesAfterARefreshBuildsOnTheNewText() {
        val (vm, id) = openIdea()
        remoteIdea(id, "from the phone")
        until("refresh") { vm.body == "from the phone" }
        vm.onBody("from the phone, and more")
        vm.flush().done()
        assertEquals(listOf("from the phone, and more"), io { rows.ideas() }.map { it.body })
    }

    @Test fun anIdeaDeletedElsewhereWhileTypingGetsARowBackAndWithoutTypingStaysDeleted() {
        val (vm, id) = openIdea()
        io { host.db.brainDao().deleteIdea(id) }
        vm.flush().done() // nothing typed
        assertEquals(0, io { rows.ideas() }.size)
        vm.onBody("typed anyway")
        vm.flush().done()
        assertEquals(listOf("typed anyway"), io { rows.ideas() }.map { it.body })
    }

    @Test fun aNewPageStaysUnwrittenWhileBlankAndLandsInItsGroupOnceWritten() {
        val l = lobe()
        val group = io { host.db.brainDao().insertBranch(Branch(lobeId = l, name = "Read")) }
        val vm = IdeaEditorViewModel(host, null, l, group)
        vm.flush().done()
        assertEquals(0, io { rows.ideas() }.size)
        vm.onBody("   ")
        vm.flush().done()
        assertEquals(0, io { rows.ideas() }.size)
        vm.onTitle("first")
        vm.flush().done()
        val row = io { rows.ideas() }.single()
        assertEquals(listOf<Any?>("first", l, group), listOf(row.title, row.lobeId, row.branchId))
        vm.onBody("then more")
        vm.flush().done()
        assertEquals(listOf("then more"), io { rows.ideas() }.map { it.body }) // the same row, not a second
    }

    @Test fun aRoundSavesTextTypedOnAnOpenPage() {
        platform.key = null
        val (vm, id) = openIdea()
        until("signed in") { host.sync.ui.value.signedIn }
        host.sync.unlock("correct horse battery")
        until("unlocked") { host.sync.ui.value.unlocked }
        vm.onBody("typed, not saved yet")
        val before = SyncStore(platform.stateDir).state.lastSyncAt
        host.sync.syncNow()
        until("round") { SyncStore(platform.stateDir).state.lastSyncAt != before }
        assertEquals("typed, not saved yet", io { rows.ideas() }.single { it.id == id }.body)
    }

    @Test fun burningAPageDeletesTheItem() {
        val (vm, _) = openIdea()
        vm.delete()
        until("gone") { io { rows.ideas() }.isEmpty() }
        vm.flush().done()
        assertEquals(0, io { rows.ideas() }.size)
    }

    // ---- moving an item (Move to...) ----
    private fun move(vm: IdeaEditorViewModel, lobe: Long, group: Long?): String? {
        val job = host.appScope.async { vm.moveTo(lobe, group) }
        until("move") { job.isCompleted }
        @Suppress("OPT_IN_USAGE") return job.getCompleted()
    }

    @Test fun movingSavesTypedTextFirstAndLandsInTheOtherLobesGroup() {
        val (vm, id) = openIdea()
        val brain = host.db.brainDao()
        val other = io { brain.insertLobe(Lobe(name = "Coding", colorIndex = 2, slot = 5)) }
        val group = io { brain.insertBranch(Branch(lobeId = other, name = "Vesper")) }
        val before = io { rows.ideas().single { it.id == id } }
        vm.onBody("typed just now") // not saved yet: the move must carry it
        Thread.sleep(3)
        assertEquals("Coding › Vesper", move(vm, other, group))
        val row = io { rows.ideas().single { it.id == id } }
        assertEquals(listOf<Any?>("typed just now", other, group), listOf(row.body, row.lobeId, row.branchId))
        assertTrue(row.syncAt > before.syncAt && row.updatedAt > before.updatedAt)
        assertEquals(other, vm.lobeId)
        // then loose on the lobe
        assertEquals("Coding", move(vm, other, null))
        assertNull(io { rows.ideas().single { it.id == id } }.branchId)
    }

    @Test fun aGroupOfAnotherLobeOrAGoneLobeRefusesTheMove() {
        val (vm, id) = openIdea()
        val brain = host.db.brainDao()
        val other = io { brain.insertLobe(Lobe(name = "Coding", colorIndex = 2, slot = 5)) }
        val foreign = io { brain.insertBranch(Branch(lobeId = vm.lobeId, name = "Stays")) }
        val home = vm.lobeId
        assertNull(move(vm, other, foreign))
        assertNull(move(vm, 9999L, null))
        assertEquals(home, io { rows.ideas().single { it.id == id } }.lobeId)
    }
}
