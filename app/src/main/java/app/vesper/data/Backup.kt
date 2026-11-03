package app.vesper.data

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import app.vesper.NotebookApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/*
 * Backup zip layout (FORMAT 1):
 *   manifest.json   {"app":"vesper","format":1,"dbVersion":5,"createdAt":<millis>,"appVersion":"1.4"}
 *   database.db     single-file SQLite copy of pixel-notebook.db (no -wal)
 *   shrine/<name>   every file in filesDir/shrine (Grimoire photos)
 */

private const val FORMAT = 1
private const val MANIFEST = "manifest.json"
private const val DB_ENTRY = "database.db"
private const val SHRINE = "shrine"
private const val NOT_BACKUP = "this isn't a vesper backup."
private const val DAMAGED = "this backup is damaged."

/** Writes everything (database + Grimoire photos) as one zip to [uri] (from ACTION_CREATE_DOCUMENT) and records [NotebookApp.lastBackupAt]. */
suspend fun writeBackup(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
    val app = context.applicationContext as NotebookApp
    try {
        val out = app.contentResolver.openOutputStream(uri) ?: throw BackupException("could not write the backup.")
        writeZip(app, out)
    } catch (e: Exception) {
        if (e is BackupException || e is CancellationException) throw e
        Log.e("Vesper", "backup failed", e)
        throw BackupException("could not write the backup.", cause = e)
    }
    val now = System.currentTimeMillis()
    app.getSharedPreferences(NotebookApp.PREFS, Context.MODE_PRIVATE).edit().putLong(NotebookApp.KEY_LAST_BACKUP, now).apply()
    app.lastBackupAt.value = now
}

/**
 * Replaces all data with the backup at [uri] (from ACTION_OPEN_DOCUMENT). Nothing changes until the backup is fully
 * validated (and migrated in a scratch copy); the current data is first saved to `noBackupFilesDir/before-restore.zip`.
 * On success the live DB is closed: the caller must call [restartApp] right away and touch nothing else.
 */
suspend fun restoreBackup(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
    val app = context.applicationContext as NotebookApp
    val staging = File(app.noBackupFilesDir, "restore-staging")
    try {
        val input = try { app.contentResolver.openInputStream(uri) } catch (e: Exception) { null }
            ?: throw BackupException("could not read the backup.")
        input.use { unzip(it, staging) }
        checkStaged(app, staging)

        val safety = File(app.noBackupFilesDir, "before-restore.zip")
        val safetyTmp = File(app.noBackupFilesDir, "before-restore.zip.tmp")
        safetyTmp.outputStream().use { writeZip(app, it) }
        move(safetyTmp, safety)

        withContext(NonCancellable) {
            app.db.close() // after this nothing may use the DB until the process restarts
            try {
                install(app, staging)
            } catch (e: Exception) {
                Log.e("Vesper", "restore failed, rolling back", e)
                try {
                    safety.inputStream().use { unzip(it, staging) }
                    install(app, staging)
                } catch (e2: Exception) {
                    Log.e("Vesper", "rollback failed", e2)
                    throw BackupException("restore failed. your old data is kept in before-restore.zip.", true, e2)
                }
                throw BackupException("restore failed. nothing was changed.", true, e)
            }
        }
    } catch (e: Exception) {
        if (e is BackupException || e is CancellationException) throw e
        Log.e("Vesper", "restore failed", e)
        throw BackupException("could not restore the backup.", cause = e)
    } finally {
        staging.deleteRecursively()
    }
}

/** Relaunches the app in a fresh process (call after [restoreBackup]). Not unit-tested: it ends the process. */
fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
    context.startActivity(Intent.makeRestartActivityTask(launch!!.component))
    Runtime.getRuntime().exit(0)
}

private suspend fun writeZip(app: NotebookApp, out: OutputStream) {
    val tmp = File(app.noBackupFilesDir, "backup-db.tmp")
    try {
        val version = snapshotDb(app, tmp)
        ZipOutputStream(out.buffered()).use { zip ->
            val manifest = JSONObject()
                .put("app", "vesper").put("format", FORMAT).put("dbVersion", version)
                .put("createdAt", System.currentTimeMillis())
                .put("appVersion", app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "")
            zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry(DB_ENTRY)); tmp.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            File(app.filesDir, SHRINE).listFiles()?.filter { it.isFile }?.forEach { f ->
                zip.putNextEntry(ZipEntry("$SHRINE/${f.name}")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    } finally {
        for (s in listOf("", "-wal", "-shm", "-journal")) File(tmp.path + s).delete()
    }
}

/** Copies the live DB to [tmp] as one consistent self-contained file; returns its schema version. */
private suspend fun snapshotDb(app: NotebookApp, tmp: File): Int {
    val live = app.getDatabasePath(NotebookApp.DB_NAME)
    val tmpWal = File(tmp.path + "-wal")
    // Holding the write transaction means no commit or checkpoint can touch the db or -wal file while we copy both.
    app.db.withTransaction {
        live.copyTo(tmp, overwrite = true)
        val wal = File(live.path + "-wal")
        if (wal.exists()) wal.copyTo(tmpWal, overwrite = true) else tmpWal.delete()
    }
    // Opening the copy replays its -wal; journal_mode=DELETE folds it into the main file.
    val version = SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
        db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst(); check(it.getString(0) == "ok") { "live db fails integrity_check" } }
        db.version
    }
    check(!tmpWal.exists()) { "snapshot still has a -wal" }
    return version
}

/** Extracts only the known entry names into a fresh [dir]; anything else (incl. `..` or absolute paths) rejects the whole file. */
private fun unzip(input: InputStream, dir: File) {
    dir.deleteRecursively(); dir.mkdirs()
    try {
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory && e.name == "$SHRINE/") continue
                val photo = e.name.removePrefix("$SHRINE/")
                val ok = e.name == MANIFEST || e.name == DB_ENTRY ||
                    (photo != e.name && photo.isNotEmpty() && photo != "." && photo != ".." && '/' !in photo && '\\' !in photo)
                if (!ok) throw BackupException(NOT_BACKUP)
                val out = File(dir, e.name)
                out.parentFile!!.mkdirs()
                out.outputStream().use { zip.copyTo(it) }
            }
        }
    } catch (e: java.io.IOException) {
        throw BackupException(NOT_BACKUP, cause = e) // not a zip, truncated, or rejected by Android's own zip path check
    }
}

/** Validates [dir] and migrates its database to [DB_VERSION] in place, exactly as the app would open it. */
private fun checkStaged(context: Context, dir: File) {
    val json = try { JSONObject(File(dir, MANIFEST).readText()) } catch (e: Exception) { throw BackupException(NOT_BACKUP, cause = e) }
    if (json.optString("app") != "vesper") throw BackupException(NOT_BACKUP)
    val format = json.optInt("format", -1)
    val version = json.optInt("dbVersion", -1)
    if (format > FORMAT || version > DB_VERSION) throw BackupException("this backup is from a newer vesper.")
    if (format < 1 || version < 1) throw BackupException(NOT_BACKUP)
    val dbFile = File(dir, DB_ENTRY)
    if (!dbFile.isFile) throw BackupException(NOT_BACKUP)
    try {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst(); if (it.getString(0) != "ok") throw BackupException(DAMAGED) }
            if (db.version != version) throw BackupException(DAMAGED)
        }
        // Room opens, migrates and validates the schema; if this fails here, it would have failed on the next app start.
        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath).addMigrations(*ALL_MIGRATIONS).build()
        try { room.openHelper.writableDatabase.query("SELECT COUNT(*) FROM notes").close() } finally { room.close() }
    } catch (e: BackupException) {
        throw e
    } catch (e: Exception) {
        throw BackupException(DAMAGED, cause = e)
    }
    if (File(dbFile.path + "-wal").exists()) throw BackupException(DAMAGED) // close() should have folded it in
}

/** Swaps the staged DB and photos in. Only call with the live DB closed. */
private fun install(context: Context, staging: File) {
    val db = context.getDatabasePath(NotebookApp.DB_NAME)
    db.parentFile!!.mkdirs()
    for (s in listOf("-wal", "-shm", "-journal")) File(db.path + s).delete()
    File(staging, DB_ENTRY + "-shm").delete()
    move(File(staging, DB_ENTRY), db)
    val shrine = File(context.filesDir, SHRINE)
    val old = File(context.filesDir, "$SHRINE-old")
    old.deleteRecursively()
    if (shrine.exists()) move(shrine, old)
    val staged = File(staging, SHRINE)
    if (staged.exists()) move(staged, shrine) else shrine.mkdirs()
    old.deleteRecursively()
}

private fun move(from: File, to: File) {
    Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
}
