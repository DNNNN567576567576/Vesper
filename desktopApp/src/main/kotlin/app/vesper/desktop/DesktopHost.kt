package app.vesper.desktop

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.vesper.DesktopShrineMedia
import app.vesper.DesktopVault
import app.vesper.PropertiesSettings
import app.vesper.Settings
import app.vesper.ShrineMedia
import app.vesper.Vault
import app.vesper.VesperHost
import app.vesper.data.AppDatabase
import app.vesper.data.SYNC_TRIGGERS_CALLBACK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Desktop platform: a fresh DB at the current version (no migrations), settings and DB under [dir]. */
class DesktopHost(dir: File) : VesperHost {
    override val db: AppDatabase = Room.databaseBuilder<AppDatabase>(File(dir, "vesper.db").absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addCallback(SYNC_TRIGGERS_CALLBACK)
        .build()

    override val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override val settings: Settings = PropertiesSettings(File(dir, "settings.properties"))
    override val shrineMedia: ShrineMedia = DesktopShrineMedia
    override val vault: Vault = DesktopVault

    override fun reportError(what: String, e: Throwable) {
        System.err.println("Vesper: could not $what")
        e.printStackTrace()
    }
}
