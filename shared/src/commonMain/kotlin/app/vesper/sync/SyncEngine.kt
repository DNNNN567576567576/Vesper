package app.vesper.sync

import app.vesper.data.AppDatabase
import app.vesper.data.newUid
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Sync bookkeeping, outside the DB and outside backups. [peerVersions] (file id -> Drive version) says which peer
 * snapshots are merged; [seen] is what tells a real conflict from a one-sided edit, and [pushedUpTo] what tells an edit
 * the peer never saw from one it deleted, see [merge].
 */
@Serializable
data class SyncState(
    val deviceId: String = newUid(),
    val vaultId: String? = null,
    val lastSyncAt: Long = 0,
    val pushedHash: String? = null,
    val pushedUpTo: Long = 0,
    val peerVersions: Map<String, String> = emptyMap(),
    val seen: Map<String, Long> = emptyMap(),
)

private val json = Json { ignoreUnknownKeys = true }

class SyncStore(dir: File) {
    private val file = File(dir, "state.json")

    var state: SyncState = if (file.exists()) json.decodeFromString(SyncState.serializer(), file.readText()) else SyncState().also { dir.mkdirs(); write(it) }
        set(value) { write(value); field = value }

    // Replace in one step, so a crash can't leave half a file and lose the device id.
    private fun write(s: SyncState) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(SyncState.serializer(), s))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

fun snapName(deviceId: String) = "snap-$deviceId.vsp"
private val SNAP = Regex("snap-(.+)\\.vsp")

/** Two setups at once can leave two vaults; the oldest one is the vault, on every device. */
fun List<RemoteFile>.vault() = filter { it.name == VAULT_FILE }.minByOrNull { it.createdTime }

/** The vault this device unlocked is gone: someone reset sync on another device. */
class VaultChanged : SyncException("The passphrase changed on another device. Enter it again here.")

/**
 * One sync round. Each device writes only its own snapshot, so Drive never sees a write conflict. Peers first, then
 * push, so the snapshot carries what was just merged. Pure re-merges change nothing, the hash stays and nothing uploads.
 * shortcut: full snapshot every push; past ~2 MB gzipped add delta-<deviceId>.vsp and rewrite the snapshot daily.
 */
suspend fun syncRound(db: AppDatabase, store: SyncStore, t: SyncTransport, key: ByteArray, now: () -> Long = System::currentTimeMillis) {
    val deviceId = store.state.deviceId
    val files = t.list()
    val vault = files.vault()?.id
    if (vault == null || vault != store.state.vaultId) throw VaultChanged()

    var olderPeer = false
    for (f in files) {
        val peer = SNAP.matchEntire(f.name)?.groupValues?.get(1) ?: continue
        if (peer == deviceId || store.state.peerVersions[f.id] == f.version) continue
        val remote = json.decodeFromString(Snapshot.serializer(), String(open(key, f.name, t.download(f.id))))
        // Too old to merge: its rows lack fields we hold and would wipe them. Skipping loses nothing, that device keeps its rows
        // and sends them once updated. We still push below: two freshly updated devices each see the other's old file, and
        // if both stopped here neither would ever write a new one.
        if (remote.v < SNAPSHOT_VERSION) { olderPeer = true; continue }
        remote.validate()
        if (remote.deviceId != peer) throw SyncException("The copy on Drive is damaged. Nothing was changed.")
        val seen = store.state.seen
        db.mergeIn(remote, deviceId, seen, store.state.pushedUpTo)
        // After the commit: a crash in between only merges the same file again, which is a no-op.
        store.state = store.state.copy(peerVersions = store.state.peerVersions + (f.id to f.version), seen = seenAfter(seen, remote))
    }

    val snapshot = db.exportSnapshot(deviceId)
    val plain = json.encodeToString(Snapshot.serializer(), snapshot).toByteArray()
    val hash = MessageDigest.getInstance("SHA-256").digest(plain).joinToString("") { "%02x".format(it) }
    val name = snapName(deviceId)
    val own = files.filter { it.name == name }.sortedBy { it.createdTime }
    own.drop(1).forEach { t.delete(it.id) } // a retried create; the oldest stays
    when {
        own.isEmpty() -> t.create(name, seal(key, name, plain))
        hash != store.state.pushedHash -> t.update(own[0].id, seal(key, name, plain))
    }
    store.state = store.state.copy(pushedHash = hash, pushedUpTo = snapshot.rows.maxOfOrNull { it.syncAt } ?: 0, lastSyncAt = now())
    if (olderPeer) throw SyncException("Your other device runs an older Vesper. Update it to sync again. Everything here is safe.")
}

/**
 * Sync state for a DB that was just replaced by a backup: nothing here is known to be merged or pushed any more, so the
 * next round merges every peer in full, and with [SyncState.seen] empty any text that differs keeps both versions.
 */
fun SyncState.afterRestore() = copy(lastSyncAt = 0, pushedHash = null, pushedUpTo = 0, peerVersions = emptyMap(), seen = emptyMap())

/** Forgot the passphrase, or starting over: empties the Drive folder. Local rows and [SyncState.seen] stay, they still hold. */
fun resetVault(store: SyncStore, t: SyncTransport) {
    // The vault goes first: every other device stops at its check, and if this fails halfway a new vault clears the rest.
    t.list().sortedBy { it.name != VAULT_FILE }.forEach { t.delete(it.id) }
    store.state = store.state.copy(vaultId = null, lastSyncAt = 0, pushedHash = null, peerVersions = emptyMap())
}

/** First device: makes the vault. Later devices: opens it. Returns the data key; nothing is stored here on a wrong passphrase. */
fun openOrCreateVault(store: SyncStore, t: SyncTransport, passphrase: String): ByteArray {
    val files = t.list()
    val existing = files.vault()
    val key: ByteArray
    val id: String
    if (existing != null) {
        key = unwrapKey(t.download(existing.id), passphrase)
        id = existing.id
    } else {
        if (passphrase.length < MIN_PASSPHRASE) throw SyncException("Use at least $MIN_PASSPHRASE characters. Four or more words work well.")
        files.forEach { t.delete(it.id) } // snapshots with no vault are unreadable leftovers of a reset that stopped halfway
        key = newDataKey()
        id = t.create(VAULT_FILE, wrapKey(key, passphrase)).id
    }
    // Another vault means another key: every cursor is void, and our own file on Drive must be rewritten even if unchanged.
    if (id != store.state.vaultId) store.state = store.state.copy(vaultId = id, pushedHash = null, peerVersions = emptyMap())
    return key
}
