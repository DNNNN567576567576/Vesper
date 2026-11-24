package app.vesper.ui

import app.vesper.DayKind
import app.vesper.dayRecord
import app.vesper.data.Idea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 2.8.0 phase B: the day-tap Daily record, the tab list (Alarms only where alarms ring) and the calculator's display grouping. */
class TabsAndDayTest {
    private val today = LocalDate.of(2026, 11, 20)
    private fun item(n: Int) = Idea(id = n.toLong(), lobeId = 1, title = "item$n", body = "", uid = "u$n")
    private val items = listOf(item(1), item(2), item(3))

    /** 2.8.1: the world header's count is sized on whole pixels, and a held alarm arrow speeds up to a floor. */
    @Test fun crispCountAndHeldArrow() {
        assertEquals(24f, crispPx(2.75f)); assertEquals(16f, crispPx(2.2f)); assertEquals(8f, crispPx(0.4f))
        assertEquals(120L, repeatDelay(0)); assertEquals(35L, repeatDelay(15)); assertEquals(35L, repeatDelay(500))
        assertTrue((0 until 33).sumOf { repeatDelay(it) } + 400 < 2500)
    }

    @Test fun doneAndUndoneForAPastDay() {
        val r = dayRecord(items, listOf("u1", "u3"), today.minusDays(2), today)
        assertEquals(DayKind.Record, r.kind)
        assertEquals(listOf("u1", "u3"), r.done.map { it.uid })
        assertEquals(listOf("u2"), r.undone.map { it.uid })
        assertFalse(r.allDone)
    }

    @Test fun allDoneMarksTheDay() {
        val r = dayRecord(items, listOf("u3", "u2", "u1"), today.minusDays(1), today)
        assertTrue(r.allDone)
        assertTrue(r.undone.isEmpty())
    }

    @Test fun aPastDayNobodyTickedShowsEveryItemUnlit() {
        val r = dayRecord(items, emptyList(), today.minusDays(5), today)
        assertEquals(DayKind.Record, r.kind)
        assertEquals(3, r.undone.size)
        assertTrue(r.done.isEmpty())
        assertEquals(DayKind.Empty, dayRecord(emptyList(), emptyList(), today.minusDays(5), today).kind)
        assertEquals(DayKind.Empty, dayRecord(emptyList(), emptyList(), today, today).kind)
    }

    @Test fun todayWithNothingTickedShowsEveryItemUnlit() {
        val r = dayRecord(items, emptyList(), today, today)
        assertEquals(DayKind.Record, r.kind)
        assertEquals(3, r.undone.size)
    }

    @Test fun futureDaysHaveNoRecordEvenIfRowsExist() {
        assertEquals(DayKind.Future, dayRecord(items, listOf("u1"), today.plusDays(1), today).kind)
    }

    @Test fun deletedItemsStillCountAsDoneButHaveNoName() {
        val r = dayRecord(items, listOf("u1", "gone"), today.minusDays(1), today)
        assertEquals(1, r.gone)
        assertEquals(listOf("u1"), r.done.map { it.uid })
        assertEquals(DayKind.Record, dayRecord(emptyList(), listOf("gone"), today.minusDays(1), today).kind)
    }

    @Test fun alarmsTabOnlyWhereAlarmsRing() {
        assertEquals(listOf(AppTab.Brain, AppTab.Grimoire, AppTab.Moons, AppTab.Calc), navTabs(alarmsSupported = false))
        assertEquals(listOf(AppTab.Brain, AppTab.Grimoire, AppTab.Moons, AppTab.Alarms, AppTab.Calc), navTabs(alarmsSupported = true))
    }

    @Test fun calcDisplayGroupsThousandsButNotDecimals() {
        assertEquals("1,280 + 4.5 =", groupDigits("1280 + 4.5 ="))
        assertEquals("1,284.5", groupDigits("1284.5"))
        assertEquals("0.123456", groupDigits("0.123456"))
        assertEquals("−1,234,567", groupDigits("−1234567"))
        assertEquals("1.5e100", groupDigits("1.5e100"))
        assertEquals("999", groupDigits("999"))
    }

    @Test fun calcStillAddsThousandsAndDecimals() {
        val s = "1280+4.5=".fold(CalcState()) { st, k -> CalcEngine.press(st, k) }
        assertEquals("1284.5", CalcEngine.shown(s))
    }
}
