package app.vesper.desktop

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.vesper.DesktopShrineMedia
import app.vesper.DesktopSyncPlatform
import app.vesper.DesktopVault
import app.vesper.PropertiesSettings
import app.vesper.Settings
import app.vesper.ShrineMedia
import app.vesper.Vault
import app.vesper.VesperHost
import app.vesper.data.AppDatabase
import app.vesper.data.DESKTOP_MIGRATIONS
import app.vesper.data.SYNC_TRIGGERS_CALLBACK
import app.vesper.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Desktop platform: settings and DB under [dir]. */
class DesktopHost(dir: File) : VesperHost {
    override val db: AppDatabase = Room.databaseBuilder<AppDatabase>(File(dir, "vesper.db").absolutePath)
        .setDriver(BundledSQLiteDriver())
        .addMigrations(*DESKTOP_MIGRATIONS)
        .setQueryCoroutineContext(Dispatchers.IO)
        .addCallback(SYNC_TRIGGERS_CALLBACK)
        .build()

    override val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override val settings: Settings = PropertiesSettings(File(dir, "settings.properties"))
    override val shrineMedia: ShrineMedia = DesktopShrineMedia
    override val vault: Vault = DesktopVault
    override val sync by lazy { SyncManager(db, appScope, DesktopSyncPlatform(File(dir, "sync")), ::reportError) }

    /** Asks where to save the picture (the Pictures folder to start with). */
    override fun shareNote(png: ByteArray, name: String) {
        appScope.launch {
            try {
                val dlg = java.awt.FileDialog(null as java.awt.Frame?, "Save the note picture", java.awt.FileDialog.SAVE)
                dlg.directory = File(System.getProperty("user.home"), "Pictures").takeIf { it.isDirectory }?.path
                dlg.file = name.replace(Regex("[\\/:*?\"<>|]+"), "-").take(40).ifBlank { "note" } + ".png"
                dlg.isVisible = true
                val f = dlg.file ?: return@launch
                File(dlg.directory, f).writeBytes(png)
            } catch (e: Exception) { reportError("save the note picture", e) }
        }
    }

    override fun reportError(what: String, e: Throwable) {
        System.err.println("Vesper: could not $what")
        e.printStackTrace()
    }
}
