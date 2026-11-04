package app.vesper.sync

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.vesper.SyncPlatform
import app.vesper.data.AppDatabase
import app.vesper.data.Note
import app.vesper.data.SYNC_TRIGGERS_CALLBACK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** The platform with the Google parts out: no UI can come from here, as on an automatic run. */
internal class FakePlatform(override val stateDir: File) : SyncPlatform {
    override val available = true
    var signedIn = true
    var key: ByteArray? = null
    var signInGone = false
    override fun isSignedIn() = signedIn
    override fun accessToken(): String = if (signInGone) throw SignInNeeded() else "token"
    override fun signOut() { signedIn = false }
    override fun loadKey() = key
    override fun storeKey(key: ByteArray?) { this.key = key }

    @Composable
    override fun rememberSignIn(onDone: (Throwable?) -> Unit): () -> Unit = {}
}

/** Fails every list while [offline]; everything else as [MemoryDrive]. */
internal class FlakyDrive(private val drive: SyncTransport) : SyncTransport by drive {
    var offline = false
    var lists = 0
    override fun list(): List<RemoteFile> {
        lists++
        if (offline) throw IOException("no route")
        return drive.list()
    }
}

internal fun waitUntil(what: String, tick: () -> Unit = {}, done: () -> Boolean) {
    repeat(2000) {
        tick()
        if (done()) return
        Thread.sleep(5)
    }
    error("timed out waiting: $what")
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncAutoTest {
    @get:Rule val tmp = TemporaryFolder()

    private val drive = MemoryDrive()
    private val flaky = FlakyDrive(drive)
    private val pass = "correct horse battery"

    private inner class Device {
        val db: AppDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .addCallback(SYNC_TRIGGERS_CALLBACK).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val platform = FakePlatform(tmp.newFolder())
        val reports = mutableListOf<String>()
        val sync = SyncManager(db, scope, platform, { what, _ -> reports += what }, { flaky })
        val store get() = SyncStore(platform.stateDir)
        val ui get() = sync.ui.value
        fun notes() = runBlocking(Dispatchers.IO) { db.syncDao().notes() }
        fun write(title: String) = runBlocking(Dispatchers.IO) { db.noteDao().insert(Note(title = title, content = "")) }

        fun unlocked(): Device {
            waitUntil("signed in") { ui.signedIn }
            sync.unlock(pass)
            waitUntil("unlocked") { ui.unlocked }
            return this
        }

        fun syncNow() {
            val before = store.state.lastSyncAt
            sync.syncNow()
            waitUntil("sync") { ui.working == null && (store.state.lastSyncAt != before || ui.error != null) }
        }
    }

    private val devices = mutableListOf<Device>()
    private fun device() = Device().also { devices += it }

    @After fun close() = devices.forEach { it.scope.cancel(); it.db.close() }

    // The schedule, on virtual time.

    @Test fun aRoundOnOpenThenFiveSecondsAfterTheLastChange() = runTest {
        val changes = MutableSharedFlow<Long>()
        var rounds = 0
        val job = launch { autoSyncLoop(changes) { rounds++; true } }
        runCurrent()
        assertEquals(1, rounds)
        changes.emit(1); advanceTimeBy(3_000)
        changes.emit(2); advanceTimeBy(4_000); runCurrent()
        assertEquals(1, rounds) // still typing: the first change moved the timer on
        advanceTimeBy(1_001); runCurrent()
        assertEquals(2, rounds)
        job.cancel()
    }

    @Test fun aRoundEveryFiveMinutesWithNoChanges() = runTest {
        var rounds = 0
        val job = launch { autoSyncLoop(MutableSharedFlow<Long>()) { rounds++; true } }
        runCurrent()
        advanceTimeBy(299_000); runCurrent()
        assertEquals(1, rounds)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(2, rounds)
        advanceTimeBy(300_000); runCurrent()
        assertEquals(3, rounds)
        job.cancel()
    }

    @Test fun failuresBackOffDoublingToFifteenMinutes() = runTest {
        val at = mutableListOf<Long>()
        val job = launch { autoSyncLoop(MutableSharedFlow<Long>()) { at += currentTime; false } }
        advanceTimeBy(3_000_000); runCurrent()
        assertEquals(listOf(0L, 60, 180, 420, 900, 1800, 2700), at.map { it / 1000 })
        job.cancel()
    }

    @Test fun changesDuringABackoffCollapseIntoOneRetryAndSuccessResetsIt() = runTest {
        val changes = MutableSharedFlow<Long>()
        val at = mutableListOf<Long>()
        var fail = true
        val job = launch { autoSyncLoop(changes) { at += currentTime; !fail } }
        runCurrent()
        repeat(5) { changes.emit(it.toLong()); advanceTimeBy(6_000) }
        runCurrent()
        assertEquals(1, at.size) // five settled changes, no round inside the wait
        fail = false
        advanceTimeBy(30_001); runCurrent()
        assertEquals(listOf(0L, 60_000L), at)
        fail = true
        changes.emit(9); advanceTimeBy(5_001); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf(0L, 60_000L, 65_001L, 125_001L), at) // back to one minute
        job.cancel()
    }

    // The manager, on real rooms and a stand-in Drive.

    @Test fun everySyncedTableMovesTheChangeSignal() = runBlocking(Dispatchers.IO) {
        val d = device()
        val dao = d.db.syncDao()
        val a = dao.observeChanges().first()
        val id = d.db.noteDao().insert(Note(title = "t", content = ""))
        val b = dao.observeChanges().first()
        Thread.sleep(3)
        d.db.noteDao().updateText(id, "t", "x", 1)
        val c = dao.observeChanges().first()
        Thread.sleep(3)
        d.db.noteDao().delete(id)
        val e = dao.observeChanges().first()
        assertTrue(a < b && b < c && c != e)
    }

    @Test fun openSyncsAndLeavingPushesTheLastEdit() {
        val a = device().unlocked()
        a.sync.setForeground(true)
        waitUntil("round on open") { a.ui.lastSyncAt > 0 }
        val snap = drive.list().single { it.name.startsWith("snap-") }.version
        a.write("late")
        a.sync.setForeground(false)
        waitUntil("push on leaving") { drive.list().single { it.name.startsWith("snap-") }.version != snap }
        waitUntil("idle") { a.ui.working == null }
        val before = flaky.lists
        Thread.sleep(50)
        assertEquals(before, flaky.lists) // nothing keeps running in the background
    }

    @Test fun aLocalChangeSyncsWithoutBeingAsked() {
        val a = device().unlocked()
        a.sync.setForeground(true)
        waitUntil("round on open") { a.ui.lastSyncAt > 0 && a.ui.working == null }
        val snap = drive.list().single { it.name.startsWith("snap-") }.version
        a.write("typed")
        waitUntil("debounced push") { drive.list().single { it.name.startsWith("snap-") }.version != snap }
    }

    @Test fun nothingRunsWhileLockedOrSignedOut() {
        val a = device()
        waitUntil("signed in") { a.ui.signedIn }
        a.sync.setForeground(true)
        Thread.sleep(50)
        assertEquals(0, flaky.lists)
        assertEquals(0L, a.ui.lastSyncAt)
    }

    @Test fun offlineIsQuietKeepsDataAndShowsTheLastError() {
        val a = device().unlocked()
        a.write("kept")
        flaky.offline = true
        a.sync.setForeground(true)
        waitUntil("the failure") { a.ui.error != null && a.ui.working == null }
        assertTrue(a.ui.error!!.contains("No connection"))
        assertTrue(a.reports.isEmpty()) // reports are what toast
        assertTrue(a.ui.unlocked && a.ui.signedIn)
        assertEquals(listOf("kept"), a.notes().map { it.title })
    }

    @Test fun aLostSignInStopsTheLoopAndAsksInTheSheetOnly() {
        val a = device().unlocked()
        a.platform.signInGone = true
        a.sync.setForeground(true)
        waitUntil("sign-in state") { !a.ui.signedIn && a.ui.working == null }
        assertEquals("Sign in to Google again.", a.ui.error)
        assertTrue(a.reports.isEmpty())
        assertEquals(0, drive.uploads - 1) // only the vault was ever written
    }

    @Test fun resetEmptiesDriveKeepsNotesAndTheOtherDeviceAsksForTheNewPassphrase() {
        val a = device().unlocked()
        val b = device().unlocked()
        a.write("from a"); b.write("from b")
        a.syncNow(); b.syncNow(); a.syncNow()
        val deviceId = a.store.state.deviceId

        a.sync.reset()
        waitUntil("reset") { a.ui.working == null && !a.ui.unlocked }
        assertTrue(drive.list().isEmpty())
        assertNull(a.platform.key)
        assertEquals(false, a.ui.vaultExists)
        assertEquals(0L, a.ui.lastSyncAt)
        assertEquals(setOf("from a", "from b"), a.notes().map { it.title }.toSet())
        assertEquals(deviceId, a.store.state.deviceId)
        assertTrue(a.store.state.peerVersions.isEmpty())

        a.sync.unlock("a whole new passphrase")
        waitUntil("new vault") { a.ui.unlocked }
        a.syncNow()

        b.syncNow()
        assertEquals("The passphrase changed on another device. Enter it again here.", b.ui.error)
        assertFalse(b.ui.unlocked)
        assertNull(b.platform.key)
        // Locked now: no error loop, the automatic side doesn't even start.
        val lists = flaky.lists
        b.sync.setForeground(true)
        Thread.sleep(50)
        assertEquals(lists, flaky.lists)

        b.sync.unlock(pass)
        waitUntil("old passphrase refused") { b.ui.working == null && b.ui.error?.contains("Wrong") == true }
        b.sync.unlock("a whole new passphrase")
        waitUntil("rejoined") { b.ui.unlocked }
        b.write("after the reset")
        waitUntil("b pushed") { drive.list().count { it.name.startsWith("snap-") } == 2 }
        a.syncNow()
        assertEquals(setOf("from a", "from b", "after the reset"), a.notes().map { it.title }.toSet())
    }

    @Test fun signOutForgetsTheKeyAndStopsSync() {
        val a = device().unlocked()
        a.sync.setForeground(true)
        waitUntil("round") { a.ui.lastSyncAt > 0 && a.ui.working == null }
        a.sync.signOut()
        waitUntil("signed out") { !a.ui.signedIn }
        assertNull(a.platform.key)
        assertFalse(a.ui.unlocked)
        assertNotNull(a.store.state.vaultId)
    }

    @Test fun duringRestoreClearsTheCursorsOnlyAtTheSwapAndStopsSync() {
        val a = device().unlocked()
        val b = device().unlocked()
        b.write("x")
        b.syncNow(); a.syncNow()
        assertTrue(a.store.state.peerVersions.isNotEmpty())
        val id = a.store.state.deviceId

        // A bad backup never reaches the swap: nothing moves.
        runCatching { runBlocking(Dispatchers.IO) { a.sync.duringRestore<Unit> { throw IllegalStateException("bad zip") } } }
        assertTrue(a.store.state.peerVersions.isNotEmpty())

        runBlocking(Dispatchers.IO) { a.sync.duringRestore { beforeSwap -> beforeSwap() } }
        val s = a.store.state
        assertTrue(s.peerVersions.isEmpty() && s.seen.isEmpty())
        assertNull(s.pushedHash)
        assertEquals(0L, s.lastSyncAt)
        assertEquals(0L, s.pushedUpTo)
        assertEquals(id, s.deviceId)
        assertNotNull(s.vaultId)

        val lists = flaky.lists
        a.sync.setForeground(true)
        a.sync.syncNow()
        Thread.sleep(50)
        assertEquals(lists, flaky.lists) // the DB is closed by now: nothing may touch it until the restart
    }
}
