package app.vesper.ui

import app.vesper.AlarmLogic
import app.vesper.data.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class AlarmLogicTest {
    private val utc = ZoneId.of("UTC")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, z: ZoneId = utc) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(z).toInstant().toEpochMilli()
    private fun day(y: Int, mo: Int, d: Int) = LocalDate.of(y, mo, d).toEpochDay()

    // 2026-11-17 is a Tuesday.
    private val now = ms(2026, 11, 17, 10, 0)

    @Test fun defaultDateIsTodayWhenStillAhead() = assertEquals(day(2026, 11, 17), AlarmLogic.defaultDate(10, 1, now, utc))

    @Test fun defaultDateIsTomorrowWhenPassedOrNow() {
        assertEquals(day(2026, 11, 18), AlarmLogic.defaultDate(10, 0, now, utc))
        assertEquals(day(2026, 11, 18), AlarmLogic.defaultDate(9, 59, now, utc))
    }

    @Test fun oneTimeUsesItsDate() =
        assertEquals(ms(2026, 11, 20, 7, 30), AlarmLogic.nextTrigger(Alarm(hour = 7, minute = 30, oneTimeDate = day(2026, 11, 20)), now, utc))

    @Test fun oneTimeInThePastIsReturnedForTheCallerToJudge() =
        assertEquals(ms(2026, 11, 17, 7, 0), AlarmLogic.nextTrigger(Alarm(hour = 7, minute = 0, oneTimeDate = day(2026, 11, 17)), now, utc))

    @Test fun offOrRungAlarmsNeverRing() {
        assertNull(AlarmLogic.nextTrigger(Alarm(hour = 7, minute = 0, repeatDays = 1, enabled = false), now, utc))
        assertNull(AlarmLogic.nextTrigger(Alarm(hour = 7, minute = 0, oneTimeDate = day(2026, 11, 20), firedAt = 5), now, utc))
    }

    @Test fun repeatingTodayIfStillAhead() =
        assertEquals(ms(2026, 11, 17, 18, 0), AlarmLogic.nextTrigger(Alarm(hour = 18, minute = 0, repeatDays = AlarmLogic.WEEK), now, utc))

    @Test fun repeatingTodayAlreadyPassedGoesToNextChosenDay() {
        // Tue 07:00 passed; Mon and Tue chosen -> next Monday.
        assertEquals(ms(2026, 11, 23, 7, 0), AlarmLogic.nextTrigger(Alarm(hour = 7, minute = 0, repeatDays = 0b0000011), now, utc))
    }

    @Test fun repeatingExactlyNowMeansNextWeek() =
        assertEquals(ms(2026, 11, 24, 10, 0), AlarmLogic.nextTrigger(Alarm(hour = 10, minute = 0, repeatDays = 0b0000010), now, utc))

    @Test fun repeatingWrapsOverSunday() {
        val sat = ms(2026, 11, 21, 23, 0) // Saturday night; only Monday chosen
        assertEquals(ms(2026, 11, 23, 6, 15), AlarmLogic.nextTrigger(Alarm(hour = 6, minute = 15, repeatDays = 0b0000001), sat, utc))
    }

    @Test fun dstSpringForwardKeepsWallClock() {
        val berlin = ZoneId.of("Europe/Berlin") // clocks jump 02:00 -> 03:00 on 2026-03-29
        val before = ms(2026, 3, 28, 12, 0, berlin)
        assertEquals(ms(2026, 3, 29, 7, 0, berlin), AlarmLogic.nextTrigger(Alarm(hour = 7, minute = 0, repeatDays = AlarmLogic.WEEK), before, berlin))
        // 02:30 does not exist that day: it rings at 03:30.
        assertEquals(ms(2026, 3, 29, 3, 30, berlin), AlarmLogic.nextTrigger(Alarm(hour = 2, minute = 30, repeatDays = AlarmLogic.WEEK), before, berlin))
    }

    @Test fun labels() {
        assertEquals("once", AlarmLogic.daysLabel(0))
        assertEquals("every day", AlarmLogic.daysLabel(0b1111111))
        assertEquals("weekdays", AlarmLogic.daysLabel(0b0011111))
        assertEquals("weekends", AlarmLogic.daysLabel(0b1100000))
        assertEquals("Mon Wed Fri", AlarmLogic.daysLabel(0b0010101))
        assertEquals("Sun", AlarmLogic.daysLabel(0b1000000))
        assertEquals("07:05", AlarmLogic.timeLabel(7, 5))
    }
}
