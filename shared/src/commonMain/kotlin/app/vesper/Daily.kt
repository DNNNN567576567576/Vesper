package app.vesper

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import app.vesper.data.AppDatabase
import app.vesper.data.DailyLog
import app.vesper.data.Idea
import app.vesper.data.Lobe
import app.vesper.data.dailyLogUid
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

// The Daily brain: a small checklist whose ticks only count for the day they were made, lit like candles. Its items are the ideas of
// the sub brain named "Daily" (the same trick as "Tasks" and "Pinned"), done = the idea's done flag. Each day's final state per item
// is kept in the synced daily_log (2.8.0), the only history Vesper keeps; the midnight reset never touches it.

const val DAILY_LOBE = "Daily"
/** Settings: the local date (ISO) the ticks were last cleared for. */
const val KEY_DAILY_RESET = "dailyResetDate"

fun isDailyLobe(l: Lobe) = l.name.trim().equals(DAILY_LOBE, ignoreCase = true)

/** The Daily items, in the order they were made. */
fun dailyItems(lobes: List<Lobe>, ideas: List<Idea>): List<Idea> {
    val ids = lobes.filter(::isDailyLobe).mapTo(HashSet()) { it.id }
    return ideas.filter { it.lobeId in ids }.sortedWith(compareBy({ it.id }, { it.uid }))
}

/** Local midnight that starts [day]. */
fun dayStart(day: LocalDate, zone: ZoneId): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()

/**
 * The midnight reset: unticks every Daily item ticked before [todayStart] and remembers [today]. Only rows still ticked from an
 * earlier day are written (a tick made today, here or on another device and synced in, stays), so two devices resetting the same
 * rows write the same undone row, which never makes a conflict copy (only text differences do). Idempotent: once nothing is stale it
 * writes nothing, so it is cheap to run on every start, resume, date change and sync. Returns how many rows it unticked.
 */
suspend fun resetDaily(db: AppDatabase, settings: Settings, today: LocalDate, todayStart: Long): Int {
    val dao = db.brainDao()
    val ids = dao.observeLobes().first().filter(::isDailyLobe).map { it.id }
    val n = if (ids.isEmpty()) 0 else dao.clearDoneBefore(ids, todayStart)
    if (settings.getString(KEY_DAILY_RESET) != today.toString()) settings.putString(KEY_DAILY_RESET, today.toString())
    return n
}

/** Ticks or unticks [item] and writes [today]'s log row to match, in one transaction: the log holds each day's final state. */
suspend fun tickDaily(db: AppDatabase, item: Idea, done: Boolean, today: LocalDate) = db.useWriterConnection { c ->
    c.immediateTransaction {
        val dao = db.brainDao()
        dao.setIdeaDone(item.id, done)
        val uid = dailyLogUid(item.uid, today.toString())
        dao.insertLogIfMissing(uid, item.uid, today.toString(), done)
        dao.setLogDone(uid, done)
    }
}

/**
 * Sets [item] done or not for [day] from the Moons tab. Today goes through [tickDaily] (flag and log together); a past day only
 * writes that day's log row (same uid, so sync keeps one row) and leaves the item's flag alone. A future day is refused (false).
 */
suspend fun setDailyOn(db: AppDatabase, item: Idea, done: Boolean, day: LocalDate, today: LocalDate): Boolean {
    if (day.isAfter(today)) return false
    if (day == today) { tickDaily(db, item, done, today); return true }
    db.useWriterConnection { c ->
        c.immediateTransaction {
            val dao = db.brainDao()
            val uid = dailyLogUid(item.uid, day.toString())
            dao.insertLogIfMissing(uid, item.uid, day.toString(), done)
            dao.setLogDone(uid, done)
        }
    }
    return true
}

/** Which daily items (their uids) were done on [day], deleted ones included: the day's record for the Moons tab. */
suspend fun dailyDoneOn(db: AppDatabase, day: LocalDate): List<String> = db.brainDao().doneOn(day.toString())

/** The stats window: [STATS_DAYS] squares per item, counts over the last [STATS_COUNT_DAYS]. */
const val STATS_DAYS = 28
const val STATS_COUNT_DAYS = 14

/** One current item's record: [days] oldest first, today last; [doneRecent] of the last [STATS_COUNT_DAYS] were done. */
data class DailyStat(val item: Idea, val days: List<Boolean>, val doneRecent: Int)

/** The stats of the current [items] from [log] up to [today]; a deleted item's rows are skipped, a day with no row was not done. */
fun dailyStats(items: List<Idea>, log: List<DailyLog>, today: LocalDate): List<DailyStat> {
    val done = log.filter { it.done }.mapTo(HashSet()) { it.itemUid to it.date }
    val dates = (STATS_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()).toString() }
    return items.map { i ->
        val days = dates.map { (i.uid to it) in done }
        DailyStat(i, days, days.takeLast(STATS_COUNT_DAYS).count { it })
    }
}

/** What a day's record holds: nothing to show ([Empty]), a day still to come ([Future]), or the items' state ([Record]). */
enum class DayKind { Future, Empty, Record }

/** One day's Daily record: current [items] done that day, not done, and [gone] = done items deleted since (named by nobody). */
data class DayRecord(val kind: DayKind, val done: List<Idea> = emptyList(), val undone: List<Idea> = emptyList(), val gone: Int = 0) {
    val allDone get() = kind == DayKind.Record && done.isNotEmpty() && undone.isEmpty()
}

/**
 * The Moons tab's record of [day] from the uids done that day ([dailyDoneOn]). A future day has none yet. Any other day shows the
 * current items lit or unlit (a past day nobody ticked shows them all unlit, so they can be lit after the fact); with no items and
 * nothing done there is nothing to show.
 */
fun dayRecord(items: List<Idea>, doneUids: Collection<String>, day: LocalDate, today: LocalDate): DayRecord {
    if (day.isAfter(today)) return DayRecord(DayKind.Future)
    val set = doneUids.toSet()
    val done = items.filter { it.uid in set }
    val gone = set.size - done.size
    val undone = items.filter { it.uid !in set }
    return if (done.isEmpty() && gone == 0 && items.isEmpty())DayRecord(DayKind.Empty)
    else DayRecord(DayKind.Record, done, undone, gone)
}
