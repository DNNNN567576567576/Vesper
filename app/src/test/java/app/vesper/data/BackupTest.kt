package app.vesper.data

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.vesper.NotebookApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** Runs on the JVM (Robolectric), never touches a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupTest {
    private val app = ApplicationProvider.getApplicationContext<NotebookApp>()
    private val shrine = File(app.filesDir, "shrine")
    private val photo = Random(7).nextBytes(300_000)
    private val video = Random(8).nextBytes(200_000)

    @get:Rule
    val helper = migrationTestHelper()

    private data class Snapshot(
        val notes: List<Note>, val lit: List<Task>, val snuffed: List<Task>, val lobes: List<Lobe>,
        val ideas: List<Idea>, val posts: List<Post>, val plans: List<Plan>, val focus: List<DayFocus>,
    )

    private suspend fun AppDatabase.snapshot() = Snapshot(
        noteDao().observeAll().first(), taskDao().observeLit().first(), taskDao().observeSnuffed().first(),
        brainDao().observeLobes().first(), brainDao().observeIdeas().first(), postDao().observeAll().first(),
        moonsDao().observeAllPlans().first(), moonsDao().observeFocus().first(),
    )

    private suspend fun seed() {
        val db = app.db
        db.noteDao().insert(Note(title = "Dream log", content = "flew over the sea", updatedAt = 1700000000000, position = 7))
        db.taskDao().insert(Task(text = "buy candles"))
        db.taskDao().snuff(db.taskDao().insert(Task(text = "light the lamp")), 1700000003000)
        val lobe = db.brainDao().insertLobe(Lobe(name = "Work", colorIndex = 2, slot = 0))
        db.brainDao().insertIdea(Idea(lobeId = lobe, title = "Idea", body = "body text", noteId = 1, updatedAt = 1700000001000))
        db.postDao().insert(Post(text = "hello", imagePath = "p.jpg", createdAt = 1700000002000))
        db.postDao().insert(Post(videoPath = "v.mp4", createdAt = 1700000002500))
        db.moonsDao().insertPlan(Plan(day = 20000, text = "plan", createdAt = 1700000004000))
        db.moonsDao().insertFocus(DayFocus(20000, lobe))
        shrine.mkdirs(); File(shrine, "p.jpg").writeBytes(photo); File(shrine, "v.mp4").writeBytes(video)
    }

    private fun reopen() = Room.databaseBuilder(app, AppDatabase::class.java, NotebookApp.DB_NAME).addMigrations(*ALL_MIGRATIONS).build()

    private fun zip(name: String, entries: Map<String, ByteArray>): Uri {
        val f = File(app.cacheDir, name)
        ZipOutputStream(f.outputStream()).use { z -> entries.forEach { (k, v) -> z.putNextEntry(ZipEntry(k)); z.write(v); z.closeEntry() } }
        return Uri.fromFile(f)
    }

    private fun manifest(dbVersion: Int) = """{"app":"vesper","format":1,"dbVersion":$dbVersion,"createdAt":1,"appVersion":"t"}""".toByteArray()

    @Test
    fun roundTripRestoresEverything(): Unit = runBlocking {
        seed()
        val before = app.db.snapshot()
        assertEquals(1, before.snuffed.size)
        val file = File(app.cacheDir, "vesper.zip")
        writeBackup(app, Uri.fromFile(file))
        assertNotNull(app.lastBackupAt.value)
        assertEquals(setOf("manifest.json", "database.db", "shrine/p.jpg", "shrine/v.mp4"), ZipFile(file).use { z -> z.entries().toList().map { it.name }.toSet() })

        // change everything after the backup
        app.db.noteDao().delete(1)
        app.db.noteDao().insert(Note(title = "later", content = "x"))
        app.db.taskDao().relight(2)
        app.db.brainDao().deleteLobe(1)
        File(shrine, "p.jpg").delete(); File(shrine, "x.jpg").writeBytes(byteArrayOf(1, 2, 3))

        restoreBackup(app, Uri.fromFile(file))

        val db = reopen()
        try { assertEquals(before, db.snapshot()) } finally { db.close() }
        assertEquals(listOf("p.jpg", "v.mp4"), shrine.list()!!.sorted())
        assertArrayEquals(photo, File(shrine, "p.jpg").readBytes())
        assertArrayEquals(video, File(shrine, "v.mp4").readBytes())
        assertFalse(File(app.noBackupFilesDir, "restore-staging").exists())

        // the safety copy holds the data as it was right before the restore
        val safety = File(app.noBackupFilesDir, "before-restore.zip")
        assertTrue(ZipFile(safety).use { it.getEntry("shrine/x.jpg") != null && it.getEntry("database.db") != null })
    }

    @Test
    fun restoresAndMigratesV4Backup() = restoresAndMigratesOldVersionBackup(4)

    @Test
    fun restoresAndMigratesV8Backup() = restoresAndMigratesOldVersionBackup(8)

    private fun restoresAndMigratesOldVersionBackup(version: Int): Unit = runBlocking {
        helper.createDatabase("old.db", version).apply {
            if (version < 6) execSQL("INSERT INTO notes (id, title, content, updatedAt) VALUES (1, 'Dream log', 'flew over the sea', 1700000000000)")
            else execSQL("INSERT INTO notes (id, title, content, updatedAt, position) VALUES (1, 'Dream log', 'flew over the sea', 1700000000000, 0)")
            execSQL("INSERT INTO tasks (id, text) VALUES (1, 'buy candles')")
            execSQL("INSERT INTO lobes (id, name, colorIndex, slot) VALUES (1, 'Work', 2, 0)")
            execSQL("INSERT INTO plans (day, text, done, createdAt) VALUES (20000, 'plan', 0, 1)")
            close()
        }
        seed() // live data that the restore replaces
        val uri = zip("old.zip", mapOf("manifest.json" to manifest(version), "database.db" to app.getDatabasePath("old.db").readBytes()))

        restoreBackup(app, uri)

        val db = reopen()
        try {
            val s = db.snapshot()
            assertEquals(listOf("Dream log"), s.notes.map { it.title })
            assertEquals(listOf(Triple(1L, "buy candles", null)), s.lit.map { Triple(it.id, it.text, it.snuffedAt) })
            // deterministic uids, so restoring this backup again can never duplicate rows on sync
            assertEquals(listOf("notes-1"), s.notes.map { it.uid })
            assertEquals(listOf("tasks-1"), s.lit.map { it.uid })
            assertEquals(listOf("lobes-1"), s.lobes.map { it.uid })
            assertEquals(listOf("plans-1"), s.plans.map { it.uid })
            assertEquals(emptyList<Task>(), s.snuffed)
            assertEquals(listOf("Work"), s.lobes.map { it.name })
            assertEquals(listOf("plan"), s.plans.map { it.text })
        } finally { db.close() }
        assertEquals(0, shrine.list()!!.size) // backup had no photos
    }

    private suspend fun assertRejected(uri: Uri, message: String) {
        try {
            restoreBackup(app, uri); fail("restore should have been refused")
        } catch (e: BackupException) {
            assertEquals(message, e.message)
            assertFalse(e.restartNeeded)
        }
        assertEquals("Dream log", app.db.noteDao().get(1)!!.title) // live DB still open and untouched
        assertEquals(1, app.db.taskDao().observeSnuffed().first().size)
        assertArrayEquals(photo, File(shrine, "p.jpg").readBytes())
        assertFalse(File(app.noBackupFilesDir, "before-restore.zip").exists())
    }

    @Test
    fun rejectsBadFilesAndLeavesDataUntouched(): Unit = runBlocking {
        seed()
        val good = File(app.cacheDir, "good.zip")
        writeBackup(app, Uri.fromFile(good))
        val dbBytes = ZipFile(good).use { z -> z.getInputStream(z.getEntry("database.db")).readBytes() }

        val random = File(app.cacheDir, "random.bin").apply { writeBytes(Random(1).nextBytes(5000)) }
        assertRejected(Uri.fromFile(random), "this isn't a vesper backup.")
        assertRejected(zip("nomanifest.zip", mapOf("database.db" to dbBytes)), "this isn't a vesper backup.")
        assertRejected(zip("newer.zip", mapOf("manifest.json" to manifest(DB_VERSION + 1), "database.db" to dbBytes)), "this backup is from a newer vesper.")
        assertRejected(zip("evil.zip", mapOf("manifest.json" to manifest(DB_VERSION), "database.db" to dbBytes, "../evil" to byteArrayOf(1))), "this isn't a vesper backup.")
        assertFalse(File(app.noBackupFilesDir, "evil").exists()) // where ../evil would have landed
        assertRejected(zip("garbage.zip", mapOf("manifest.json" to manifest(DB_VERSION), "database.db" to Random(2).nextBytes(8192))), "this backup is damaged.")
    }
}
