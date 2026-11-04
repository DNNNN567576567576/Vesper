package app.vesper

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.room.Room
import app.vesper.data.AppDatabase
import app.vesper.data.ALL_MIGRATIONS
import app.vesper.data.SYNC_TRIGGERS_CALLBACK
import app.vesper.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class NotebookApp : Application(), VesperHost {
    override fun onCreate() {
        super.onCreate()
        // One-time safety net before the v3 -> v4 migration: runs before the lazy [db] is ever opened.
        backupRawDatabaseOnce()
        // Once the DB is open (and migrated), also dump the user's data as readable JSON.
        appScope.launch { exportJsonOnce() }
    }

    override val db: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, DB_NAME)
            .addMigrations(*ALL_MIGRATIONS)
            .addCallback(SYNC_TRIGGERS_CALLBACK)
            .build()
    }

    /** Epoch millis of the last successful [app.vesper.data.writeBackup]; null = never. */
    val lastBackupAt: MutableStateFlow<Long?> by lazy {
        MutableStateFlow(getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_LAST_BACKUP, 0L).takeIf { it > 0 })
    }

    override val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val settings: Settings by lazy {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        object : Settings {
            override fun getString(key: String) = prefs.getString(key, null)
            override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
            override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
            override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
        }
    }
    override val shrineMedia: ShrineMedia by lazy { AndroidShrineMedia(this) }
    override val vault: Vault by lazy { AndroidVault(this) }
    override val sync by lazy { SyncManager(db, appScope, AndroidSyncPlatform(this), ::reportError) }

    override fun reportError(what: String, e: Throwable) {
        Log.e("Vesper", what, e)
        appScope.launch(Dispatchers.Main) {
            Toast.makeText(this@NotebookApp, "Could not $what", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- One-time safety net (added with DB v3, re-armed for v4: folder backup-pre-v4). Failures are logged and never block the app. ----

    private fun backupDir(): File? = getExternalFilesDir(null)?.let { File(it, "backup-pre-v4") }

    /** Copies pixel-notebook.db (+ -wal / -shm) to external files `backup-pre-v4/`; writes DONE last. */
    private fun backupRawDatabaseOnce() {
        try {
            val dir = backupDir() ?: return
            val done = File(dir, "DONE")
            if (done.exists()) return
            val main = getDatabasePath(DB_NAME)
            if (!main.exists()) return // fresh install: nothing to protect yet, retry next launch
            dir.mkdirs()
            for (suffix in listOf("", "-wal", "-shm")) {
                val src = File(main.path + suffix)
                if (src.exists()) src.copyTo(File(dir, src.name), overwrite = true)
            }
            done.writeText("ok")
        } catch (e: Throwable) {
            Log.e("Vesper", "pre-v4 backup failed", e)
        }
    }

    /** Writes `backup-pre-v4/export.json` (notes, tasks, lobes, ideas, posts; all columns) if it is not there yet. */
    private fun exportJsonOnce() {
        try {
            val dir = backupDir() ?: return
            val out = File(dir, "export.json")
            if (out.exists()) return
            dir.mkdirs()
            val sdb = db.openHelper.readableDatabase
            val root = JSONObject()
            for (table in listOf("notes", "tasks", "lobes", "ideas", "posts")) {
                val rows = JSONArray()
                sdb.query("SELECT * FROM $table").use { c ->
                    while (c.moveToNext()) {
                        val o = JSONObject()
                        for (i in 0 until c.columnCount) {
                            val v: Any? = when (c.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                                android.database.Cursor.FIELD_TYPE_STRING -> c.getString(i)
                                else -> null
                            }
                            o.put(c.getColumnName(i), v ?: JSONObject.NULL)
                        }
                        rows.put(o)
                    }
                }
                root.put(table, rows)
            }
            val tmp = File(dir, "export.json.tmp")
            tmp.writeText(root.toString(2))
            tmp.renameTo(out)
        } catch (e: Throwable) {
            Log.e("Vesper", "json export failed", e)
        }
    }

    companion object {
        const val DB_NAME = "pixel-notebook.db"
        const val PREFS = "vesper"
        const val KEY_LAST_BACKUP = "lastBackupAt"
    }
}
