package app.vesper.data

import android.content.Context
import androidx.core.content.contentValuesOf
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs on the JVM (Robolectric), never touches a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private fun SupportSQLiteDatabase.insertNote() {
        execSQL("INSERT INTO notes (id, title, content, updatedAt) VALUES (1, 'Dream log', 'flew over the sea', 1700000000000)")
        execSQL("INSERT INTO tasks (id, text) VALUES (1, 'buy candles')")
    }

    private fun SupportSQLiteDatabase.assertNoteAndTask() {
        query("SELECT title, content, updatedAt FROM notes WHERE id = 1").use {
            assertEquals(1, it.count); it.moveToFirst()
            assertEquals("Dream log", it.getString(0))
            assertEquals("flew over the sea", it.getString(1))
            assertEquals(1700000000000, it.getLong(2))
        }
        query("SELECT text FROM tasks WHERE id = 1").use {
            assertEquals(1, it.count); it.moveToFirst()
            assertEquals("buy candles", it.getString(0))
        }
    }

    private fun SupportSQLiteDatabase.insertBrain() {
        execSQL("INSERT INTO lobes (id, name, colorIndex, slot) VALUES (1, 'Work', 2, 0)")
        execSQL("INSERT INTO ideas (id, lobeId, title, body, noteId, updatedAt) VALUES (1, 1, 'Idea', 'body text', 1, 1700000001000)")
    }

    private fun SupportSQLiteDatabase.assertBrain() {
        query("SELECT name, colorIndex, slot FROM lobes WHERE id = 1").use {
            assertEquals(1, it.count); it.moveToFirst()
            assertEquals("Work", it.getString(0)); assertEquals(2, it.getInt(1)); assertEquals(0, it.getInt(2))
        }
        query("SELECT lobeId, title, body, noteId, updatedAt FROM ideas WHERE id = 1").use {
            assertEquals(1, it.count); it.moveToFirst()
            assertEquals(1, it.getLong(0)); assertEquals("Idea", it.getString(1)); assertEquals("body text", it.getString(2))
            assertEquals(1, it.getLong(3)); assertEquals(1700000001000, it.getLong(4))
        }
    }

    private fun SupportSQLiteDatabase.assertIndexes(vararg names: String) {
        val have = query("SELECT name FROM sqlite_master WHERE type = 'index'").use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet() }
        assertEquals(emptySet<String>(), names.toSet() - have)
    }

    private fun SupportSQLiteDatabase.insertPost() {
        execSQL("INSERT INTO posts (id, text, imagePath, createdAt, editedAt) VALUES (1, 'hello', 'a.jpg', 1700000002000, NULL)")
    }

    private fun SupportSQLiteDatabase.assertPost() {
        query("SELECT text, imagePath, createdAt, editedAt FROM posts WHERE id = 1").use {
            assertEquals(1, it.count); it.moveToFirst()
            assertEquals("hello", it.getString(0)); assertEquals("a.jpg", it.getString(1))
            assertEquals(1700000002000, it.getLong(2)); assertEquals(true, it.isNull(3))
        }
    }

    @Test
    fun migrate1To2() {
        helper.createDatabase(name, 1).apply { insertNote(); close() }
        val db = helper.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2)
        db.assertNoteAndTask()
        db.assertIndexes("index_ideas_lobeId")
        db.insertBrain(); db.assertBrain() // new tables are usable
    }

    @Test
    fun migrate2To3() {
        helper.createDatabase(name, 2).apply { insertNote(); insertBrain(); close() }
        val db = helper.runMigrationsAndValidate(name, 3, true, MIGRATION_2_3)
        db.assertNoteAndTask(); db.assertBrain()
        db.insertPost(); db.assertPost()
    }

    @Test
    fun migrate3To4() {
        helper.createDatabase(name, 3).apply { insertNote(); insertBrain(); insertPost(); close() }
        val db = helper.runMigrationsAndValidate(name, 4, true, MIGRATION_3_4)
        db.assertNoteAndTask(); db.assertBrain(); db.assertPost()
        db.assertIndexes("index_plans_day", "index_day_focus_lobeId")
        db.execSQL("INSERT INTO plans (day, text, done, createdAt) VALUES (20000, 'plan', 0, 1)")
        db.execSQL("INSERT INTO day_focus (day, lobeId) VALUES (20000, 1)")
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM lobes WHERE id = 1") // FK cascade still wired
        db.query("SELECT COUNT(*) FROM day_focus").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    @Test
    fun migrate4To5() {
        helper.createDatabase(name, 4).apply { insertNote(); insertBrain(); insertPost(); execSQL("INSERT INTO tasks (id, text) VALUES (2, 'light the lamp')"); close() }
        val db = helper.runMigrationsAndValidate(name, 5, true, MIGRATION_4_5)
        db.assertNoteAndTask(); db.assertBrain(); db.assertPost()
        db.query("SELECT COUNT(*), COUNT(snuffedAt) FROM tasks").use {
            it.moveToFirst(); assertEquals(2, it.getInt(0)); assertEquals(0, it.getInt(1)) // all still lit
        }
        db.execSQL("UPDATE tasks SET snuffedAt = 1700000003000 WHERE id = 2") // column is usable
        db.query("SELECT snuffedAt FROM tasks WHERE id = 2").use { it.moveToFirst(); assertEquals(1700000003000, it.getLong(0)) }
    }

    @Test
    fun migrateAllFrom1() {
        helper.createDatabase(name, 1).apply { insertNote(); close() }
        helper.runMigrationsAndValidate(name, DB_VERSION, true, *ALL_MIGRATIONS).assertNoteAndTask()
    }

    @Test
    fun realDatabaseOpensUpgradedFile(): Unit = runBlocking {
        helper.createDatabase(name, 1).apply { insertNote(); close() }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, name).addMigrations(*ALL_MIGRATIONS).build()
        try {
            val note = db.noteDao().get(1)!!
            assertEquals("Dream log", note.title)
            assertEquals("flew over the sea", note.content)
            assertEquals("buy candles", db.taskDao().observeLit().first().single().text)
            // every new DAO works on the upgraded file
            db.brainDao().observeLobes().first(); db.postDao().observeAll().first(); db.moonsDao().observeAllPlans().first()
        } finally {
            db.close()
        }
    }

    @Test
    fun migrationsFormContinuousPathToLatestVersion() {
        var v = 1
        for (m in ALL_MIGRATIONS.sortedBy { it.startVersion }) {
            assertEquals("gap before migration to ${m.endVersion}", v, m.startVersion)
            v = m.endVersion
        }
        assertEquals("DB_VERSION and ALL_MIGRATIONS disagree", DB_VERSION, v)
        // Fails if the schema JSON for DB_VERSION was not exported (i.e. @Database version bumped without a build).
        helper.createDatabase(name, DB_VERSION).close()
    }
}
