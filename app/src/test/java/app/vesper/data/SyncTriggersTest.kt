package app.vesper.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The sync triggers must exist and behave the same on a migrated phone DB and on a fresh install. Runs on the JVM (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncTriggersTest {
    @get:Rule
    val helper = migrationTestHelper()

    private fun freshDb() = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java, "fresh.db")
        .addMigrations(*ALL_MIGRATIONS).addCallback(SYNC_TRIGGERS_CALLBACK).build()

    private fun SupportSQLiteDatabase.long(sql: String) = query(sql).use { it.moveToFirst(); it.getLong(0) }
    private fun SupportSQLiteDatabase.str(sql: String) = query(sql).use { it.moveToFirst(); it.getString(0) }
    private fun SupportSQLiteDatabase.tombstones() = query("SELECT tbl || ':' || uid FROM sync_tombstones").use { c ->
        generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet()
    }

    /** Raw SQL, so it exercises the triggers exactly as any writer (DAO, sync applier, desktop) would. */
    private fun SupportSQLiteDatabase.assertTriggersWork() {
        val triggers = query("SELECT name FROM sqlite_master WHERE type = 'trigger'").use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet() }
        assertEquals(SYNCED_TABLES.flatMap { t -> listOf("insert", "update", "delete").map { "sync_${t}_$it" } }.toSet(), triggers.filter { it.startsWith("sync_") }.toSet())

        // insert without uid/syncAt: random uid, syncAt = now
        val before = System.currentTimeMillis()
        execSQL("INSERT INTO notes (id, title, content, updatedAt, position) VALUES (100, 'a', 'x', 1, 0)")
        val after = System.currentTimeMillis()
        assertTrue(str("SELECT uid FROM notes WHERE id = 100").matches(Regex("[0-9a-f]{32}")))
        assertTrue(long("SELECT syncAt FROM notes WHERE id = 100") in before - 1..after) // -1: SQLite ms clock goes through a double

        // insert with explicit values (what the sync applier does): kept as is, nothing bumped
        execSQL("INSERT INTO notes (id, title, content, updatedAt, position, uid, syncAt) VALUES (101, 'b', 'x', 1, 0, 'remote-1', 1000)")
        assertEquals("remote-1", str("SELECT uid FROM notes WHERE id = 101"))
        assertEquals(1000, long("SELECT syncAt FROM notes WHERE id = 101"))

        // a local edit bumps syncAt to now
        execSQL("UPDATE notes SET title = 'b2' WHERE id = 101")
        assertTrue(long("SELECT syncAt FROM notes WHERE id = 101") >= before - 1)

        // an explicit newer syncAt is preserved exactly (no echo)
        val future = System.currentTimeMillis() + 1_000_000_000
        execSQL("UPDATE notes SET title = 'b3', syncAt = $future WHERE id = 101")
        assertEquals(future, long("SELECT syncAt FROM notes WHERE id = 101"))
        // an edit after that still moves forward even though 'now' is behind it: OLD.syncAt + 1
        execSQL("UPDATE notes SET position = 3 WHERE id = 101")
        assertEquals(future + 1, long("SELECT syncAt FROM notes WHERE id = 101"))
        // a stale full-row write (older syncAt, like @Update with an old copy) is still tracked as an edit
        execSQL("UPDATE notes SET title = 'stale', syncAt = 1000 WHERE id = 101")
        assertEquals(future + 2, long("SELECT syncAt FROM notes WHERE id = 101"))

        // deleting a main scroll with its pages tombstones every page (one statement, as NoteDao.delete)
        execSQL("INSERT INTO notes (id, title, content, updatedAt, position, parentId, uid) VALUES (102, 'page', 'x', 1, 0, 101, 'page-uid')")
        execSQL("DELETE FROM notes WHERE id = 101 OR parentId = 101")
        assertEquals(setOf("notes:remote-1", "notes:page-uid"), tombstones())
        assertTrue(long("SELECT deletedAt FROM sync_tombstones WHERE uid = 'page-uid'") >= before - 1)

        // deleting a lobe cascades to its ideas, and each cascaded idea leaves a tombstone
        execSQL("PRAGMA foreign_keys = ON") // Room turns this on for the app; the migration helper does not
        execSQL("INSERT INTO lobes (id, name, colorIndex, slot, uid) VALUES (50, 'Work', 0, 9, 'lobe-uid')")
        execSQL("INSERT INTO ideas (lobeId, title, body, updatedAt, uid) VALUES (50, 'i1', '', 1, 'idea-1'), (50, 'i2', '', 1, 'idea-2')")
        execSQL("DELETE FROM lobes WHERE id = 50")
        assertEquals(0, long("SELECT COUNT(*) FROM ideas WHERE lobeId = 50"))
        assertTrue(tombstones().containsAll(setOf("lobes:lobe-uid", "ideas:idea-1", "ideas:idea-2")))

        // tasks and plans are wired too
        execSQL("INSERT INTO tasks (id, text, uid) VALUES (70, 't', 'task-uid')")
        execSQL("UPDATE tasks SET snuffedAt = 5 WHERE id = 70")
        assertTrue(long("SELECT syncAt FROM tasks WHERE id = 70") >= before - 1)
        execSQL("DELETE FROM tasks WHERE id = 70")
        execSQL("INSERT INTO plans (id, day, text, done, createdAt) VALUES (80, 20000, 'p', 0, 1)")
        val planUid = str("SELECT uid FROM plans WHERE id = 80")
        execSQL("DELETE FROM plans WHERE id = 80")
        assertTrue(tombstones().containsAll(setOf("tasks:task-uid", "plans:$planUid")))
    }

    @Test
    fun triggersWorkOnMigratedDb() {
        helper.createDatabase("m.db", 8).apply { execSQL("INSERT INTO notes (id, title, content, updatedAt, position) VALUES (1, 'old', 'x', 1700000000000, 0)"); close() }
        val db = helper.runMigrationsAndValidate("m.db", DB_VERSION, true, MIGRATION_8_9, MIGRATION_9_10)
        db.assertTriggersWork()
        assertEquals("notes-1", db.str("SELECT uid FROM notes WHERE id = 1")) // backfilled row untouched by the tests above
    }

    @Test
    fun triggersWorkOnFreshDb() {
        val db = freshDb()
        try { db.openHelper.writableDatabase.assertTriggersWork() } finally { db.close() }
    }

    @Test
    fun daoWritesKeepUidAndTrackChanges(): Unit = runBlocking {
        val db = freshDb()
        try {
            val noteId = db.noteDao().insert(Note(title = "t", content = "c"))
            val note = db.noteDao().get(noteId)!!
            assertTrue(note.uid.matches(Regex("[0-9a-f]{32}"))) // Kotlin default, not the trigger
            assertTrue(note.syncAt > 0)
            db.noteDao().updateText(noteId, "t2", "c2", 1)
            val edited = db.noteDao().get(noteId)!!
            assertEquals(note.uid, edited.uid); assertTrue(edited.syncAt > note.syncAt)

            // A @Update writes the whole row from a possibly stale copy: uid survives, syncAt keeps rising
            val moons = db.moonsDao()
            moons.insertPlan(Plan(day = 20000, text = "p"))
            val plan = moons.observeAllPlans().first().single()
            db.syncDao().update(plan.copy(done = true))
            val once = moons.observeAllPlans().first().single()
            db.syncDao().update(plan.copy(text = "p2")) // same stale object again
            val twice = moons.observeAllPlans().first().single()
            assertEquals(plan.uid, twice.uid)
            assertTrue(once.syncAt > plan.syncAt); assertTrue(twice.syncAt > once.syncAt)

            val lobe = db.brainDao().insertLobe(Lobe(name = "L", colorIndex = 0, slot = 0))
            db.brainDao().insertIdea(Idea(lobeId = lobe, title = "i", body = ""))
            val idea = db.brainDao().observeIdeas().first().single()
            db.syncDao().update(idea.copy(title = "i2"))
            assertEquals(idea.uid, db.brainDao().observeIdeas().first().single().uid)
            db.brainDao().deleteLobe(lobe) // through Room, which enables foreign keys
            val tombs = db.openHelper.writableDatabase.tombstones()
            assertTrue(tombs.containsAll(setOf("ideas:${idea.uid}")))
        } finally { db.close() }
    }
}
