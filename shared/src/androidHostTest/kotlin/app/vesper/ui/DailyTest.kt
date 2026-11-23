package app.vesper.ui

import androidx.compose.ui.geometry.Offset
import app.vesper.DAILY_REMIND_HOURS
import app.vesper.dailyItems
import app.vesper.dailyRemindAt
import app.vesper.dailyReminder
import app.vesper.dailyReminderText
import app.vesper.dayStart
import app.vesper.doneToday
import app.vesper.nextRemindHour
import app.vesper.data.Idea
import app.vesper.data.Lobe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** 2.7.8: the Daily brain, its card, its exclusions, its reminder, and what a tap on each small brain opens. */
class DailyTest {
    private val zone = ZoneId.of("Asia/Tbilisi")
    private val lobes = listOf(
        Lobe(id = 1, name = "", colorIndex = 0, slot = 0, uid = "l1"), Lobe(id = 2, name = "Tasks", colorIndex = 1, slot = 1, uid = "l2"),
        Lobe(id = 3, name = "Pinned", colorIndex = 2, slot = 2, uid = "l3"), Lobe(id = 4, name = " daily ", colorIndex = 3, slot = 3, uid = "l4"),
    )
    private fun idea(id: Long, lobe: Long, title: String, done: Boolean = false, syncAt: Long = 0) = Idea(id = id, lobeId = lobe, title = title, body = "", uid = "i$id", updatedAt = id, done = done, syncAt = syncAt)
    private val ideas = listOf(
        idea(1, 1, "loose pills note"), idea(2, 2, "a task"), idea(3, 3, "pinned"),
        idea(4, 4, "pills", done = true), idea(5, 4, "workout"), idea(6, 4, "read 10 pages", done = true), idea(7, 4, "stretch"),
    )

    @Test fun dailyItemsAreTheDailyLobesIdeasInTheOrderTheyCame() {
        assertEquals(listOf("pills", "workout", "read 10 pages", "stretch"), dailyItems(lobes, ideas).map { it.title })
        assertEquals("daily", placeName(lobes[3]))
    }

    @Test fun dailyStaysOutOfTheMainBrainItsSparksSearchAndThePickers() {
        assertEquals(listOf(1L), mainBrainNotes(lobes, ideas).map { it.id })
        assertEquals(listOf(1L), buildLayout(emptyList(), mainBrainNotes(lobes, ideas)).notes.map { it.id }) // the sparks
        assertEquals(listOf("loose pills note"), searchLibrary(lobes, ideas, "pills").map { it.label })
        assertTrue(searchLibrary(lobes, ideas, "workout").isEmpty())
        assertEquals(listOf("", "Tasks", "Pinned"), PLACE_NAMES) // Move and Share offer only these three
    }

    @Test fun theCardTitleAndTheBrainsBrightness() {
        val items = dailyItems(lobes, ideas)
        assertEquals("daily · 2 of 4", dailyTitle(items))
        assertEquals(DAILY_DIM, dailyBrightness(items))
        assertTrue(DAILY_DIM < 1f)
        assertEquals(1f, dailyBrightness(items.map { it.copy(done = true) }))
        assertEquals("no items: fully lit", 1f, dailyBrightness(emptyList()))
        assertEquals("daily · 0 of 0", dailyTitle(emptyList()))
    }

    @Test fun theReminderTextAndWhenThereIsNone() {
        assertEquals("daily: 2 left · pills, workout", dailyReminderText(listOf("pills", "workout")))
        assertNull("nothing undone: no notification", dailyReminderText(emptyList()))
        val now = ZonedDateTime.of(2026, 11, 23, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
        val today = dayStart(LocalDate.of(2026, 11, 23), zone)
        val items = listOf(idea(1, 4, "pills", true, today + 1000), idea(2, 4, "workout"), idea(3, 4, "read 10 pages", true, today + 5), idea(4, 4, "stretch"))
        assertEquals("daily: 2 left · workout, stretch", dailyReminder(items, now, zone))
        assertNull(dailyReminder(items.map { it.copy(done = true, syncAt = today + 9) }, now, zone))
        assertNull("no items: no notification", dailyReminder(emptyList(), now, zone))
        // a tick from yesterday that no reset has cleared yet does not count
        assertEquals("daily: 1 left · pills", dailyReminder(listOf(idea(1, 4, "pills", true, today - 1)), now, zone))
    }

    @Test fun aTickOnlyCountsOnItsOwnDay() {
        val start = dayStart(LocalDate.of(2026, 11, 23), zone)
        assertTrue(doneToday(idea(1, 4, "x", true, start), start))
        assertFalse(doneToday(idea(1, 4, "x", true, start - 1), start))
        assertFalse(doneToday(idea(1, 4, "x", false, start + 5), start))
    }

    @Test fun theRemindChipCyclesAndTheTimeIsTheNextOne() {
        assertEquals(listOf(18, 19, 20, 21, 22, null), DAILY_REMIND_HOURS)
        assertEquals(21, nextRemindHour(20)); assertNull(nextRemindHour(22)); assertEquals(18, nextRemindHour(null))
        assertEquals("remind at 20:00", remindLabel(20)); assertEquals("remind: off", remindLabel(null))
        val before = ZonedDateTime.of(2026, 11, 23, 19, 59, 0, 0, zone).toInstant().toEpochMilli()
        val after = ZonedDateTime.of(2026, 11, 23, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(after, dailyRemindAt(20, before, zone))
        assertEquals(after + 86_400_000L, dailyRemindAt(20, after, zone))
    }

    /** A single tap on the learn, tasks and daily brains reaches each one (worlds and the card); a double-tap on the daily brain adds an item. */
    @Test fun tapsOnTheSmallBrains() {
        val l = buildLayout(emptyList())
        val u = 2.28f * 2.75f * 0.6f; val dp = 2.75f
        fun tap(w: Offset, double: Boolean = false, daily: Boolean = true) = l.mapHit(w, u, nodeScale(0.6f), dp, double, daily).first
        assertEquals(MapHit.Learn, tap(LEARN_AT))
        assertEquals(MapHit.Tasks, tap(TASKS_AT))
        assertEquals(MapHit.Daily, tap(DAILY_AT))
        assertEquals(MapHit.Daily, tap(DAILY_AT + Offset(4f, -3f)))
        assertEquals(MapHit.Daily, tap(DAILY_AT, double = true))
        assertEquals(MapHit.Brain, tap(Offset.Zero))
        assertEquals(MapHit.Heart, tap(HEART_AT))
        assertEquals("no daily brain drawn, nothing there", MapHit.Empty, tap(DAILY_AT, daily = false))
        assertEquals("the Bible spot is empty", MapHit.Empty, tap(RESERVED_AT[1]))
        assertEquals(MapHit.None, tap(RESERVED_AT[1], double = true))
    }
}
