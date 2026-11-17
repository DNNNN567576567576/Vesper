package app.vesper

import app.vesper.data.Alarm
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Why alarms might not ring right now; the Moons alarms section shows a one-line hint for it. */
enum class AlarmProblem { Exact, Notifications, FullScreen }

/** Rings alarms. Only Android does; elsewhere [supported] is false and the alarms section stays hidden. */
interface AlarmScheduler {
    val supported: Boolean get() = false

    /** Arms [alarm] for its next time (replacing any earlier arming of it). Disabled or already rung alarms are cancelled instead. */
    fun schedule(alarm: Alarm) {}
    fun cancel(id: Long) {}

    /** Null when all is well. Cheap; the UI re-reads it when the app comes back to the front. */
    fun problem(): AlarmProblem? = null

    /** Asks for the notification permission where the system still allows asking (Android 13+). */
    fun requestPermissions() {}
    fun openSettings(problem: AlarmProblem) {}
}

object NoAlarms : AlarmScheduler

/** Pure alarm maths. Times are epoch millis, days are `LocalDate.toEpochDay()`; day masks have bit 0 = Monday. */
object AlarmLogic {
    const val WEEK = 0b1111111

    private fun at(day: LocalDate, hour: Int, minute: Int, zone: ZoneId): Long =
        // ZonedDateTime.of moves a time that falls in a DST gap forward, so the alarm still rings that day.
        ZonedDateTime.of(day, LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()

    /** The day a new one-time alarm should land on: today if hour:minute is still ahead, else tomorrow. */
    fun defaultDate(hour: Int, minute: Int, now: Long, zone: ZoneId): Long {
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (if (at(today, hour, minute, zone) > now) today else today.plusDays(1)).toEpochDay()
    }

    /**
     * When [a] rings next, strictly after [now]; null if it never will (off, already rung, or a repeating alarm with no days).
     * A one-time alarm whose date has passed still returns that past moment, so the caller decides what a missed alarm does.
     */
    fun nextTrigger(a: Alarm, now: Long, zone: ZoneId): Long? {
        if (!a.enabled || a.firedAt != null) return null
        val mask = a.repeatDays and WEEK
        if (mask == 0) {
            val day = a.oneTimeDate?.let { LocalDate.ofEpochDay(it) } ?: LocalDate.ofEpochDay(defaultDate(a.hour, a.minute, now, zone))
            return at(day, a.hour, a.minute, zone)
        }
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        for (i in 0..7) {
            val day = today.plusDays(i.toLong())
            if (mask and (1 shl (day.dayOfWeek.value - 1)) == 0) continue
            val t = at(day, a.hour, a.minute, zone)
            if (t > now) return t
        }
        return null
    }

    private val NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun daysLabel(mask: Int): String = when (val m = mask and WEEK) {
        0 -> "once"
        WEEK -> "every day"
        0b0011111 -> "weekdays"
        0b1100000 -> "weekends"
        else -> NAMES.filterIndexed { i, _ -> m and (1 shl i) != 0 }.joinToString(" ")
    }

    fun timeLabel(hour: Int, minute: Int) = hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')
}
