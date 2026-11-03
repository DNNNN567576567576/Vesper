package app.vesper.data

import androidx.room.ColumnInfo
import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notes", indices = [Index(value = ["uid"], unique = true)])
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    /** Internal only (used to list recently edited notes first). Never shown in the UI. */
    val updatedAt: Long = System.currentTimeMillis(),
    /** Order in the hand-sorted (manual) list, lowest first. New notes take the lowest value so they land on top. */
    val position: Int = 0,
    /** Set = this is a page inside the main scroll with that id (one level only); null = a top-level scroll. */
    val parentId: Long? = null,
    /** Sync identity, the same on every device. Rows from before v9 got '<table>-<id>'; see [MIGRATION_8_9]. */
    @ColumnInfo(defaultValue = "''") val uid: String = newUid(),
    /** Sync clock (epoch ms), kept current by the [syncTriggerSql] triggers; 0 = stamped on insert. Not shown in the UI. */
    @ColumnInfo(defaultValue = "0") val syncAt: Long = 0,
)

@Entity(tableName = "tasks", indices = [Index(value = ["uid"], unique = true)])
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    /** Epoch millis when the candle was snuffed; null = still lit. Snuffed tasks are kept forever. */
    val snuffedAt: Long? = null,
    /** Sync identity, the same on every device. Rows from before v9 got '<table>-<id>'; see [MIGRATION_8_9]. */
    @ColumnInfo(defaultValue = "''") val uid: String = newUid(),
    /** Sync clock (epoch ms), kept current by the [syncTriggerSql] triggers; 0 = stamped on insert. Not shown in the UI. */
    @ColumnInfo(defaultValue = "0") val syncAt: Long = 0,
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC, id DESC")
    fun observeAll(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Long): Note?

    @Insert
    suspend fun insert(note: Note): Long

    /** Edits text only, so the manual [Note.position] survives. */
    @Query("UPDATE notes SET title = :title, content = :content, updatedAt = :at WHERE id = :id")
    suspend fun updateText(id: Long, title: String, content: String, at: Long)

    @Query("SELECT MIN(position) FROM notes")
    suspend fun minPosition(): Int?

    @Query("UPDATE notes SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Int)

    @Query("SELECT * FROM notes WHERE parentId = :parentId ORDER BY id ASC")
    fun observePages(parentId: Long): Flow<List<Note>>

    /** One statement, so a main scroll and all its pages go together. */
    @Query("DELETE FROM notes WHERE id = :id OR parentId = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE snuffedAt IS NULL ORDER BY id ASC")
    fun observeLit(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE snuffedAt IS NOT NULL ORDER BY snuffedAt DESC")
    fun observeSnuffed(): Flow<List<Task>>

    @Insert
    suspend fun insert(task: Task): Long

    @Query("UPDATE tasks SET snuffedAt = :at WHERE id = :id")
    suspend fun snuff(id: Long, at: Long)

    @Query("UPDATE tasks SET snuffedAt = NULL WHERE id = :id")
    suspend fun relight(id: Long)
}

/** A region of the Brain map (a "sub-brain"). Placed automatically around the core by [slot]. */
@Entity(tableName = "lobes", indices = [Index(value = ["uid"], unique = true)])
data class Lobe(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorIndex: Int,
    val slot: Int,
    /** Sync identity, the same on every device. Rows from before v9 got '<table>-<id>'; see [MIGRATION_8_9]. */
    @ColumnInfo(defaultValue = "''") val uid: String = newUid(),
    /** Sync clock (epoch ms), kept current by the [syncTriggerSql] triggers; 0 = stamped on insert. Not shown in the UI. */
    @ColumnInfo(defaultValue = "0") val syncAt: Long = 0,
)

/** One thought inside a lobe. [noteId] optionally points at a note; it is not a foreign key because notes are deleted permanently. */
@Entity(
    tableName = "ideas",
    foreignKeys = [ForeignKey(Lobe::class, ["id"], ["lobeId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("lobeId"), Index(value = ["uid"], unique = true)],
)
data class Idea(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lobeId: Long,
    val title: String,
    val body: String,
    val noteId: Long? = null,
    /** Internal only, used to list recent thoughts first. Never shown. */
    val updatedAt: Long = System.currentTimeMillis(),
    /** Sync identity, the same on every device. Rows from before v9 got '<table>-<id>'; see [MIGRATION_8_9]. */
    @ColumnInfo(defaultValue = "''") val uid: String = newUid(),
    /** Sync clock (epoch ms), kept current by the [syncTriggerSql] triggers; 0 = stamped on insert. Not shown in the UI. */
    @ColumnInfo(defaultValue = "0") val syncAt: Long = 0,
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

/** One entry in the Shrine feed: optional text, optional photo or video. [imagePath]/[videoPath] are file names inside `filesDir/shrine`. */
@Entity(tableName = "posts")
data class Post(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String? = null,
    val imagePath: String? = null,
    val videoPath: String? = null,
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

/** A plan for one local day. [day] is `LocalDate.toEpochDay()`. */
@Entity(tableName = "plans", indices = [Index("day"), Index(value = ["uid"], unique = true)])
data class Plan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    val text: String,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** Sync identity, the same on every device. Rows from before v9 got '<table>-<id>'; see [MIGRATION_8_9]. */
    @ColumnInfo(defaultValue = "''") val uid: String = newUid(),
    /** Sync clock (epoch ms), kept current by the [syncTriggerSql] triggers; 0 = stamped on insert. Not shown in the UI. */
    @ColumnInfo(defaultValue = "0") val syncAt: Long = 0,
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

/** A deleted synced row, written by the delete triggers (FK cascades included) so peers can delete it too. Kept forever. */
@Entity(tableName = "sync_tombstones", primaryKeys = ["tbl", "uid"])
data class SyncTombstone(val tbl: String, val uid: String, val deletedAt: Long)

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
fun newUid(): String = kotlin.uuid.Uuid.random().toHexString()

/** The tables that carry uid + syncAt. day_focus and posts are not synced yet. */
val SYNCED_TABLES = listOf("notes", "tasks", "lobes", "ideas", "plans")

internal const val NOW_MS = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"

/**
 * Change tracking without touching any DAO. Insert: fill an empty uid and a 0 syncAt. Update: bump syncAt unless the
 * statement itself raised it (sync code writes newer explicit values; a stale full-row @Update writing an older one is
 * still bumped). Delete: tombstone, which also fires for FK cascade deletes. recursive_triggers is off, so the
 * triggers' own UPDATEs never re-fire the trigger that issued them.
 */
fun syncTriggerSql(t: String) = listOf(
    "CREATE TRIGGER IF NOT EXISTS `sync_${t}_insert` AFTER INSERT ON `$t` WHEN NEW.uid = '' OR NEW.syncAt = 0 BEGIN " +
        "UPDATE `$t` SET uid = CASE WHEN NEW.uid = '' THEN lower(hex(randomblob(16))) ELSE uid END, " +
        "syncAt = CASE WHEN NEW.syncAt = 0 THEN $NOW_MS ELSE syncAt END WHERE id = NEW.id; END",
    // OLD.uid <> '': the insert trigger filling a uid is not an edit.
    "CREATE TRIGGER IF NOT EXISTS `sync_${t}_update` AFTER UPDATE ON `$t` WHEN NEW.syncAt <= OLD.syncAt AND OLD.uid <> '' BEGIN " +
        "UPDATE `$t` SET syncAt = max($NOW_MS, OLD.syncAt + 1) WHERE id = NEW.id; END",
    "CREATE TRIGGER IF NOT EXISTS `sync_${t}_delete` AFTER DELETE ON `$t` BEGIN " +
        "INSERT OR REPLACE INTO `sync_tombstones` (`tbl`, `uid`, `deletedAt`) VALUES ('$t', OLD.uid, $NOW_MS); END",
)

/** Bump together with a new MIGRATION_x_y added to [ALL_MIGRATIONS]; MigrationTest fails if either is forgotten. */
const val DB_VERSION = 9

@Database(entities = [Note::class, Task::class, Lobe::class, Idea::class, Post::class, Plan::class, DayFocus::class, SyncTombstone::class], version = DB_VERSION, exportSchema = true)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun taskDao(): TaskDao
    abstract fun brainDao(): BrainDao
    abstract fun postDao(): PostDao
    abstract fun moonsDao(): MoonsDao
}

/** Room KMP: KSP generates the actual for each target. Android still builds through Room.databaseBuilder(context, AppDatabase::class.java, name). */
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
