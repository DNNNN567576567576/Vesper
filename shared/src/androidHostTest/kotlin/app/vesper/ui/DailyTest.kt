package app.vesper.ui

import androidx.compose.ui.geometry.Offset
import app.vesper.STATS_COUNT_DAYS
import app.vesper.STATS_DAYS
import app.vesper.dailyItems
import app.vesper.dailyStats
import app.vesper.dayStart
import app.vesper.data.DailyLog
import app.vesper.data.Idea
import app.vesper.data.Lobe
import app.vesper.data.dailyLogUid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 2.7.8 / 2.8.0: the Daily brain, its candles, its exclusions, its stats, and what a tap on each small brain opens. */
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

    @Test fun theCardCountAndTheBrainsBrightness() {
        val items = dailyItems(lobes, ideas)
        assertEquals("2 / 4", dailyCount(items))
        assertEquals(DAILY_DIM, dailyBrightness(items))
        assertTrue(DAILY_DIM < 1f)
        assertEquals(1f, dailyBrightness(items.map { it.copy(done = true) }))
        assertEquals("no items: fully lit", 1f, dailyBrightness(emptyList()))
        assertEquals("0 / 0", dailyCount(emptyList()))
    }

    /** 2.8.0: an unlit candle is only dark purple wax, no flame; lit, it has a white and lilac flame and lighter wax; it flickers. Never pink. */
    @Test fun candleLitAndUnlit() {
        val unlit = candlePixels(false)
        val lit = candlePixels(true)
        assertTrue("no flame on an unlit candle", unlit.none { it.third == 0xFFFFFF || it.third == 0xCDB8FF })
        assertTrue("a white flame core", lit.any { it.third == 0xFFFFFF && it.second < 7 })
        assertTrue("a lilac flame edge", lit.any { it.third == 0xCDB8FF })
        val wax = { p: List<Triple<Int, Int, Int>> -> p.filter { it.second == 12 }.map { it.third }.toSet() }
        assertTrue("lit wax is lighter", wax(lit).sumOf { lum(it) } > wax(unlit).sumOf { lum(it) })
        assertFalse("the flame flickers", candlePixels(true, 0) == candlePixels(true, 2))
        assertEquals(candlePixels(false, 0), candlePixels(false, 2))
        for ((x, y, rgb) in lit + unlit) {
            assertTrue("inside the 9 x 20 art", x in 0 until CANDLE_W && y in 0 until CANDLE_H)
            assertTrue("${rgb.toString(16)} leans red (pink)", (rgb shr 16 and 0xFF) <= (rgb and 0xFF))
        }
    }
    private fun lum(rgb: Int) = (rgb shr 16 and 0xFF) + (rgb shr 8 and 0xFF) + (rgb and 0xFF)

    /** 2.8.0: a tap on a candle toggles it, so the card's count and the brain's brightness follow. */
    @Test fun candleToggle() {
        val items = dailyItems(lobes, ideas)
        fun toggle(list: List<Idea>, i: Idea) = list.map { if (it.id == i.id) it.copy(done = !it.done) else it }
        val a = toggle(items, items[1]) // workout lit
        assertEquals("3 / 4", dailyCount(a))
        val b = toggle(a, a[3])
        assertEquals("4 / 4", dailyCount(b)); assertEquals(1f, dailyBrightness(b))
        val c = toggle(b, b[0]) // pills snuffed again
        assertEquals("3 / 4", dailyCount(c)); assertEquals(DAILY_DIM, dailyBrightness(c))
    }

    /** 2.8.0: the stats: 28 squares per current item (oldest first, today last), counts over the last 14 days; a deleted item's rows are left out. */
    @Test fun statsFromTheLog() {
        val today = LocalDate.of(2026, 11, 24)
        val items = dailyItems(lobes, ideas).take(2) // pills (i4), workout (i5)
        fun row(item: String, daysAgo: Long, done: Boolean = true) = today.minusDays(daysAgo).toString().let { DailyLog(itemUid = item, date = it, done = done, uid = dailyLogUid(item, it)) }
        val log = (0L..13L).filter { it != 3L && it != 5L && it != 9L }.map { row("i4", it) } + // pills 11 of 14
            listOf(row("i4", 20), row("i4", 27), row("i4", 28), row("i4", 2, done = false).copy(uid = "x"), row("gone", 0), row("i5", 1, done = false))
        val s = dailyStats(items, log, today)
        assertEquals(listOf("pills", "workout"), s.map { it.item.title })
        assertEquals(STATS_DAYS, s[0].days.size)
        assertEquals(11, s[0].doneRecent)
        assertEquals("11 of $STATS_COUNT_DAYS days", statsCount(s[0]))
        assertTrue("today is last", s[0].days.last())
        assertFalse(s[0].days[STATS_DAYS - 1 - 3])
        assertTrue("27 days ago is the first square", s[0].days.first())
        assertEquals("28 days ago is outside", 13, s[0].days.count { it })
        assertEquals(0, s[1].doneRecent)
        assertTrue(s[1].days.none { it })
        assertTrue("no rows: all dark", dailyStats(items, emptyList(), today).all { st -> st.days.none { it } && st.doneRecent == 0 })
    }

    /** 2.8.0: a single tap on the learn, tasks and daily brains reaches each one at once (worlds and the card); there is no double-tap any more. */
    @Test fun tapsOnTheSmallBrains() {
        val l = buildLayout(emptyList())
        val u = 2.28f * 2.75f * 0.6f; val dp = 2.75f
        fun tap(w: Offset, daily: Boolean = true) = l.mapHit(w, u, nodeScale(0.6f), dp, daily).first
        assertEquals(MapHit.Learn, tap(LEARN_AT))
        assertEquals(MapHit.Tasks, tap(TASKS_AT))
        assertEquals(MapHit.Daily, tap(DAILY_AT))
        assertEquals(MapHit.Daily, tap(DAILY_AT + Offset(4f, -3f)))
        assertEquals(MapHit.Brain, tap(Offset.Zero))
        assertEquals(MapHit.Heart, tap(HEART_AT))
        assertEquals("no daily brain drawn, nothing there", MapHit.Empty, tap(DAILY_AT, daily = false))
        assertEquals("the Bible spot is empty", MapHit.Empty, tap(RESERVED_AT[1]))
    }
}
