package app.vesper

import app.vesper.data.AppDatabase
import app.vesper.data.Idea
import app.vesper.data.Lobe
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

// The Daily brain: a small checklist whose ticks only count for the day they were made. No history, no streaks, no stats.
// Its items are the ideas of the sub brain named "Daily" (the same trick as "Tasks" and "Pinned"), done = the idea's done flag.

const val DAILY_LOBE = "Daily"
/** Settings: the local date (ISO) the ticks were last cleared for. */
const val KEY_DAILY_RESET = "dailyResetDate"
/** Settings: the evening reminder's hour, or "off". */
const val KEY_DAILY_REMIND = "dailyRemindAt"
const val DAILY_REMIND_DEFAULT = 20
/** The "remind at" chip cycles through these; null = off. */
val DAILY_REMIND_HOURS: List<Int?> = listOf(18, 19, 20, 21, 22, null)

fun isDailyLobe(l: Lobe) = l.name.trim().equals(DAILY_LOBE, ignoreCase = true)

/** The Daily items, in the order they were made. */
fun dailyItems(lobes: List<Lobe>, ideas: List<Idea>): List<Idea> {
    val ids = lobes.filter(::isDailyLobe).mapTo(HashSet()) { it.id }
    return ideas.filter { it.lobeId in ids }.sortedWith(compareBy({ it.id }, { it.uid }))
}

/** Local midnight that starts [day]. */
fun dayStart(day: LocalDate, zone: ZoneId): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()

/** A tick counts only on the day it was made: its row's last change (its sync stamp, carried as-is by sync) is today. */
fun doneToday(i: Idea, todayStart: Long) = i.done && i.syncAt >= todayStart

/** What the evening reminder says for the undone items' names, or null when nothing is undone: then no notification at all. */
fun dailyReminderText(undone: List<String>): String? =
    if (undone.isEmpty()) null else "daily: ${undone.size} left · ${undone.joinToString(", ")}"

/** The reminder for [items] at [now]: null when every one is done today (or there are none). */
fun dailyReminder(items: List<Idea>, now: Long, zone: ZoneId): String? {
    val start = dayStart(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), zone)
    return dailyReminderText(items.filterNot { doneToday(it, start) }.map { it.title.ifBlank { it.body.lineSequence().firstOrNull().orEmpty() }.trim() })
}

fun dailyRemindHour(s: Settings): Int? = when (val v = s.getString(KEY_DAILY_REMIND)) {
    null -> DAILY_REMIND_DEFAULT
    "off" -> null
    else -> v.toIntOrNull()?.takeIf { it in 0..23 } ?: DAILY_REMIND_DEFAULT
}
fun setDailyRemindHour(s: Settings, hour: Int?) = s.putString(KEY_DAILY_REMIND, hour?.toString() ?: "off")
/** The chip's next value after [hour]. */
fun nextRemindHour(hour: Int?): Int? = DAILY_REMIND_HOURS[(DAILY_REMIND_HOURS.indexOf(hour) + 1) % DAILY_REMIND_HOURS.size]

/** The next [hour]:00 strictly after [now]. */
fun dailyRemindAt(hour: Int, now: Long, zone: ZoneId): Long {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val at = ZonedDateTime.of(today, LocalTime.of(hour, 0), zone).toInstant().toEpochMilli()
    return if (at > now) at else ZonedDateTime.of(today.plusDays(1), LocalTime.of(hour, 0), zone).toInstant().toEpochMilli()
}

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
