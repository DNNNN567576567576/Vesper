package app.vesper.sync

import androidx.compose.runtime.Composable
import app.vesper.SyncPlatform
import app.vesper.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

/** Process-wide sync state behind the vault sheet. Runs only on user request; automatic triggers come in M5. */
class SyncManager(private val db: AppDatabase, private val scope: CoroutineScope, private val platform: SyncPlatform, private val report: (String, Throwable) -> Unit) {
    private val _ui = MutableStateFlow(SyncUi(available = platform.available))
    val ui: StateFlow<SyncUi> = _ui
    private val mutex = Mutex()
    private val store by lazy { SyncStore(platform.stateDir) }

    init {
        if (platform.available) scope.launch {
            runCatching {
                val signedIn = platform.isSignedIn()
                val unlocked = platform.loadKey() != null
                // Not signed in: sync stays fully idle, not even a device id is written.
                _ui.update { it.copy(signedIn = signedIn, unlocked = unlocked, lastSyncAt = if (signedIn) store.state.lastSyncAt else 0) }
            }.onFailure { report("read the sync state", it) }
        }
    }

    private fun work(label: String, block: suspend () -> Unit) {
        if (_ui.value.working != null) return
        _ui.update { it.copy(working = label, error = null) }
        scope.launch {
            mutex.withLock {
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
                    report("sync", e)
                    error = "Sync failed. Your data here is unchanged."
                }
                _ui.update { it.copy(working = null, error = error) }
            }
        }
    }

    fun onSignIn(failure: Throwable?) {
        if (failure == null) _ui.update { it.copy(signedIn = platform.isSignedIn(), error = null) }
        else _ui.update { it.copy(error = (failure as? SyncException)?.message ?: "Sign-in failed. Try again.") }
    }

    @Composable
    fun rememberSignIn(): () -> Unit = platform.rememberSignIn(::onSignIn)

    /** Only lists the Drive folder; it decides which passphrase dialog to show. */
    fun checkVault() = work("checking drive...") {
        val exists = DriveTransport(platform.accessToken()).list().vault() != null
        _ui.update { it.copy(vaultExists = exists) }
    }

    fun unlock(passphrase: String) = work("opening the vault...") {
        val key = openOrCreateVault(store, DriveTransport(platform.accessToken()), passphrase)
        platform.storeKey(key)
        _ui.update { it.copy(unlocked = true, vaultExists = true) }
    }

    fun syncNow() = work("syncing...") {
        val key = platform.loadKey() ?: throw VaultChanged()
        syncRound(db, store, DriveTransport(platform.accessToken()), key)
        _ui.update { it.copy(lastSyncAt = store.state.lastSyncAt) }
    }
}
