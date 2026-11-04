package app.vesper

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import app.vesper.data.AppDatabase
import app.vesper.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** What the shared ViewModels and screens need from the platform app; :app implements it in NotebookApp. */
interface VesperHost {
    val db: AppDatabase

    /**
     * Writes that must survive leaving a screen (saves, deletes) run here rather than in a
     * ViewModel scope, so closing the editor can never cancel an in-flight write.
     */
    val appScope: CoroutineScope

    /** Logs [e] and tells the user "Could not [what]". */
    fun reportError(what: String, e: Throwable)

    val settings: Settings
    val shrineMedia: ShrineMedia
    val vault: Vault
    val sync: SyncManager
}

/** What Drive sync needs from the platform. Blocking calls run off the main thread. */
interface SyncPlatform {
    /** False when this build has no Google client (desktop built without oauth-client.json). */
    val available: Boolean

    /** Device id and Drive cursors. Outside backups, so a restored phone can never clone a device id. */
    val stateDir: File
    fun isSignedIn(): Boolean

    /** A fresh token for drive.appdata. Throws [app.vesper.sync.SignInNeeded] when the user must sign in again. */
    fun accessToken(): String

    /** The data key, unwrapped; null = not unlocked on this device. */
    fun loadKey(): ByteArray?

    /** Stores the data key at rest; null forgets it. */
    fun storeKey(key: ByteArray?)

    /** Returns the action that starts the sign-in; [onDone] gets null on success. */
    @Composable
    fun rememberSignIn(onDone: (Throwable?) -> Unit): () -> Unit
}

/** Small persistent key/value store for UI preferences (Android: SharedPreferences "vesper"). */
interface Settings {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

/**
 * Grimoire photos and videos. A `ref` is a picked, not yet kept item (Android: a content:// uri string);
 * a `name` is a kept file in the Grimoire's own storage.
 */
interface ShrineMedia {
    val canAttach: Boolean get() = true

    fun isVideo(ref: String): Boolean

    /** Blocking: call off the main thread. Copies/scales [ref] into storage and returns the stored name. */
    fun import(ref: String, video: Boolean): String

    /** Removes a stored file; missing files are fine. */
    fun delete(name: String)

    fun cached(name: String, widthPx: Int): ImageBitmap?

    /** Decodes stored [name] roughly [widthPx] wide (a video gives its first frame); null if it can't be read. */
    suspend fun load(name: String, widthPx: Int, cache: Boolean, video: Boolean): ImageBitmap?

    /** Small thumbnail of a picked [ref] for the attachment chip. */
    suspend fun thumb(ref: String, edge: Int): ImageBitmap?

    /** Returns the action that opens the photo/video picker; [onPick] gets the picked ref. */
    @Composable
    fun rememberPicker(onPick: (String) -> Unit): () -> Unit

    /** Full-screen player for stored video [name]. */
    @Composable
    fun VideoPlayer(name: String, onClose: () -> Unit)
}

/** Backup ("vault") of all data to a user-chosen file. Failures throw [app.vesper.data.BackupException]. */
interface Vault {
    /** Epoch millis of the last successful backup; null = never. */
    val lastBackupAt: StateFlow<Long?>

    /** Returns the action that asks where to save a backup named like its argument; [onPick] gets the target. */
    @Composable
    fun rememberSavePicker(onPick: (String) -> Unit): (String) -> Unit

    /** Returns the action that asks for a backup file to restore; [onPick] gets the source. */
    @Composable
    fun rememberOpenPicker(onPick: (String) -> Unit): () -> Unit

    suspend fun write(target: String)
    suspend fun restore(source: String)
    fun restart()
}
