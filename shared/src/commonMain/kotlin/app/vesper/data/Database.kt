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
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
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

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observe(id: Long): Flow<Note?>

    @Insert
    suspend fun insert(note: Note): Long

    /** A conflict copy; merge may already have built the same uid. */
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertCopy(note: Note)

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

/** A user-named group of ideas inside a lobe. Deleting one leaves its ideas directly on the lobe (the FK sets [Idea.branchId] to null). */
@Entity(
    tableName = "branches",
    foreignKeys = [ForeignKey(Lobe::class, ["id"], ["lobeId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("lobeId"), Index(value = ["uid"], unique = true)],
)
data class Branch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lobeId: Long,
    val name: String,
    /** Sync identity, the same on every device. */
    @ColumnInfo(defaultValue = "''") val uid: String = newUid(),
    /** Sync clock (epoch ms), kept current by the [syncTriggerSql] triggers; 0 = stamped on insert. Not shown in the UI. */
    @ColumnInfo(defaultValue = "0") val syncAt: Long = 0,
)

/** One thought inside a lobe. [noteId] optionally points at a note; it is not a foreign key because notes are deleted permanently. */
@Entity(
    tableName = "ideas",
    foreignKeys = [
        ForeignKey(Lobe::class, ["id"], ["lobeId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Branch::class, ["id"], ["branchId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("lobeId"), Index("branchId"), Index(value = ["uid"], unique = true)],
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
    /** The group it sits in; null = directly on the lobe. */
    val branchId: Long? = null,
    val link: String? = null,
    @ColumnInfo(defaultValue = "0") val done: Boolean = false,
)

@Dao
interface BrainDao {
    @Query("SELECT * FROM lobes ORDER BY slot ASC")
    fun observeLobes(): Flow<List<Lobe>>

    @Query("SELECT * FROM ideas ORDER BY updatedAt DESC, id DESC")
    fun observeIdeas(): Flow<List<Idea>>

    @Query("SELECT * FROM branches ORDER BY uid ASC")
    fun observeBranches(): Flow<List<Branch>>

    @Insert
    suspend fun insertLobe(lobe: Lobe): Long

    @Query("DELETE FROM lobes WHERE id = :id")
    suspend fun deleteLobe(id: Long)

    @Query("UPDATE lobes SET name = :name WHERE id = :id")
    suspend fun renameLobe(id: Long, name: String)

    /** Lobe [id] takes [slot]; a lobe already on [slot] takes [from], the moved one's old slot. */
    @Query("UPDATE lobes SET slot = CASE WHEN id = :id THEN :slot ELSE :from END WHERE id = :id OR slot = :slot")
    suspend fun moveLobe(id: Long, slot: Int, from: Int)

    @Query("SELECT EXISTS(SELECT 1 FROM lobes WHERE id = :id)")
    suspend fun lobeExists(id: Long): Boolean

    @Query("SELECT * FROM ideas WHERE id = :id")
    suspend fun getIdea(id: Long): Idea?

    @Insert
    suspend fun insertIdea(idea: Idea): Long

    /** A conflict copy; merge may already have built the same uid. */
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertIdeaCopy(idea: Idea)

    /** Named columns only: a whole-row write from a held copy would put back whatever sync changed since. */
    @Query("UPDATE ideas SET title = :title, body = :body, link = :link, branchId = :branchId, updatedAt = :at WHERE id = :id")
    suspend fun setIdeaFields(id: Long, title: String, body: String, link: String?, branchId: Long?, at: Long)

    /** A move: new home and a fresh updatedAt; the update trigger stamps syncAt, so the peer takes the move in by lobe/group uid. */
    @Query("UPDATE ideas SET lobeId = :lobeId, branchId = :branchId, updatedAt = :at WHERE id = :id")
    suspend fun moveIdea(id: Long, lobeId: Long, branchId: Long?, at: Long)

    /** A group moving worlds: the group row and every note in it follow, so the update triggers stamp them all for sync. */
    @Query("UPDATE branches SET lobeId = :lobeId WHERE id = :id")
    suspend fun moveBranch(id: Long, lobeId: Long)

    @Query("UPDATE ideas SET lobeId = :lobeId, updatedAt = :at WHERE branchId = :branchId")
    suspend fun moveBranchIdeas(branchId: Long, lobeId: Long, at: Long)

    @Query("SELECT * FROM branches WHERE id = :id")
    suspend fun getBranch(id: Long): Branch?

    @Query("SELECT * FROM lobes WHERE id = :id")
    suspend fun getLobe(id: Long): Lobe?

    @Query("UPDATE ideas SET done = :done WHERE id = :id")
    suspend fun setIdeaDone(id: Long, done: Boolean)

    /**
     * The Daily reset: unticks only rows still ticked from before [before] (their last change), so a tick made today stays. Stamped
     * [before] itself, not now: newer than yesterday's tick but older than any tick made today on another device, so a late reset never wins.
     */
    @Query("UPDATE ideas SET done = 0, syncAt = :before WHERE done = 1 AND syncAt < :before AND lobeId IN (:lobeIds)")
    suspend fun clearDoneBefore(lobeIds: List<Long>, before: Long): Int

    @Insert
    suspend fun insertBranch(branch: Branch): Long

    @Query("UPDATE branches SET name = :name WHERE id = :id")
    suspend fun renameBranch(id: Long, name: String)

    @Query("SELECT EXISTS(SELECT 1 FROM branches WHERE id = :id)")
    suspend fun branchExists(id: Long): Boolean

    /** Its ideas stay, directly on the lobe. */
    @Query("DELETE FROM branches WHERE id = :id")
    suspend fun deleteBranch(id: Long)

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

    @Query("SELECT * FROM plans WHERE id = :id")
    suspend fun getPlan(id: Long): Plan?

    @Insert
    suspend fun insertPlan(plan: Plan): Long

    /** A conflict copy; merge may already have built the same uid. */
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertPlanCopy(plan: Plan)

    // Single columns: a whole-row write from a held copy would put back whatever sync changed since.
    @Query("UPDATE plans SET done = :done WHERE id = :id")
    suspend fun setPlanDone(id: Long, done: Boolean)

    @Query("UPDATE plans SET text = :text WHERE id = :id")
    suspend fun setPlanText(id: Long, text: String)

    @Query("DELETE FROM plans WHERE id = :id")
    suspend fun deletePlan(id: Long)

    @Query("SELECT * FROM day_focus")
    fun observeFocus(): Flow<List<DayFocus>>

    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertFocus(focus: DayFocus)

    @Query("DELETE FROM day_focus WHERE day = :day AND lobeId = :lobeId")
    suspend fun deleteFocus(day: Long, lobeId: Long)
}

/** A learning goal: a moon round the learn brain. [updatedAt] is its own last edit; its steps carry theirs, see [app.vesper.ui.goalTouchedAt]. */
@Entity(tableName = "goals", indices = [Index(value = ["uid"], unique = true)])
data class Goal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Order round the learn brain, lowest first. */
    val position: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val uid: String = newUid(),
    @ColumnInfo(defaultValue = "0") val syncAt: Long = 0,
)

/** One step on a goal's path. [day] is `LocalDate.toEpochDay()`, null = no day. Deleting the goal deletes its steps. */
@Entity(
    tableName = "steps",
    foreignKeys = [ForeignKey(Goal::class, ["id"], ["goalId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("goalId"), Index("day"), Index(value = ["uid"], unique = true)],
)
data class Step(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long,
    val title: String,
    val note: String = "",
    val done: Boolean = false,
    val day: Long? = null,
    /** Order on the path, lowest first. */
    val position: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** Made, edited or done: what keeps its goal bright. */
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val uid: String = newUid(),
    @ColumnInfo(defaultValue = "0") val syncAt: Long = 0,
)

@Dao
interface LearnDao {
    @Query("SELECT * FROM goals ORDER BY position ASC, id ASC")
    fun observeGoals(): Flow<List<Goal>>

    @Query("SELECT * FROM steps ORDER BY goalId ASC, position ASC, id ASC")
    fun observeSteps(): Flow<List<Step>>

    @Query("SELECT IFNULL(MAX(position), -1) + 1 FROM goals")
    suspend fun nextGoalPosition(): Int

    @Query("SELECT IFNULL(MAX(position), -1) + 1 FROM steps WHERE goalId = :goalId")
    suspend fun nextStepPosition(goalId: Long): Int

    @Insert suspend fun insertGoal(goal: Goal): Long
    @Insert suspend fun insertStep(step: Step): Long

    // Named columns only: a whole-row write from a held copy would put back whatever sync changed since.
    @Query("UPDATE goals SET name = :name, updatedAt = :at WHERE id = :id")
    suspend fun renameGoal(id: Long, name: String, at: Long)

    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun deleteGoal(id: Long)

    @Query("UPDATE steps SET done = :done, updatedAt = :at WHERE id = :id")
    suspend fun setStepDone(id: Long, done: Boolean, at: Long)

    @Query("UPDATE steps SET day = :day, updatedAt = :at WHERE id = :id")
    suspend fun setStepDay(id: Long, day: Long?, at: Long)

    @Query("UPDATE steps SET title = :title, note = :note, day = :day, updatedAt = :at WHERE id = :id")
    suspend fun setStepFields(id: Long, title: String, note: String, day: Long?, at: Long)

    @Query("DELETE FROM steps WHERE id = :id")
    suspend fun deleteStep(id: Long)
}

/** A card on the Bible canvas: a person (a plain box) or a thing or event ([BIBLE_THING], a rounded dashed box). [x]/[y] is its middle, canvas dp. */
@Entity(tableName = "bible_cards", indices = [Index(value = ["uid"], unique = true)])
data class BibleCard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val note: String = "",
    val kind: String = BIBLE_PERSON,
    val x: Float,
    val y: Float,
    val locked: Boolean = false,
    @ColumnInfo(defaultValue = "''") val uid: String = newUid(),
    @ColumnInfo(defaultValue = "0") val syncAt: Long = 0,
)

const val BIBLE_PERSON = "person"
const val BIBLE_THING = "thing"

/** A labelled line from one Bible card to another, by their uids (no FK: deleting a card deletes its links, see [BibleDao.deleteLinksOf]). */
@Entity(tableName = "bible_links", indices = [Index(value = ["uid"], unique = true)])
data class BibleLink(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromUid: String,
    val toUid: String,
    val label: String,
    @ColumnInfo(defaultValue = "''") val uid: String = newUid(),
    @ColumnInfo(defaultValue = "0") val syncAt: Long = 0,
)

@Dao
interface BibleDao {
    @Query("SELECT * FROM bible_cards ORDER BY id ASC")
    fun observeCards(): Flow<List<BibleCard>>

    @Query("SELECT * FROM bible_links ORDER BY id ASC")
    fun observeLinks(): Flow<List<BibleLink>>

    @Insert suspend fun insertCard(card: BibleCard): Long
    @Insert suspend fun insertLink(link: BibleLink): Long

    // Named columns only: a whole-row write from a held copy would put back whatever sync changed since.
    @Query("UPDATE bible_cards SET name = :name WHERE id = :id")
    suspend fun renameCard(id: Long, name: String)

    @Query("UPDATE bible_cards SET note = :note WHERE id = :id")
    suspend fun setNote(id: Long, note: String)

    @Query("UPDATE bible_cards SET kind = :kind WHERE id = :id")
    suspend fun setKind(id: Long, kind: String)

    /** A locked card stays put, whatever asks. */
    @Query("UPDATE bible_cards SET x = :x, y = :y WHERE id = :id AND locked = 0")
    suspend fun moveCard(id: Long, x: Float, y: Float)

    @Query("UPDATE bible_cards SET locked = :locked WHERE id = :id")
    suspend fun setLocked(id: Long, locked: Boolean)

    @Query("UPDATE bible_links SET label = :label WHERE id = :id")
    suspend fun renameLink(id: Long, label: String)

    @Query("DELETE FROM bible_links WHERE id = :id")
    suspend fun deleteLink(id: Long)

    @Query("DELETE FROM bible_links WHERE fromUid = :uid OR toUid = :uid")
    suspend fun deleteLinksOf(uid: String)

    @Query("DELETE FROM bible_cards WHERE id = :id")
    suspend fun deleteCardRow(id: Long)
}

/**
 * A phone alarm. Local only, never synced: each device rings its own. [repeatDays] is a bitmask, bit 0 = Monday .. bit 6 =
 * Sunday; 0 = one time, on [oneTimeDate] (epoch day). A one-time alarm that has rung keeps its row with [firedAt] set (history).
 */
@Entity(tableName = "alarms")
data class Alarm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val repeatDays: Int = 0,
    val enabled: Boolean = true,
    val oneTimeDate: Long? = null,
    val firedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms WHERE firedAt IS NULL ORDER BY hour ASC, minute ASC, id ASC")
    fun observeActive(): Flow<List<Alarm>>

    @Query("SELECT * FROM alarms WHERE firedAt IS NOT NULL ORDER BY firedAt DESC, id DESC")
    fun observeHistory(): Flow<List<Alarm>>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun get(id: Long): Alarm?

    @Insert suspend fun insert(alarm: Alarm): Long
    @Update suspend fun update(alarm: Alarm)

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun delete(id: Long)
}

/** Raw row access for the sync code only (app.vesper.sync); everything else goes through the DAOs above. */
@Dao
interface SyncDao {
    @Query("SELECT * FROM notes") suspend fun notes(): List<Note>
    @Query("SELECT * FROM tasks") suspend fun tasks(): List<Task>
    @Query("SELECT * FROM lobes") suspend fun lobes(): List<Lobe>
    @Query("SELECT * FROM branches") suspend fun branches(): List<Branch>
    @Query("SELECT * FROM ideas") suspend fun ideas(): List<Idea>
    @Query("SELECT * FROM plans") suspend fun plans(): List<Plan>
    @Query("SELECT * FROM goals") suspend fun goals(): List<Goal>
    @Query("SELECT * FROM steps") suspend fun steps(): List<Step>
    @Query("SELECT * FROM bible_cards") suspend fun bibleCards(): List<BibleCard>
    @Query("SELECT * FROM bible_links") suspend fun bibleLinks(): List<BibleLink>
    @Query("SELECT * FROM sync_tombstones") suspend fun tombstones(): List<SyncTombstone>

    /** Rises on every change to a synced table, deletes included: the triggers keep each term moving. */
    @Query(
        "SELECT IFNULL((SELECT MAX(syncAt) FROM notes), 0) + IFNULL((SELECT MAX(syncAt) FROM tasks), 0) + IFNULL((SELECT MAX(syncAt) FROM lobes), 0) + " +
            "IFNULL((SELECT MAX(syncAt) FROM branches), 0) + IFNULL((SELECT MAX(syncAt) FROM ideas), 0) + IFNULL((SELECT MAX(syncAt) FROM plans), 0) + " +
            "IFNULL((SELECT MAX(syncAt) FROM goals), 0) + IFNULL((SELECT MAX(syncAt) FROM steps), 0) + " +
            "IFNULL((SELECT MAX(syncAt) FROM bible_cards), 0) + IFNULL((SELECT MAX(syncAt) FROM bible_links), 0) + " +
            "IFNULL((SELECT MAX(deletedAt) FROM sync_tombstones), 0)",
    )
    fun observeChanges(): Flow<Long>

    @Insert suspend fun insert(row: Note): Long
    @Insert suspend fun insert(row: Task): Long
    @Insert suspend fun insert(row: Lobe): Long
    @Insert suspend fun insert(row: Branch): Long
    @Insert suspend fun insert(row: Idea): Long
    @Insert suspend fun insert(row: Plan): Long
    @Insert suspend fun insert(row: Goal): Long
    @Insert suspend fun insert(row: Step): Long
    @Insert suspend fun insert(row: BibleCard): Long
    @Insert suspend fun insert(row: BibleLink): Long
    @Insert suspend fun insert(rows: List<SyncTombstone>)

    @Update suspend fun update(row: Note)
    @Update suspend fun update(row: Task)
    @Update suspend fun update(row: Lobe)
    @Update suspend fun update(row: Branch)
    @Update suspend fun update(row: Idea)
    @Update suspend fun update(row: Plan)
    @Update suspend fun update(row: Goal)
    @Update suspend fun update(row: Step)
    @Update suspend fun update(row: BibleCard)
    @Update suspend fun update(row: BibleLink)

    @Query("DELETE FROM notes WHERE uid = :uid") suspend fun deleteNote(uid: String)
    @Query("DELETE FROM tasks WHERE uid = :uid") suspend fun deleteTask(uid: String)
    @Query("DELETE FROM lobes WHERE uid = :uid") suspend fun deleteLobe(uid: String)
    @Query("DELETE FROM branches WHERE uid = :uid") suspend fun deleteBranch(uid: String)
    @Query("DELETE FROM ideas WHERE uid = :uid") suspend fun deleteIdea(uid: String)
    @Query("DELETE FROM plans WHERE uid = :uid") suspend fun deletePlan(uid: String)
    @Query("DELETE FROM goals WHERE uid = :uid") suspend fun deleteGoal(uid: String)
    @Query("DELETE FROM steps WHERE uid = :uid") suspend fun deleteStep(uid: String)
    @Query("DELETE FROM bible_cards WHERE uid = :uid") suspend fun deleteBibleCard(uid: String)
    @Query("DELETE FROM bible_links WHERE uid = :uid") suspend fun deleteBibleLink(uid: String)
    @Query("DELETE FROM sync_tombstones") suspend fun clearTombstones()
}

/** A deleted synced row, written by the delete triggers (FK cascades included) so peers can delete it too. Kept forever. */
@Entity(tableName = "sync_tombstones", primaryKeys = ["tbl", "uid"])
data class SyncTombstone(val tbl: String, val uid: String, val deletedAt: Long)

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
fun newUid(): String = kotlin.uuid.Uuid.random().toHexString()

/** The tables that carry uid + syncAt. day_focus, posts and alarms are not synced. */
val SYNCED_TABLES = listOf("notes", "tasks", "lobes", "branches", "ideas", "plans", "goals", "steps", "bible_cards", "bible_links")

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
const val DB_VERSION = 12

/**
 * v9 -> v10, as plain SQL so Android and desktop run the very same statements. Groups come in with ALTER, not a table
 * rebuild: no delete or update trigger fires, no syncAt moves, and every row keeps its lobe and noteId. No default group is
 * made here: two devices migrating apart would each mint their own and sync would double it.
 */
val MIGRATE_9_10_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `branches` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `lobeId` INTEGER NOT NULL, `name` TEXT NOT NULL, `uid` TEXT NOT NULL DEFAULT '', `syncAt` INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(`lobeId`) REFERENCES `lobes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_branches_lobeId` ON `branches` (`lobeId`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_branches_uid` ON `branches` (`uid`)",
    "ALTER TABLE `ideas` ADD COLUMN `branchId` INTEGER REFERENCES `branches`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL",
    "ALTER TABLE `ideas` ADD COLUMN `link` TEXT",
    "ALTER TABLE `ideas` ADD COLUMN `done` INTEGER NOT NULL DEFAULT 0",
    "CREATE INDEX IF NOT EXISTS `index_ideas_branchId` ON `ideas` (`branchId`)",
) + syncTriggerSql("branches")

/** v10 -> v11: the learn brain's goals and steps (synced) and alarms (local only). New tables only: no existing row is touched. */
val MIGRATE_10_11_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `goals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `position` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `uid` TEXT NOT NULL DEFAULT '', `syncAt` INTEGER NOT NULL DEFAULT 0)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_goals_uid` ON `goals` (`uid`)",
    "CREATE TABLE IF NOT EXISTS `steps` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `goalId` INTEGER NOT NULL, `title` TEXT NOT NULL, `note` TEXT NOT NULL, `done` INTEGER NOT NULL, `day` INTEGER, `position` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `uid` TEXT NOT NULL DEFAULT '', `syncAt` INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(`goalId`) REFERENCES `goals`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_steps_goalId` ON `steps` (`goalId`)",
    "CREATE INDEX IF NOT EXISTS `index_steps_day` ON `steps` (`day`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_steps_uid` ON `steps` (`uid`)",
    "CREATE TABLE IF NOT EXISTS `alarms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `hour` INTEGER NOT NULL, `minute` INTEGER NOT NULL, `label` TEXT NOT NULL, `repeatDays` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `oneTimeDate` INTEGER, `firedAt` INTEGER, `createdAt` INTEGER NOT NULL)",
) + syncTriggerSql("goals") + syncTriggerSql("steps")

/** v11 -> v12: the Bible canvas's cards and links (synced). New tables only: no existing row is touched. */
val MIGRATE_11_12_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `bible_cards` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `note` TEXT NOT NULL, `kind` TEXT NOT NULL, `x` REAL NOT NULL, `y` REAL NOT NULL, `locked` INTEGER NOT NULL, `uid` TEXT NOT NULL DEFAULT '', `syncAt` INTEGER NOT NULL DEFAULT 0)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_bible_cards_uid` ON `bible_cards` (`uid`)",
    "CREATE TABLE IF NOT EXISTS `bible_links` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fromUid` TEXT NOT NULL, `toUid` TEXT NOT NULL, `label` TEXT NOT NULL, `uid` TEXT NOT NULL DEFAULT '', `syncAt` INTEGER NOT NULL DEFAULT 0)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_bible_links_uid` ON `bible_links` (`uid`)",
) + syncTriggerSql("bible_cards") + syncTriggerSql("bible_links")

/** The desktop's first release was v9, so this is its whole list. Its driver has no SupportSQLiteDatabase, hence the connection. */
val DESKTOP_MIGRATIONS = arrayOf<Migration>(
    object : Migration(9, 10) {
        override fun migrate(connection: SQLiteConnection) = MIGRATE_9_10_SQL.forEach { connection.execSQL(it) }
    },
    object : Migration(10, 11) {
        override fun migrate(connection: SQLiteConnection) = MIGRATE_10_11_SQL.forEach { connection.execSQL(it) }
    },
    object : Migration(11, 12) {
        override fun migrate(connection: SQLiteConnection) = MIGRATE_11_12_SQL.forEach { connection.execSQL(it) }
    },
)

@Database(entities = [Note::class, Task::class, Lobe::class, Branch::class, Idea::class, Post::class, Plan::class, DayFocus::class, SyncTombstone::class, Goal::class, Step::class, Alarm::class, BibleCard::class, BibleLink::class], version = DB_VERSION, exportSchema = true)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun taskDao(): TaskDao
    abstract fun brainDao(): BrainDao
    abstract fun postDao(): PostDao
    abstract fun moonsDao(): MoonsDao
    abstract fun syncDao(): SyncDao
    abstract fun learnDao(): LearnDao
    abstract fun alarmDao(): AlarmDao
    abstract fun bibleDao(): BibleDao
}

/** Room KMP: KSP generates the actual for each target. Android still builds through Room.databaseBuilder(context, AppDatabase::class.java, name). */
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

/** Room builds a fresh install from the entities, which carry no triggers; IF NOT EXISTS makes this a no-op afterwards. Driver API, so it runs on Android (compatibility mode) and desktop. */
val SYNC_TRIGGERS_CALLBACK = object : RoomDatabase.Callback() {
    override fun onOpen(connection: SQLiteConnection) {
        SYNCED_TABLES.forEach { t -> syncTriggerSql(t).forEach { connection.execSQL(it) } }
    }
}
