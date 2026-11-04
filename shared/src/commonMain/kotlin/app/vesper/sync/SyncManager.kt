package app.vesper.sync

import androidx.compose.runtime.Composable
import app.vesper.SyncPlatform
import app.vesper.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/** [vaultExists]: null until asked; tells the passphrase dialog whether to set a new one or open the existing one. */
data class SyncUi(
    val available: Boolean = false,
    val signedIn: Boolean = false,
    val unlocked: Boolean = false,
    val vaultExists: Boolean? = null,
    val working: String? = null,
    val lastSyncAt: Long = 0,
    val error: String? = null,
)

const val SYNC_DEBOUNCE_MS = 5_000L
const val SYNC_PERIOD_MS = 300_000L
const val SYNC_BACKOFF_MS = 60_000L
const val SYNC_BACKOFF_MAX_MS = 900_000L

/**
 * Runs until cancelled: a round now, a round [SYNC_DEBOUNCE_MS] after the last of [changes], and one every
 * [SYNC_PERIOD_MS]. After a failed [round] it waits, 1 minute doubling to 15, then retries; anything that happens
 * meanwhile collapses into that one retry.
 */
@OptIn(FlowPreview::class)
suspend fun autoSyncLoop(changes: Flow<*>, round: suspend () -> Boolean) = coroutineScope {
    val nudge = Channel<Unit>(Channel.CONFLATED)
    launch { changes.debounce(SYNC_DEBOUNCE_MS).collect { nudge.trySend(Unit) } }
    nudge.trySend(Unit)
    var failures = 0
    while (true) {
        withTimeoutOrNull(SYNC_PERIOD_MS) { nudge.receive() }
        if (round()) {
            failures = 0
        } else {
            delay(minOf(SYNC_BACKOFF_MS shl minOf(failures++, 4), SYNC_BACKOFF_MAX_MS))
            nudge.trySend(Unit)
        }
    }
}

/** Process-wide sync state behind the vault sheet. One thing at a time, by [mutex]; automatic rounds run only in the foreground. */
class SyncManager(
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    private val platform: SyncPlatform,
    private val report: (String, Throwable) -> Unit,
    private val transport: (String) -> SyncTransport = ::DriveTransport,
) {
    private val _ui = MutableStateFlow(SyncUi(available = platform.available))
    val ui: StateFlow<SyncUi> = _ui
    private val mutex = Mutex()
    private val store by lazy { SyncStore(platform.stateDir) }
    private val foreground = MutableStateFlow(false)
    private val flushers = CopyOnWriteArrayList<suspend () -> Unit>()
    private var loop: Job? = null
    @Volatile private var stopped = false

    init {
        if (platform.available) {
            scope.launch {
                runCatching {
                    val signedIn = platform.isSignedIn()
                    val unlocked = platform.loadKey() != null
                    // Not signed in: sync stays fully idle, not even a device id is written.
                    _ui.update { it.copy(signedIn = signedIn, unlocked = unlocked, lastSyncAt = if (signedIn) store.state.lastSyncAt else 0) }
                }.onFailure { report("read the sync state", it) }
            }
            loop = scope.launch {
                combine(foreground, _ui) { open, ui -> open && ui.signedIn && ui.unlocked }.distinctUntilChanged().collectLatest { on ->
                    if (on) autoSyncLoop(db.syncDao().observeChanges().distinctUntilChanged().drop(1)) { run("syncing...", auto = true) { round() }.await() }
                }
            }
        }
    }

    /** Takes [block] through the one-at-a-time lane; false if it failed. [auto] stays silent: no toast, and the last error stays up until the outcome is known. */
    private fun run(label: String, auto: Boolean = false, block: suspend () -> Unit): Deferred<Boolean> = scope.async {
        mutex.withLock {
            if (stopped) {
                _ui.update { it.copy(working = null) }
                return@withLock true
            }
            _ui.update { it.copy(working = label, error = if (auto) it.error else null) }
            var error: String? = null
            try {
                plainNetworkErrors { block() }
            } catch (e: SignInNeeded) {
                error = e.message
                _ui.update { it.copy(signedIn = false) }
            } catch (e: VaultChanged) {
                error = e.message
                platform.storeKey(null)
                _ui.update { it.copy(unlocked = false, vaultExists = null) }
            } catch (e: SyncException) {
                error = e.message
            } catch (e: Exception) {
                if (auto) System.err.println("Vesper: sync failed, ${e::class.simpleName}") else report("sync", e)
                error = "Sync failed. Your data here is unchanged."
            }
            _ui.update { it.copy(working = null, error = error) }
            error == null
        }
    }

    private fun work(label: String, block: suspend () -> Unit) {
        if (_ui.value.working != null) return
        _ui.update { it.copy(working = label, error = null) }
        run(label, block = block)
    }

    private suspend fun round() {
        flushers.forEach { it() }
        val key = platform.loadKey() ?: throw VaultChanged()
        syncRound(db, store, transport(platform.accessToken()), key)
        _ui.update { it.copy(lastSyncAt = store.state.lastSyncAt) }
    }

    /** [flush] runs before every round. Returns what takes it off the list again. */
    fun beforeRound(flush: suspend () -> Unit): () -> Unit {
        flushers += flush
        return { flushers -= flush }
    }

    /** The app came on screen, or left it. Leaving pushes once more, best effort. */
    fun setForeground(on: Boolean) {
        if (foreground.value == on) return
        foreground.value = on
        if (!on && _ui.value.signedIn && _ui.value.unlocked) run("syncing...", auto = true) { round() }
    }

    /** The app is closing: last push on [scope]; the caller decides how long to wait for it. */
    fun shutdown() {
        setForeground(false)
        loop?.cancel()
    }

    fun onSignIn(failure: Throwable?) {
        if (failure == null) _ui.update { it.copy(signedIn = platform.isSignedIn(), error = null) }
        else _ui.update { it.copy(error = (failure as? SyncException)?.message ?: "Sign-in failed. Try again.") }
    }

    @Composable
    fun rememberSignIn(): () -> Unit = platform.rememberSignIn(::onSignIn)

    /** Only lists the Drive folder; it decides which passphrase dialog to show. */
    fun checkVault() = work("checking drive...") {
        val exists = transport(platform.accessToken()).list().vault() != null
        _ui.update { it.copy(vaultExists = exists) }
    }

    fun unlock(passphrase: String) = work("opening the vault...") {
        val key = openOrCreateVault(store, transport(platform.accessToken()), passphrase)
        platform.storeKey(key)
        _ui.update { it.copy(unlocked = true, vaultExists = true) }
    }

    fun syncNow() = work("syncing...") { round() }

    /** Empties the Drive folder and locks this device until a new passphrase is set. Local notes are not touched. */
    fun reset() = work("resetting sync...") {
        resetVault(store, transport(platform.accessToken()))
        platform.storeKey(null)
        _ui.update { it.copy(unlocked = false, vaultExists = false, lastSyncAt = 0) }
    }

    fun signOut() = work("signing out...") {
        platform.storeKey(null)
        platform.signOut()
        _ui.update { it.copy(signedIn = false, unlocked = false, vaultExists = null, lastSyncAt = 0) }
    }

    /**
     * Keeps sync away while a backup replaces the DB. [block] calls its argument right before the swap: the cursors go, and
     * sync stays stopped until the restart.
     */
    suspend fun <T> duringRestore(block: suspend (beforeSwap: () -> Unit) -> T): T = mutex.withLock {
        block {
            store.state = store.state.afterRestore()
            stopped = true
            loop?.cancel()
        }
    }
}
