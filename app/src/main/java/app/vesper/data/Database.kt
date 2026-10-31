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

/** One entry in the Shrine feed: optional text, optional photo. [imagePath] is a file name inside `filesDir/shrine`. */
@Entity(tableName = "posts")
data class Post(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String? = null,
    val imagePath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val editedAt: Long? = null,
)

@Dao
interface PostDao {
    @Query("SELECT * FROM posts ORDER BY createdAt ASC, id ASC")
    fun observeAll(): Flow<List<Post>>

    @Query("SELECT * FROM posts WHERE id = :id")
    suspend fun get(id: Long): Post?

    @Insert
    suspend fun insert(post: Post): Long

    @Update
    suspend fun update(post: Post)

    @Query("DELETE FROM posts WHERE id = :id")
    suspend fun delete(id: Long)
}

/** v2 -> v3: adds the Shrine table. Notes, tasks and Brain tables are not touched. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `posts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT, `imagePath` TEXT, `createdAt` INTEGER NOT NULL, `editedAt` INTEGER)")
    }
}

/** A plan for one local day. [day] is `LocalDate.toEpochDay()`. */
@Entity(tableName = "plans", indices = [Index("day")])
data class Plan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    val text: String,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A Brain lobe marked as the focus of one day. Removed automatically when the lobe is deleted. */
@Entity(
    tableName = "day_focus",
    primaryKeys = ["day", "lobeId"],
    foreignKeys = [ForeignKey(Lobe::class, ["id"], ["lobeId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("lobeId")],
)
data class DayFocus(
    val day: Long,
    val lobeId: Long,
)

@Dao
interface MoonsDao {
    @Query("SELECT * FROM plans WHERE day BETWEEN :from AND :to ORDER BY createdAt ASC, id ASC")
    fun observePlans(from: Long, to: Long): Flow<List<Plan>>

    @Query("SELECT * FROM plans ORDER BY createdAt ASC, id ASC")
    fun observeAllPlans(): Flow<List<Plan>>

    @Insert
    suspend fun insertPlan(plan: Plan): Long

    @Update
    suspend fun updatePlan(plan: Plan)

    @Query("DELETE FROM plans WHERE id = :id")
    suspend fun deletePlan(id: Long)

    @Query("SELECT * FROM day_focus")
    fun observeFocus(): Flow<List<DayFocus>>

    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertFocus(focus: DayFocus)

    @Query("DELETE FROM day_focus WHERE day = :day AND lobeId = :lobeId")
    suspend fun deleteFocus(day: Long, lobeId: Long)
}

/** v3 -> v4: adds the Moons tables. Every existing table is left untouched. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `plans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `day` INTEGER NOT NULL, `text` TEXT NOT NULL, `done` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_plans_day` ON `plans` (`day`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `day_focus` (`day` INTEGER NOT NULL, `lobeId` INTEGER NOT NULL, PRIMARY KEY(`day`, `lobeId`), FOREIGN KEY(`lobeId`) REFERENCES `lobes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_day_focus_lobeId` ON `day_focus` (`lobeId`)")
    }
}

@Database(entities = [Note::class, Task::class, Lobe::class, Idea::class, Post::class, Plan::class, DayFocus::class], version = 4, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun taskDao(): TaskDao
    abstract fun brainDao(): BrainDao
    abstract fun postDao(): PostDao
    abstract fun moonsDao(): MoonsDao
}
