package app.vesper.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.vesper.data.AppDatabase
import app.vesper.data.Branch
import app.vesper.data.Idea
import app.vesper.data.Lobe
import app.vesper.data.Note
import app.vesper.data.Plan
import app.vesper.data.SYNC_TRIGGERS_CALLBACK
import app.vesper.data.Task
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

    private fun java.util.Random.asKotlin() = kotlin.random.Random(nextLong())
}
