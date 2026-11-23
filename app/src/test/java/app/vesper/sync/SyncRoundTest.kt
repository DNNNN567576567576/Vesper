package app.vesper.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.vesper.data.AppDatabase
import app.vesper.data.Branch
import app.vesper.data.Goal
import app.vesper.data.Step
import app.vesper.data.Idea
import app.vesper.data.Lobe
import app.vesper.data.Note
import app.vesper.data.Plan
import app.vesper.data.SYNC_TRIGGERS_CALLBACK
import app.vesper.data.Task
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drive stand-in: stores what a device uploads and bumps a version per write, like the real folder. */
internal class MemoryDrive : SyncTransport {
    class F(var bytes: ByteArray, var version: Int, val created: Int)
    val files = linkedMapOf<RemoteFile, F>()
    var uploads = 0
    var failAfterDownload = false
    private var clock = 0

    private fun remote(id: String) = files.keys.first { it.id == id }
    private fun view(k: RemoteFile, f: F) = k.copy(version = f.version.toString(), createdTime = "%06d".format(f.created))

    override fun list() = files.map { (k, f) -> view(k, f) }
    override fun download(id: String) = files.getValue(remote(id)).bytes
    override fun create(name: String, bytes: ByteArray): RemoteFile {
        uploads++
        val k = RemoteFile("id${++clock}", name)
        val f = F(bytes, 1, clock)
        files[k] = f
        return view(k, f)
    }
    override fun update(id: String, bytes: ByteArray): RemoteFile {
        uploads++
        val k = remote(id)
        val f = files.getValue(k).also { it.bytes = bytes; it.version++ }
        return view(k, f)
    }
    override fun delete(id: String) { files.remove(remote(id)) }
}

/** Two real Room databases with the real triggers, talking through full rounds of seal/merge/upload. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncRoundTest {
    @get:Rule val tmp = TemporaryFolder()

    private var drive = MemoryDrive()
    private val key = newDataKey()

    private inner class Device(val name: String) {
        val db: AppDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .addCallback(SYNC_TRIGGERS_CALLBACK).build()
        val store = SyncStore(tmp.newFolder())
        suspend fun sync() = syncRound(db, store, drive, key)
        suspend fun state() = db.exportSnapshot("same") // deviceId out of the comparison
        suspend fun notes() = db.syncDao().notes()
    }

    private val devices = mutableListOf<Device>()
    private fun device(name: String) = Device(name).also { it.store.state = it.store.state.copy(vaultId = "v"); devices += it }

    /** Rounds until both are equal and a whole extra pass uploads nothing. */
    private suspend fun converge(a: Device, b: Device): Int {
        repeat(8) { round ->
            a.sync(); b.sync()
            val before = drive.uploads
            a.sync(); b.sync()
            if (a.state() == b.state() && drive.uploads == before) return round + 1
        }
        error("did not converge:\n${a.state()}\n${b.state()}")
    }

    private fun test(block: suspend () -> Unit) = runBlocking(Dispatchers.IO) {
        drive.create(VAULT_FILE, wrapKey(key, "correct horse battery", 100_000)).also { devicesVault = it.id }
        block()
    }
    private var devicesVault = ""
    private fun Device.linkVault() { store.state = store.state.copy(vaultId = devicesVault) }
    private suspend fun pair(): Pair<Device, Device> = device("a").also { it.linkVault() } to device("b").also { it.linkVault() }
    private fun tick() = Thread.sleep(3) // edits on one clock must get distinct ms

    @After fun close() = devices.forEach { it.db.close() }

    @Test fun phoneWithDataFillsAnEmptyDesktop() = test {
        val (a, b) = pair()
        val parent = a.db.noteDao().insert(Note(title = "scroll", content = "c"))
        a.db.noteDao().insert(Note(title = "page", content = "p", parentId = parent))
        val lobe = a.db.brainDao().insertLobe(Lobe(name = "Work", colorIndex = 1, slot = 2))
        a.db.brainDao().insertIdea(Idea(lobeId = lobe, title = "idea", body = "b", noteId = parent))
        val task = a.db.taskDao().insert(Task(text = "t"))
        a.db.taskDao().snuff(task, 123)
        a.db.moonsDao().insertPlan(Plan(day = 20000, text = "plan", done = true))

        converge(a, b)

        val page = b.notes().single { it.title == "page" }
        val scroll = b.notes().single { it.title == "scroll" }
        assertEquals(scroll.id, page.parentId)
        val idea = b.db.syncDao().ideas().single()
        assertEquals(scroll.id, idea.noteId)
        assertEquals(b.db.syncDao().lobes().single().id, idea.lobeId)
        assertEquals(123L, b.db.taskDao().observeSnuffed().first().single().snuffedAt)
        assertTrue(b.db.syncDao().plans().single().done)
        assertEquals(a.state(), b.state())
    }

    @Test fun bothWithDataGiveTheUnion() = test {
        val (a, b) = pair()
        a.db.noteDao().insert(Note(title = "A", content = ""))
        b.db.noteDao().insert(Note(title = "B", content = ""))
        converge(a, b)
        assertEquals(setOf("A", "B"), a.notes().map { it.title }.toSet())
    }

    @Test fun sameUidOnBothWithDifferentTextLosesNothing() = test {
        val (a, b) = pair()
        a.db.noteDao().insert(Note(title = "t", content = "one", uid = "notes-1"))
        tick()
        b.db.noteDao().insert(Note(title = "t", content = "two", uid = "notes-1"))
        converge(a, b)
        assertEquals(setOf("one", "two"), a.notes().map { it.content }.toSet())
        assertEquals(2, a.notes().size)
    }

    @Test fun bothEditedSameNoteConvergesWithExactlyOneCopy() = test {
        val (a, b) = pair()
        val id = a.db.noteDao().insert(Note(title = "t", content = "base"))
        converge(a, b)
        val bid = b.notes().single().id
        a.db.noteDao().updateText(id, "t", "edit A", 1)
        tick()
        b.db.noteDao().updateText(bid, "t", "edit B", 2)
        converge(a, b)
        assertEquals(setOf("edit A", "edit B"), a.notes().map { it.content }.toSet())
        assertEquals(1, a.notes().count { it.title == "t (other copy)" })
        assertEquals("edit A", a.notes().single { it.title == "t (other copy)" }.content)
    }

    /** Scenario from the report: B edits offline, A syncs, A edits, B syncs and pushes, A syncs. Perfect clocks. */
    @Test fun offlineEditMadeBeforeThePeersLastSyncSurvives() = test {
        val (a, b) = pair()
        val id = a.db.noteDao().insert(Note(title = "t", content = "base"))
        converge(a, b)
        b.db.noteDao().updateText(b.notes().single().id, "t", "offline on B", 2); tick()
        a.sync(); tick() // A's last sync is now later than B's edit
        a.db.noteDao().updateText(id, "t", "later on A", 1); tick()
        b.sync() // B sees nothing new from A and pushes its edit
        a.sync()
        converge(a, b)
        assertEquals(setOf("offline on B", "later on A"), a.notes().map { it.content }.toSet())
    }

    @Test fun deleteAndCascadesPropagate() = test {
        val (a, b) = pair()
        val parent = a.db.noteDao().insert(Note(title = "scroll", content = ""))
        a.db.noteDao().insert(Note(title = "page", content = "", parentId = parent))
        val lobe = a.db.brainDao().insertLobe(Lobe(name = "Work", colorIndex = 1, slot = 2))
        a.db.brainDao().insertIdea(Idea(lobeId = lobe, title = "i1", body = ""))
        a.db.brainDao().insertIdea(Idea(lobeId = lobe, title = "i2", body = ""))
        converge(a, b)
        assertEquals(2, b.db.syncDao().ideas().size)
        tick()
        a.db.noteDao().delete(parent)
        a.db.brainDao().deleteLobe(lobe)
        converge(a, b)
        assertTrue(b.notes().isEmpty())
        assertTrue(b.db.syncDao().lobes().isEmpty())
        assertTrue(b.db.syncDao().ideas().isEmpty())
        assertEquals(a.db.syncDao().tombstones().toSet(), b.db.syncDao().tombstones().toSet())
        assertEquals(5, b.db.syncDao().tombstones().size) // kept, with the deleter's own times
    }

    @Test fun anOfflineEditOutlivesALaterDeleteThatNeverSawIt() = test {
        val (a, b) = pair()
        val id = a.db.noteDao().insert(Note(title = "t", content = "base"))
        converge(a, b)
        b.db.noteDao().updateText(b.notes().single().id, "t", "offline on B", 2); tick()
        a.db.noteDao().delete(id); tick()
        a.sync()
        b.sync()
        assertEquals("offline on B", b.notes().single().content)
        converge(a, b)
        assertEquals("offline on B", a.notes().single().content)
        assertTrue(a.db.syncDao().tombstones().isEmpty())
    }

    @Test fun aRowThePeerSawAndThenDeletedStaysDeleted() = test {
        val (a, b) = pair()
        b.db.noteDao().insert(Note(title = "t", content = "made on B"))
        b.sync(); a.sync(); tick()
        a.db.noteDao().delete(a.notes().single().id); tick()
        a.sync(); b.sync()
        converge(a, b)
        assertTrue(a.notes().isEmpty())
        assertTrue(b.notes().isEmpty())
    }

    @Test fun bothEditedThePlanTextKeepsTheLoser() = test {
        val (a, b) = pair()
        val id = a.db.moonsDao().insertPlan(Plan(day = 20000, text = "base"))
        converge(a, b)
        a.db.moonsDao().setPlanText(id, "edit A"); tick()
        b.db.moonsDao().setPlanText(b.db.syncDao().plans().single().id, "edit B")
        converge(a, b)
        assertEquals(setOf("edit B", "edit A (other copy)"), a.db.syncDao().plans().map { it.text }.toSet())
    }

    /** The "Kurumi update" report: typing on one device while the other echoes each older version back must make no copy. */
    @Test fun editingOnOneDeviceAcrossRoundsMakesNoCopy() = test {
        val (a, b) = pair()
        val lobe = a.db.brainDao().insertLobe(Lobe(name = "Tasks", colorIndex = 0, slot = 0))
        val idea = a.db.brainDao().insertIdea(Idea(lobeId = lobe, title = "Kurumi update", body = "v0"))
        val note = a.db.noteDao().insert(Note(title = "page", content = "v0"))
        val plan = a.db.moonsDao().insertPlan(Plan(day = 20000, text = "v0"))
        converge(a, b)
        for (i in 1..6) {
            tick()
            a.db.brainDao().setIdeaFields(idea, "Kurumi update", "v$i", null, null, i.toLong())
            a.db.noteDao().updateText(note, "page", "v$i", i.toLong())
            a.db.moonsDao().setPlanText(plan, "v$i")
            a.sync() // takes in b's echo of the previous version, then pushes this one
            b.sync()
        }
        converge(a, b)
        for (d in listOf(a, b)) {
            assertEquals(listOf("v6"), d.db.syncDao().ideas().map { it.body })
            assertEquals(listOf("v6"), d.notes().map { it.content })
            assertEquals(listOf("v6"), d.db.syncDao().plans().map { it.text })
        }
    }

    /** B pushed an older version of its own before it ever saw A's edit: a real conflict, still kept as a copy. */
    @Test fun aRealConflictStillCopiesAfterOneSidedRounds() = test {
        val (a, b) = pair()
        val id = a.db.noteDao().insert(Note(title = "t", content = "base"))
        converge(a, b)
        val bid = b.notes().single().id
        b.db.noteDao().updateText(bid, "t", "edit B", 1)
        tick()
        a.db.noteDao().updateText(id, "t", "edit A", 2)
        a.sync()
        b.sync() // merges A's newer edit: B's text loses and B makes the copy itself
        a.sync()
        converge(a, b)
        assertEquals(setOf("edit A", "edit B"), a.notes().map { it.content }.toSet())
        assertEquals("edit B", a.notes().single { it.title == "t (other copy)" }.content)
        assertEquals(a.state(), b.state())
    }

    @Test fun onlyOneSideTickingAPlanMakesNoCopy() = test {
        val (a, b) = pair()
        a.db.moonsDao().insertPlan(Plan(day = 20000, text = "base"))
        converge(a, b)
        b.db.moonsDao().setPlanDone(b.db.syncDao().plans().single().id, true)
        converge(a, b)
        assertTrue(a.db.syncDao().plans().single().done)
    }

    /** A backup put in place: the rows it held, nothing else, then what duringRestore does to the sync state. */
    private suspend fun Device.restore(rows: List<Note>) {
        notes().forEach { db.noteDao().delete(it.id) }
        db.syncDao().clearTombstones()
        rows.forEach { db.syncDao().insert(it.copy(id = 0)) }
        store.state = store.state.afterRestore()
    }

    @Test fun aRestoreKeepsTheNoteItBroughtBackAgainstAnOlderDelete() = test {
        val (a, b) = pair()
        a.db.noteDao().insert(Note(title = "wanted", content = "dear"))
        converge(a, b)
        val backup = a.notes(); tick()
        a.db.noteDao().delete(a.notes().single().id)
        converge(a, b)
        assertTrue(b.notes().isEmpty())
        a.restore(backup)
        converge(a, b)
        assertEquals("dear", a.notes().single().content)
        assertEquals("dear", b.notes().single().content)
    }

    @Test fun aRestoredDeviceGetsThePeersNewerWorkBackAndLosesNoText() = test {
        val (a, b) = pair()
        a.db.noteDao().insert(Note(title = "t", content = "old"))
        converge(a, b)
        val backup = a.notes(); tick()
        b.db.noteDao().updateText(b.notes().single().id, "t", "newer", 2)
        b.db.noteDao().insert(Note(title = "made after the backup", content = ""))
        converge(a, b)
        a.restore(backup)
        val state = a.store.state
        assertTrue(state.peerVersions.isEmpty())
        converge(a, b)
        assertEquals(setOf("old", "newer", ""), a.notes().map { it.content }.toSet())
        assertEquals(a.state(), b.state())
    }

    @Test fun aNewVaultRewritesOurOwnFileEvenWhenNothingChanged() = test {
        val (a, _) = pair()
        a.db.noteDao().insert(Note(title = "t", content = "c"))
        a.sync()
        drive.delete(devicesVault) // the file under the old key is left, as when a device raced a reset
        val joined = runCatching { a.sync() }
        assertTrue(joined.exceptionOrNull() is VaultChanged)
        val newVault = drive.create(VAULT_FILE, wrapKey(newDataKey(), "another good passphrase", 100_000))
        val newKey = openOrCreateVault(a.store, drive, "another good passphrase")
        assertEquals(newVault.id, a.store.state.vaultId)
        syncRound(a.db, a.store, drive, newKey)
        val own = drive.list().single { it.name == snapName(a.store.state.deviceId) }
        open(newKey, own.name, drive.download(own.id)) // would throw on the old key's bytes
    }

    @Test fun aResetThatStoppedHalfwayIsCleanedUpByTheNextVault() = test {
        val (a, _) = pair()
        a.sync()
        drive.delete(devicesVault) // vault gone, snapshot left behind
        openOrCreateVault(a.store, drive, "another good passphrase")
        assertEquals(listOf(VAULT_FILE), drive.list().map { it.name })
    }

    @Test fun resetVaultRemovesEverythingAndKeepsLocalRowsAndSeen() = test {
        val (a, b) = pair()
        a.db.noteDao().insert(Note(title = "t", content = "c"))
        converge(a, b)
        val seen = b.store.state.seen
        val deviceId = b.store.state.deviceId
        resetVault(b.store, drive)
        assertTrue(drive.list().isEmpty())
        assertEquals(1, b.notes().size)
        assertEquals(seen, b.store.state.seen)
        assertEquals(deviceId, b.store.state.deviceId)
        assertEquals(null, b.store.state.vaultId)
    }

    @Test fun lobesMadeSeparatelyEndUpOnTheirOwnSlots() = test {
        val (a, b) = pair()
        a.db.brainDao().insertLobe(Lobe(name = "Work", colorIndex = 0, slot = 0))
        b.db.brainDao().insertLobe(Lobe(name = "Home", colorIndex = 0, slot = 0))
        converge(a, b)
        assertEquals(2, a.db.syncDao().lobes().map { it.slot }.toSet().size)
    }

    @Test fun lobeDeletedHereWhileAnIdeaWasEditedThereKeepsTheIdea() = test {
        val (a, b) = pair()
        val lobe = a.db.brainDao().insertLobe(Lobe(name = "Work", colorIndex = 1, slot = 2))
        a.db.brainDao().insertIdea(Idea(lobeId = lobe, title = "i", body = "", uid = "idea"))
        converge(a, b)
        a.db.brainDao().deleteLobe(lobe); tick()
        b.db.brainDao().setIdeaFields(b.db.syncDao().ideas().single().id, "edited", "", null, null, 2)
        converge(a, b)
        assertEquals("edited", a.db.syncDao().ideas().single().title)
        assertEquals("Work", a.db.syncDao().lobes().single().name)
        assertTrue(a.db.syncDao().tombstones().isEmpty())
    }

    @Test fun editAfterDeleteRevives() = test {
        val (a, b) = pair()
        val id = a.db.noteDao().insert(Note(title = "t", content = "base"))
        converge(a, b)
        a.db.noteDao().delete(id); tick()
        b.db.noteDao().updateText(b.notes().single().id, "t", "still wanted", 2)
        converge(a, b)
        assertEquals("still wanted", a.notes().single().content)
        assertTrue(a.db.syncDao().tombstones().isEmpty())
        assertTrue(b.db.syncDao().tombstones().isEmpty())
    }

    @Test fun pageAndParentArrivingTogetherLinkUp() = test {
        val (a, b) = pair()
        // uids that sort the page first, so only the insert ordering can get this right
        val parent = a.db.noteDao().insert(Note(title = "scroll", content = "", uid = "z-parent"))
        a.db.noteDao().insert(Note(title = "page", content = "", parentId = parent, uid = "a-page"))
        converge(a, b)
        assertEquals(b.notes().single { it.uid == "z-parent" }.id, b.notes().single { it.uid == "a-page" }.parentId)
    }

    @Test fun anAppliedRowDoesNotEcho() = test {
        val (a, b) = pair()
        a.db.noteDao().insert(Note(title = "t", content = "c"))
        a.sync(); b.sync()
        assertEquals(a.state().notes, b.state().notes) // syncAt unchanged by the triggers
        assertTrue(b.db.syncDao().tombstones().isEmpty())
        val before = drive.uploads
        val snapB = drive.list().first { it.name == snapName(b.store.state.deviceId) }.version
        a.sync(); b.sync(); a.sync(); b.sync()
        assertEquals(before, drive.uploads) // merged the identical state: nobody uploads again
        assertEquals(snapB, drive.list().first { it.name == snapName(b.store.state.deviceId) }.version)
    }

    @Test fun anExactTieSettlesOnTheLargerDeviceAfterOneEcho() = test {
        val (a, b) = pair()
        val ids = listOf(a, b).sortedBy { it.store.state.deviceId }
        ids[0].db.noteDao().insert(Note(title = "t", content = "low", uid = "n", syncAt = 5000))
        ids[1].db.noteDao().insert(Note(title = "t", content = "high", uid = "n", syncAt = 5000))
        converge(a, b)
        assertEquals("high", a.notes().single { it.uid == "n" }.content)
        assertEquals("low", a.notes().single { it.uid != "n" }.content) // first sync, so the loser became a copy
    }

    @Test fun reapplyingTheSameSnapshotIsANoOp() = test {
        val (a, b) = pair()
        a.db.noteDao().insert(Note(title = "t", content = "c"))
        a.sync(); b.sync()
        val state = b.state()
        val snap = a.db.exportSnapshot(a.store.state.deviceId)
        assertFalse(b.db.mergeIn(snap, b.store.state.deviceId, b.store.state.seen))
        assertFalse(b.db.mergeIn(snap, b.store.state.deviceId, emptyMap()))
        assertEquals(state, b.state())
    }

    @Test fun aFailureLeavesTheDbAsItWas() = test {
        val (a, _) = pair()
        a.db.noteDao().insert(Note(title = "t", content = "keep me"))
        val before = a.state()
        val damaged = Snapshot(deviceId = "z", notes = listOf(SyncNote("new", 9, "x", "x", 9, 0)), ideas = listOf(SyncIdea("i", 9, "ghost-lobe", "", "", null, 9)), tombstones = listOf(SyncTomb("notes", a.notes().single().uid, Long.MAX_VALUE)))
        assertThrows(SyncException::class.java) { runBlocking { a.db.mergeIn(damaged, a.store.state.deviceId, emptyMap()) } }
        assertEquals(before, a.state())
    }

    /** Tasks are written after the deletes, lobes, notes and ideas, so this failure lands in the middle of the apply. */
    @Test fun aFailureHalfwayThroughTheWritesRollsEverythingBack() = test {
        val (a, _) = pair()
        val keep = a.db.noteDao().insert(Note(title = "t", content = "keep me"))
        a.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER boom BEFORE INSERT ON tasks WHEN NEW.text = 'boom' BEGIN SELECT RAISE(ABORT, 'boom'); END")
        val before = a.state()
        val remote = Snapshot(
            deviceId = "z",
            notes = listOf(SyncNote("new", 9, "x", "x", 9, 0)),
            lobes = listOf(SyncLobe("L", 9, "x", 0, 0)),
            ideas = listOf(SyncIdea("i", 9, "L", "", "", null, 9)),
            tasks = listOf(SyncTask("t", 9, "boom")),
            tombstones = listOf(SyncTomb("notes", a.db.noteDao().get(keep)!!.uid, Long.MAX_VALUE)),
        )
        assertTrue(runCatching { a.db.mergeIn(remote, a.store.state.deviceId, emptyMap()) }.isFailure)
        assertEquals(before, a.state())
    }

    @Test fun aTamperedPeerFileChangesNothingAndNoCursorMoves() = test {
        val (a, b) = pair()
        a.db.noteDao().insert(Note(title = "t", content = "c"))
        a.sync()
        val k = drive.files.keys.first { it.name.startsWith("snap-") }
        drive.files.getValue(k).bytes.let { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(SyncException::class.java) { runBlocking { b.sync() } }
        assertTrue(b.notes().isEmpty())
        assertTrue(b.store.state.peerVersions.isEmpty())
    }

    @Test fun aSnapshotInAnotherDevicesFileIsRefused() = test {
        val (a, b) = pair()
        val fake = Snapshot(v = SNAPSHOT_VERSION, deviceId = "someone-else")
        val name = snapName(a.store.state.deviceId)
        drive.create(name, seal(key, name, kotlinx.serialization.json.Json.encodeToString(Snapshot.serializer(), fake).toByteArray()))
        assertThrows(SyncException::class.java) { runBlocking { b.sync() } }
    }

    private fun upload(id: String, snap: Snapshot) {
        val name = snapName(id)
        drive.create(name, seal(key, name, kotlinx.serialization.json.Json.encodeToString(Snapshot.serializer(), snap).toByteArray()))
    }

    private fun Device.ownFile(): String {
        val name = snapName(store.state.deviceId)
        return String(open(key, name, drive.files.entries.first { it.key.name == name }.value.bytes))
    }

    private suspend fun Device.seedWeb(): Long {
        val lobe = db.brainDao().insertLobe(Lobe(name = "Religion", colorIndex = 1, slot = 0, uid = "L"))
        val read = db.brainDao().insertBranch(Branch(lobeId = lobe, name = "Read", uid = "read"))
        db.brainDao().insertIdea(Idea(lobeId = lobe, title = "Gita", body = "chapter 2", uid = "gita", branchId = read, link = "example.org/gita", done = true))
        db.brainDao().insertIdea(Idea(lobeId = lobe, title = "loose", body = "", uid = "loose"))
        return read
    }

    @Test fun groupsLinksAndDoneTravel() = test {
        val (a, b) = pair()
        a.seedWeb()
        converge(a, b)
        val idea = b.db.syncDao().ideas().single { it.uid == "gita" }
        assertEquals("Read", b.db.syncDao().branches().single { it.id == idea.branchId }.name)
        assertEquals("example.org/gita" to true, idea.link to idea.done)
        assertNull(b.db.syncDao().ideas().single { it.uid == "loose" }.branchId)
        assertEquals(a.state(), b.state())
    }

    @Test fun aDeletedGroupGoesEverywhereAndItsItemsLandOnTheLobe() = test {
        val (a, b) = pair()
        val read = a.seedWeb()
        converge(a, b)
        a.db.brainDao().deleteBranch(read)
        converge(a, b)
        assertTrue(b.db.syncDao().branches().isEmpty())
        assertNull(b.db.syncDao().ideas().single { it.uid == "gita" }.branchId)
        assertEquals("Gita", b.db.syncDao().ideas().single { it.uid == "gita" }.title)
        assertEquals(a.state(), b.state())
    }

    @Test fun anItemMovedIntoAGroupDeletedElsewhereLandsOnTheLobe() = test {
        val (a, b) = pair()
        val read = a.seedWeb()
        converge(a, b)
        a.db.brainDao().deleteBranch(read); tick()
        val loose = b.db.syncDao().ideas().single { it.uid == "loose" }
        b.db.brainDao().setIdeaFields(loose.id, "moved", "", null, b.db.syncDao().branches().single().id, 5)
        converge(a, b)
        assertTrue(a.db.syncDao().branches().isEmpty())
        assertEquals("moved" to null, a.db.syncDao().ideas().single { it.uid == "loose" }.let { it.title to it.branchId })
        assertEquals(a.state(), b.state())
    }


    @Test fun anItemMovedToAnotherLobeAndGroupLandsThereOnTheOtherDevice() = test {
        val (a, b) = pair()
        a.seedWeb()
        val code = a.db.brainDao().insertLobe(Lobe(name = "Coding", colorIndex = 2, slot = 3, uid = "C"))
        val vesper = a.db.brainDao().insertBranch(Branch(lobeId = code, name = "Vesper", uid = "vesper"))
        converge(a, b); tick()
        val gita = a.db.syncDao().ideas().single { it.uid == "gita" }
        a.db.brainDao().moveIdea(gita.id, code, vesper, System.currentTimeMillis())
        converge(a, b)
        val onB = b.db.syncDao().ideas().single { it.uid == "gita" }
        assertEquals("C" to "vesper", b.db.syncDao().lobes().single { it.id == onB.lobeId }.uid to b.db.syncDao().branches().single { it.id == onB.branchId }.uid)
        assertEquals("chapter 2", onB.body)
        assertEquals(a.state(), b.state())
        // and back to loose on the first lobe, from the other side
        tick()
        val home = b.db.syncDao().lobes().single { it.uid == "L" }.id
        b.db.brainDao().moveIdea(onB.id, home, null, System.currentTimeMillis())
        converge(a, b)
        assertEquals("L" to null, a.db.syncDao().ideas().single { it.uid == "gita" }.let { i -> a.db.syncDao().lobes().single { it.id == i.lobeId }.uid to i.branchId })
    }
    private suspend fun Device.seedGroupOf3(): Pair<Long, Long> {
        val code = db.brainDao().insertLobe(Lobe(name = "Coding", colorIndex = 2, slot = 3, uid = "C"))
        val read = db.brainDao().insertBranch(Branch(lobeId = db.syncDao().lobes().single { it.uid == "L" }.id, name = "Shelf", uid = "shelf"))
        val home = db.syncDao().lobes().single { it.uid == "L" }.id
        repeat(3) { db.brainDao().insertIdea(Idea(lobeId = home, title = "n$it", body = "b$it", uid = "n$it", branchId = read)) }
        return read to code
    }

    private suspend fun Device.landed() = db.syncDao().ideas().filter { it.uid.startsWith("n") }.associate { i ->
        i.uid to (db.syncDao().lobes().single { it.id == i.lobeId }.uid to db.syncDao().branches().firstOrNull { it.id == i.branchId }?.uid)
    }

    @Test fun aGroupMovedToAnotherLobeBringsAllItsNotesAlong() = test {
        val (a, b) = pair()
        a.seedWeb()
        val (shelf, code) = a.seedGroupOf3()
        converge(a, b); tick()
        a.db.brainDao().moveBranch(shelf, code)
        a.db.brainDao().moveBranchIdeas(shelf, code, System.currentTimeMillis())
        converge(a, b)
        assertEquals(mapOf("n0" to ("C" to "shelf"), "n1" to ("C" to "shelf"), "n2" to ("C" to "shelf")), b.landed())
        assertEquals(1, b.db.syncDao().branches().count { it.uid == "shelf" })
        assertEquals("C", b.db.syncDao().lobes().single { it.id == b.db.syncDao().branches().single { g -> g.uid == "shelf" }.lobeId }.uid)
        assertEquals(a.state(), b.state())
    }

    @Test fun anEditOnTheOtherDeviceBeforeSyncingStillConvergesInsideTheMovedGroup() = test {
        val (a, b) = pair()
        a.seedWeb()
        val (shelf, code) = a.seedGroupOf3()
        converge(a, b); tick()
        a.db.brainDao().moveBranch(shelf, code)
        a.db.brainDao().moveBranchIdeas(shelf, code, System.currentTimeMillis()); tick()
        val n1 = b.db.syncDao().ideas().single { it.uid == "n1" } // B still sees the old lobe and edits later than the move
        b.db.brainDao().setIdeaFields(n1.id, "n1 edited", "b1", null, n1.branchId, System.currentTimeMillis())
        converge(a, b)
        // both sides changed n1 (a move, a text edit): the existing conflict rule keeps both versions, and neither goes loose
        val landed = b.landed()
        assertEquals(setOf("n0", "n1", "n2"), landed.keys.filter { !it.contains('~') }.toSet())
        assertTrue(landed.values.all { it == ("C" to "shelf") })
        assertTrue(b.db.syncDao().ideas().any { it.title.contains("n1 edited") })
        assertEquals(6, b.db.syncDao().ideas().size) // gita, loose, three notes and one conflict copy
        assertEquals(a.state(), b.state())
    }

    @Test fun deletingALobeTombstonesItsGroups() = test {
        val (a, b) = pair()
        a.seedWeb()
        converge(a, b)
        a.db.brainDao().deleteLobe(a.db.syncDao().lobes().single().id)
        converge(a, b)
        assertTrue(b.db.syncDao().branches().isEmpty() && b.db.syncDao().ideas().isEmpty() && b.db.syncDao().lobes().isEmpty())
    }

    @Test fun aSnapshotKeepsItsVersionOnTheWire() = test {
        val (a, _) = pair()
        a.sync()
        assertTrue(a.ownFile().contains("\"v\":$SNAPSHOT_VERSION")) // what makes a version 1 build stop
        // files from before the bump carry no version at all: they must read as old, not as ours
        val old = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(Snapshot.serializer(), """{"deviceId":"x"}""")
        assertEquals(1, old.v)
    }

    @Test fun anOlderDevicesFileChangesNothingHereAndIsStillAnswered() = test {
        val (a, _) = pair()
        a.seedWeb()
        val before = a.state()
        // the old device edited the same idea later: merged, its row would wipe group, link and done
        val l = SyncLobe("L", 1, "Religion", 1, 0)
        upload("old-device", Snapshot(deviceId = "old-device", lobes = listOf(l), ideas = listOf(SyncIdea("gita", Long.MAX_VALUE / 2, "L", "Gita edited", "", null, 1))))
        val e = assertThrows(SyncException::class.java) { runBlocking { a.sync() } }
        assertTrue(e.message!!, e.message!!.contains("older Vesper"))
        assertEquals(before, a.state())
        assertTrue(a.ownFile().contains("example.org/gita")) // pushed anyway
    }

    /** Both updated, each holding the other's version 1 file: stopping at it would leave them stuck for good. */
    @Test fun twoUpdatedDevicesGetPastEachOthersOldFiles() = test {
        val (a, b) = pair()
        a.seedWeb()
        for (id in listOf(a.store.state.deviceId, b.store.state.deviceId)) upload(id, Snapshot(deviceId = id))
        assertThrows(SyncException::class.java) { runBlocking { a.sync() } } // b's file is old, a pushes regardless
        b.sync() // a's is new now, so b has nothing to complain about
        a.sync()
        assertEquals(a.state(), b.state())
        assertEquals("example.org/gita", b.db.syncDao().ideas().single { it.uid == "gita" }.link)
    }

    @Test fun aNewerDevicesFileStopsTheRoundBeforeAnythingHappens() = test {
        val (a, _) = pair()
        a.seedWeb()
        val before = a.state()
        val uploads = drive.uploads
        upload("future", Snapshot(v = SNAPSHOT_VERSION + 1, deviceId = "future", tombstones = listOf(SyncTomb("lobes", "L", Long.MAX_VALUE))))
        assertThrows(SyncException::class.java) { runBlocking { a.sync() } }
        assertEquals(before, a.state())
        assertEquals(uploads + 1, drive.uploads) // only the test's own upload
    }

    @Test fun aRetriedCreateKeepsTheOldestOwnFile() = test {
        val (a, _) = pair()
        val name = snapName(a.store.state.deviceId)
        a.sync()
        drive.create(name, seal(key, name, ByteArray(0)))
        a.sync()
        assertEquals(1, drive.list().count { it.name == name })
    }

    @Test fun aResetVaultAsksForThePassphraseAgainAndWritesNothing() = test {
        val (a, _) = pair()
        drive.delete(devicesVault)
        val uploads = drive.uploads
        assertThrows(VaultChanged::class.java) { runBlocking { a.sync() } }
        assertEquals(uploads, drive.uploads)
    }

    @Test fun settingUpAndJoiningTheVaultGiveTheSameKey() = runBlocking(Dispatchers.IO) {
        val first = device("first"); val second = device("second")
        val pass = "correct horse battery"
        val made = openOrCreateVault(first.store, drive, pass)
        assertEquals(1, drive.list().size)
        assertEquals(first.store.state.vaultId, drive.list().single().id)
        assertThrows(SyncException::class.java) { openOrCreateVault(second.store, drive, "wrong passphrase") }
        assertEquals("v", second.store.state.vaultId) // untouched by the failure
        assertEquals(made.toList(), openOrCreateVault(second.store, drive, pass).toList())
        assertEquals(1, drive.list().size)
    }

    @Test fun aShortFirstPassphraseIsRefused() {
        val s = device("short")
        assertThrows(SyncException::class.java) { openOrCreateVault(s.store, drive, "too short") }
        assertTrue(drive.files.isEmpty())
    }

    @Test fun deviceIdSurvivesAReload() {
        val dir = tmp.newFolder("reload")
        val id = SyncStore(dir).state.deviceId
        assertEquals(id, SyncStore(dir).state.deviceId)
    }

    @Test fun manyMixedEditsConverge() = (1L..12L).forEach { seed -> drive = MemoryDrive(); mixedEdits(seed) }

    private fun mixedEdits(seed: Long) = test {
        val (a, b) = pair()
        val rng = java.util.Random(seed)
        val devices = listOf(a, b)
        repeat(40) { i ->
            val d = devices[rng.nextInt(2)]
            val notes = d.notes()
            val lobes = d.db.syncDao().lobes()
            when (rng.nextInt(7)) {
                0, 1 -> d.db.noteDao().insert(Note(title = "n$i", content = "c$i", parentId = notes.filter { it.parentId == null }.randomOrNull(rng.asKotlin())?.id))
                2 -> notes.randomOrNull(rng.asKotlin())?.let { d.db.noteDao().updateText(it.id, it.title, "edit$i", 1) }
                3 -> notes.randomOrNull(rng.asKotlin())?.let { d.db.noteDao().delete(it.id) }
                4 -> d.db.brainDao().insertLobe(Lobe(name = "l$i", colorIndex = 0, slot = i))
                5 -> lobes.randomOrNull(rng.asKotlin())?.let { d.db.brainDao().insertIdea(Idea(lobeId = it.id, title = "i$i", body = "")) }
                6 -> lobes.randomOrNull(rng.asKotlin())?.let { d.db.brainDao().deleteLobe(it.id) }
            }
            tick()
            if (i % 7 == 0) d.sync()
        }
        converge(a, b)
        assertEquals(a.state().notes.map { it.uid to it.content }, b.state().notes.map { it.uid to it.content })
        assertEquals(a.state(), b.state())
    }

    private suspend fun Device.seedLearn(): Long {
        val g = db.learnDao().insertGoal(Goal(name = "Panduri", uid = "G"))
        db.learnDao().insertStep(Step(goalId = g, title = "Suliko", note = "slow", day = 20000, position = 0, uid = "s1"))
        db.learnDao().insertStep(Step(goalId = g, title = "Chords", done = true, position = 1, uid = "s2"))
        return g
    }

    @Test fun goalsAndStepsTravel() = test {
        val (a, b) = pair()
        a.seedLearn()
        converge(a, b)
        val g = b.db.syncDao().goals().single()
        assertEquals("Panduri", g.name)
        val steps = b.db.syncDao().steps().sortedBy { it.position }
        assertEquals(listOf("Suliko", "Chords"), steps.map { it.title })
        assertTrue(steps.all { it.goalId == g.id })
        assertEquals(Triple("slow", 20000L, false), Triple(steps[0].note, steps[0].day, steps[0].done))
        assertTrue(steps[1].done)
        // edits travel back
        tick()
        b.db.learnDao().setStepDone(steps[0].id, true, 9)
        converge(a, b)
        assertTrue(a.db.syncDao().steps().single { it.uid == "s1" }.done)
        assertEquals(a.state(), b.state())
    }

    @Test fun aDeletedGoalAndItsStepsGoEverywhere() = test {
        val (a, b) = pair()
        val g = a.seedLearn()
        converge(a, b)
        tick()
        a.db.learnDao().deleteGoal(g) // cascades its steps, the triggers tombstone all three
        converge(a, b)
        assertTrue(b.db.syncDao().goals().isEmpty() && b.db.syncDao().steps().isEmpty())
        assertEquals(setOf("goals:G", "steps:s1", "steps:s2"), b.db.syncDao().tombstones().map { "${it.tbl}:${it.uid}" }.toSet())
        assertEquals(a.state(), b.state())
    }

    @Test fun aDeletedStepGoesEverywhere() = test {
        val (a, b) = pair()
        a.seedLearn()
        converge(a, b)
        tick()
        a.db.learnDao().deleteStep(a.db.syncDao().steps().single { it.uid == "s2" }.id)
        converge(a, b)
        assertEquals(listOf("s1"), b.db.syncDao().steps().map { it.uid })
        assertEquals(a.state(), b.state())
    }

    /** A device still on version 2: its rows merge (they only lack whole tables), our goals stay, and the user hears to update it. */
    @Test fun aVersion2DevicesFileMergesAndKeepsGoals() = test {
        val (a, _) = pair()
        a.seedLearn()
        upload("old-device", Snapshot(v = 2, deviceId = "old-device", lobes = listOf(SyncLobe("L2", 5, "Work", 1, 0))))
        val e = assertThrows(SyncException::class.java) { runBlocking { a.sync() } }
        assertTrue(e.message!!, e.message!!.contains("older Vesper"))
        assertEquals("Work", a.db.syncDao().lobes().single().name)
        assertEquals(2, a.db.syncDao().steps().size)
        assertEquals("Panduri", a.db.syncDao().goals().single().name)
        assertTrue(a.ownFile().contains("\"v\":$SNAPSHOT_VERSION") && a.ownFile().contains("Suliko")) // pushed anyway
    }

    /** What a version 2 or 3 build does with our file: its validate() stops at the number before merging or pushing anything. */
    @Test fun aVersion4FileCarriesItsNumberForOlderBuilds() = test {
        val (a, _) = pair()
        a.seedLearn()
        a.sync()
        val file = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(Snapshot.serializer(), a.ownFile())
        assertEquals(4, file.v)
        assertTrue(file.v > 3) // a v3 build's validate() throws "newer Vesper" on exactly this, before it merges or uploads
    }

    private fun java.util.Random.asKotlin() = kotlin.random.Random(nextLong())

    // ---- 2.7.8 the Daily brain: ticks clear at midnight on every device, with no history and no conflict copies ----

    private class MemSettings : app.vesper.Settings {
        val m = HashMap<String, String>()
        override fun getString(key: String) = m[key]
        override fun putString(key: String, value: String) { m[key] = value }
        override fun getBoolean(key: String, default: Boolean) = m[key]?.toBoolean() ?: default
        override fun putBoolean(key: String, value: Boolean) { m[key] = value.toString() }
    }
    private val today = java.time.LocalDate.of(2026, 11, 24)
    private suspend fun Device.seedDaily(): Map<String, Long> {
        val lobe = db.brainDao().insertLobe(Lobe(name = "Daily", colorIndex = 3, slot = 3, uid = "daily"))
        return listOf("pills", "workout", "read", "stretch").associateWith { db.brainDao().insertIdea(Idea(lobeId = lobe, title = it, body = "", uid = "d-$it")) }
    }
    private suspend fun Device.daily() = db.syncDao().ideas().filter { it.uid.startsWith("d-") || it.title.contains("(other copy)") }.associate { it.title to it.done }
    private suspend fun Device.tickDaily(title: String, done: Boolean = true) =
        db.brainDao().setIdeaDone(db.syncDao().ideas().single { it.title == title }.id, done)

    /** Fake midnight: a moment between yesterday's ticks and today's on the real clock the triggers stamp with. */
    private fun midnight(): Long { tick(); return System.currentTimeMillis().also { tick() } }

    @Test fun bothDevicesResetAtMidnightAndEndUpTheSameWithNoCopies() = test {
        val (a, b) = pair()
        a.seedDaily()
        converge(a, b)
        a.tickDaily("pills"); b.tickDaily("workout"); b.tickDaily("pills")
        converge(a, b)
        val start = midnight()
        val sa = MemSettings(); val sb = MemSettings()
        assertEquals(2, app.vesper.resetDaily(a.db, sa, today, start))
        assertEquals(2, app.vesper.resetDaily(b.db, sb, today, start)) // nearly the same moment, the same rows
        assertEquals("only ticked rows are written: a second reset writes nothing", 0, app.vesper.resetDaily(a.db, sa, today, start))
        converge(a, b)
        val all = mapOf("pills" to false, "workout" to false, "read" to false, "stretch" to false)
        assertEquals(all, a.daily()); assertEquals(all, b.daily())
        assertEquals("no conflict copies", 4, a.db.syncDao().ideas().size)
        assertEquals(today.toString(), sa.getString(app.vesper.KEY_DAILY_RESET))
        assertEquals(a.state(), b.state())
    }

    @Test fun aTickMadeAfterMidnightSurvivesTheOtherDevicesLateReset() = test {
        val (a, b) = pair()
        a.seedDaily()
        a.tickDaily("pills")
        converge(a, b)
        val start = midnight()
        app.vesper.resetDaily(a.db, MemSettings(), today, start)
        a.tickDaily("workout") // today, after A's reset
        converge(a, b) // B has not reset yet: it gets A's reset and A's new tick
        assertEquals(0, app.vesper.resetDaily(b.db, MemSettings(), today, start)) // B opens late: today's tick is not stale
        converge(a, b)
        val want = mapOf("pills" to false, "workout" to true, "read" to false, "stretch" to false)
        assertEquals(want, a.daily()); assertEquals(want, b.daily())
        assertEquals(4, b.db.syncDao().ideas().size)
    }

    /** The PC opens hours after the phone: its reset of yesterday's tick must not beat the tick the phone made this morning. */
    @Test fun aLateResetNeverUndoesATodayTickOfTheSameItem() = test {
        val (a, b) = pair()
        a.seedDaily()
        a.tickDaily("pills") // yesterday, seen by both
        converge(a, b)
        val start = midnight()
        app.vesper.resetDaily(a.db, MemSettings(), today, start)
        a.tickDaily("pills") // this morning on the phone
        tick()
        assertEquals(1, app.vesper.resetDaily(b.db, MemSettings(), today, start)) // the PC starts later, before it syncs
        converge(a, b)
        assertEquals(true, a.daily()["pills"]); assertEquals(true, b.daily()["pills"])
        assertEquals(4, a.db.syncDao().ideas().size)
    }

    @Test fun aDeviceThatResetsBeforeItSyncsStillGetsTheOthersTodayTick() = test {
        val (a, b) = pair()
        a.seedDaily()
        converge(a, b)
        b.tickDaily("stretch") // yesterday, on B
        a.tickDaily("read")    // yesterday, on A
        val start = midnight()
        a.tickDaily("read", false); a.tickDaily("pills") // today on A, before anything synced: unticked read, ticked pills
        app.vesper.resetDaily(b.db, MemSettings(), today, start) // B resets first, offline
        app.vesper.resetDaily(a.db, MemSettings(), today, start)
        converge(a, b)
        val want = mapOf("pills" to true, "workout" to false, "read" to false, "stretch" to false)
        assertEquals(want, a.daily()); assertEquals(want, b.daily())
        assertEquals("no conflict copies", 4, a.db.syncDao().ideas().size)
    }

    // ---- 2.7.9 the Bible canvas between two devices ----

    private suspend fun Device.cards() = db.syncDao().bibleCards().sortedBy { it.uid }
    private suspend fun Device.links() = db.syncDao().bibleLinks().sortedBy { it.uid }
    private suspend fun Device.cardId(uid: String) = db.syncDao().bibleCards().single { it.uid == uid }.id

    private suspend fun Device.seedBible() {
        val d = db.bibleDao()
        d.insertCard(app.vesper.data.BibleCard(name = "Abraham", x = 0f, y = 0f, uid = "abraham"))
        d.insertCard(app.vesper.data.BibleCard(name = "Isaac", note = "laughter", x = 0f, y = 120f, uid = "isaac"))
        d.insertCard(app.vesper.data.BibleCard(name = "The ark", kind = app.vesper.data.BIBLE_THING, x = 200f, y = 0f, locked = true, uid = "ark"))
        d.insertLink(app.vesper.data.BibleLink(fromUid = "isaac", toUid = "abraham", label = "son", uid = "l1"))
        d.insertLink(app.vesper.data.BibleLink(fromUid = "abraham", toUid = "ark", label = "saw", uid = "l2"))
    }

    @Test fun bibleCardsAndLinksTravel() = test {
        val (a, b) = pair()
        a.seedBible()
        converge(a, b)
        assertEquals(listOf("Abraham", "Isaac", "The ark"), b.cards().map { it.name }.sorted())
        val ark = b.cards().single { it.uid == "ark" }
        assertEquals(listOf(app.vesper.data.BIBLE_THING, 200f, true), listOf(ark.kind, ark.x, ark.locked))
        assertEquals("laughter", b.cards().single { it.uid == "isaac" }.note)
        assertEquals(listOf("son", "saw"), b.links().map { it.label })
        assertEquals(a.state(), b.state())
    }

    @Test fun aMoveTravelsAndMovesOnBothSidesMakeNoCopy() = test {
        val (a, b) = pair()
        a.seedBible()
        converge(a, b)
        a.db.bibleDao().moveCard(a.cardId("isaac"), 30f, 300f)
        converge(a, b)
        assertEquals(30f to 300f, b.cards().single { it.uid == "isaac" }.let { it.x to it.y })
        // both move the same card before syncing: the later one wins everywhere, no copy
        a.db.bibleDao().moveCard(a.cardId("abraham"), -50f, 0f)
        tick()
        b.db.bibleDao().moveCard(b.cardId("abraham"), 70f, -20f)
        converge(a, b)
        assertEquals(3, a.cards().size)
        assertEquals(70f to -20f, a.cards().single { it.uid == "abraham" }.let { it.x to it.y })
        assertEquals(a.state(), b.state())
    }

    @Test fun renamedOnBothSidesKeepsBothNames() = test {
        val (a, b) = pair()
        a.seedBible()
        converge(a, b)
        a.db.bibleDao().renameCard(a.cardId("isaac"), "Yitzhak")
        tick()
        b.db.bibleDao().renameCard(b.cardId("isaac"), "Isaac ben Abraham")
        converge(a, b)
        assertEquals(setOf("Abraham", "The ark", "Isaac ben Abraham", "Yitzhak (other copy)"), a.cards().map { it.name }.toSet())
        assertEquals(a.state(), b.state())
        // the copy has no links; the card keeps its own
        assertEquals(listOf("isaac"), b.links().filter { it.label == "son" }.map { it.fromUid })
    }

    @Test fun typingOnOneSideWhileTheOtherEchoesMakesNoCopy() = test {
        val (a, b) = pair()
        a.seedBible()
        converge(a, b)
        for (n in listOf("Is", "Isa", "Isaa", "Isaac!")) {
            a.db.bibleDao().renameCard(a.cardId("isaac"), n); tick()
            a.sync(); b.sync()
        }
        converge(a, b)
        assertEquals(listOf("Abraham", "Isaac!", "The ark"), b.cards().map { it.name }.sorted())
    }

    @Test fun deletingACardWithLinksDeletesThemEverywhere() = test {
        val (a, b) = pair()
        a.seedBible()
        converge(a, b)
        val id = a.cardId("abraham")
        a.db.useWriterConnection { w -> w.immediateTransaction { a.db.bibleDao().deleteLinksOf("abraham"); a.db.bibleDao().deleteCardRow(id) } }
        converge(a, b)
        assertEquals(listOf("isaac" to "Isaac", "ark" to "The ark").sortedBy { it.first }, b.cards().map { it.uid to it.name })
        assertTrue(b.links().isEmpty())
        assertEquals(a.state(), b.state())
    }

    /** B links Isaac to Abraham while A deletes Abraham: after syncing, no device shows a line to nowhere. */
    @Test fun aLinkToACardDeletedOnTheOtherDeviceIsDropped() = test {
        val (a, b) = pair()
        a.seedBible()
        converge(a, b)
        val id = a.cardId("abraham")
        a.db.useWriterConnection { w -> w.immediateTransaction { a.db.bibleDao().deleteLinksOf("abraham"); a.db.bibleDao().deleteCardRow(id) } }
        tick()
        b.db.bibleDao().insertLink(app.vesper.data.BibleLink(fromUid = "ark", toUid = "abraham", label = "carried", uid = "late"))
        converge(a, b)
        for (d in listOf(a, b)) {
            val uids = d.cards().map { it.uid }.toSet()
            assertTrue(d.links().all { it.fromUid in uids && it.toUid in uids })
            assertTrue(d.links().none { it.uid == "late" })
        }
        assertEquals(a.state(), b.state())
    }

    /** A device still on version 3: its rows merge, our Bible stays, and the user hears to update it. */
    @Test fun aVersion3DevicesFileMergesAndKeepsTheBible() = test {
        val (a, _) = pair()
        a.seedBible()
        upload("old-device", Snapshot(v = 3, deviceId = "old-device", lobes = listOf(SyncLobe("L2", 5, "Work", 1, 0))))
        val e = assertThrows(SyncException::class.java) { runBlocking { a.sync() } }
        assertTrue(e.message!!, e.message!!.contains("older Vesper"))
        assertEquals("Work", a.db.syncDao().lobes().single().name)
        assertEquals(3, a.cards().size); assertEquals(2, a.links().size)
        assertTrue(a.ownFile().contains("\"v\":4") && a.ownFile().contains("The ark")) // pushed anyway
    }
}
