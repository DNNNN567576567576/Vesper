package app.vesper.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    /** Internal only (used to list recently edited notes first). Never shown in the UI. */
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC, id DESC")
    fun observeAll(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Long): Note?

    @Insert
    suspend fun insert(note: Note): Long

    @Update
    suspend fun update(note: Note)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY id ASC")
    fun observeAll(): Flow<List<Task>>

    @Insert
    suspend fun insert(task: Task): Long

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: Long)
}

/** A region of the Brain map (a "sub-brain"). Placed automatically around the core by [slot]. */
@Entity(tableName = "lobes")
data class Lobe(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorIndex: Int,
    val slot: Int,
)

/** One thought inside a lobe. [noteId] optionally points at a note; it is not a foreign key because notes are deleted permanently. */
@Entity(
    tableName = "ideas",
    foreignKeys = [ForeignKey(Lobe::class, ["id"], ["lobeId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("lobeId")],
)
data class Idea(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lobeId: Long,
    val title: String,
    val body: String,
    val noteId: Long? = null,
    /** Internal only, used to list recent thoughts first. Never shown. */
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface BrainDao {
    @Query("SELECT * FROM lobes ORDER BY slot ASC")
    fun observeLobes(): Flow<List<Lobe>>

    @Query("SELECT * FROM ideas ORDER BY updatedAt DESC, id DESC")
    fun observeIdeas(): Flow<List<Idea>>

    @Insert
    suspend fun insertLobe(lobe: Lobe): Long

    @Query("DELETE FROM lobes WHERE id = :id")
    suspend fun deleteLobe(id: Long)

    @Insert
    suspend fun insertIdea(idea: Idea): Long

    @Update
    suspend fun updateIdea(idea: Idea)

    @Query("DELETE FROM ideas WHERE id = :id")
    suspend fun deleteIdea(id: Long)
}

/** v1 -> v2: adds the Brain tables. Notes and tasks are not touched. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `lobes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `colorIndex` INTEGER NOT NULL, `slot` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `ideas` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `lobeId` INTEGER NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `noteId` INTEGER, `updatedAt` INTEGER NOT NULL, FOREIGN KEY(`lobeId`) REFERENCES `lobes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ideas_lobeId` ON `ideas` (`lobeId`)")
    }
}

@Database(entities = [Note::class, Task::class, Lobe::class, Idea::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun taskDao(): TaskDao
    abstract fun brainDao(): BrainDao
}
