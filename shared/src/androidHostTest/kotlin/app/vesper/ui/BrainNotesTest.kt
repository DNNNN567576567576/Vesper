package app.vesper.ui

import androidx.compose.ui.geometry.Size
import app.vesper.data.Idea
import app.vesper.data.Lobe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the Brain-as-notes work: rings, search, share parsing, dimension layout, camera fit. */
class BrainNotesTest {
    private fun idea(id: Long, title: String, body: String = "") = Idea(id = id, lobeId = 1, title = title, body = body, uid = "i$id")

    @Test fun searchMatchesTitleAndBodyIgnoringCaseIncludingGeorgian() {
        val all = listOf(
            idea(1, "Kotlin Tips"), idea(2, "groceries", "buy MILK and bread"), idea(3, "კოდი", "ლექსი ღამეზე"),
            idea(4, "ᲙᲝᲓᲘ capitals"), idea(5, "unrelated"),
        )
        assertEquals(listOf(1L), searchIdeas(all, "kotlin").map { it.id })
        assertEquals(listOf(1L), searchIdeas(all, "  TIPS ").map { it.id })
        assertEquals(listOf(2L), searchIdeas(all, "milk").map { it.id })
        assertEquals(listOf(3L), searchIdeas(all, "ღამე").map { it.id })
        assertEquals(listOf(3L, 4L), searchIdeas(all, "კოდი").map { it.id }) // Mtavruli finds Mkhedruli
        assertEquals(listOf(3L, 4L), searchIdeas(all, "ᲙᲝᲓᲘ").map { it.id })
        assertTrue(searchIdeas(all, "zzz").isEmpty())
        assertTrue(searchIdeas(all, "   ").isEmpty())
    }

    @Test fun searchCoversNotesAndTasks() {
        val lobes = listOf(
            Lobe(id = 1, name = "Coding", colorIndex = 0, slot = 0, uid = "l1"), Lobe(id = 2, name = "Поэзия", colorIndex = 1, slot = 1, uid = "l2"),
            Lobe(id = 3, name = " tasks ", colorIndex = 2, slot = 2, uid = "l3"), Lobe(id = 4, name = "", colorIndex = 3, slot = 3, uid = "l4"),
        )
        val notes = listOf(
            Idea(id = 1, lobeId = 1, title = "bolts", body = "draw the LIGHTNING twice", uid = "a", branchId = 7),
            Idea(id = 2, lobeId = 2, title = "ღამე", body = "Стихи о ночи", uid = "b", link = "https://example.org/Moon"),
            Idea(id = 3, lobeId = 3, title = "buy milk", body = "", uid = "c"),
            Idea(id = 4, lobeId = 4, title = "a loose thought", body = "", uid = "d"),
        )
        fun find(q: String) = searchLibrary(lobes, notes, q).map { "${it.kind}:${it.label}:${it.where}" }
        assertEquals(listOf("note:bolts:Coding"), find("lightning")) // body text, any case
        assertTrue(find("CODING").isEmpty()) // groups are no search results any more
        assertEquals(listOf("note:ღამე:Поэзия"), find("стихи"))
        assertEquals(listOf("note:ღამე:Поэзия"), find("moon")) // the link
        assertEquals(listOf("task:buy milk:tasks"), find("milk")) // Tasks is no group, its notes are tasks
        assertEquals(listOf("note:a loose thought:main brain"), find("loose"))
        assertTrue(find("  ").isEmpty())
    }

    @Test fun mainBrainNotesAreEveryNoteButTasksNewestFirst() {
        val lobes = listOf(
            Lobe(id = 1, name = "Personal", colorIndex = 0, slot = 4, uid = "p"), Lobe(id = 2, name = "TASKS", colorIndex = 1, slot = 0, uid = "t"),
            Lobe(id = 3, name = "Coding", colorIndex = 2, slot = 1, uid = "c"), Lobe(id = 4, name = "  ", colorIndex = 3, slot = 2, uid = "n"),
            Lobe(id = 5, name = "Poetry", colorIndex = 4, slot = 9, uid = "y"),
        )
        val ideas = listOf(
            Idea(id = 1, lobeId = 1, title = "old personal", body = "", uid = "a", updatedAt = 10), Idea(id = 2, lobeId = 2, title = "a task", body = "", uid = "b", updatedAt = 99),
            Idea(id = 3, lobeId = 3, title = "newest coding", body = "", uid = "c", updatedAt = 50), Idea(id = 4, lobeId = 4, title = "loose", body = "", uid = "d", updatedAt = 30),
        )
        assertEquals(listOf("newest coding", "loose", "old personal"), mainBrainNotes(lobes, ideas).map { it.title }) // Personal is not kept apart, tasks are left out
        assertEquals(listOf("Personal", "tasks", "Coding", "main brain", "Poetry"), lobes.map(::placeName))
    }

    @Test fun aVeinRunsEndToEnd() {
        val o = FloatArray(2)
        veinPoint(o, 3f, 4f, 93f, 44f, 8f, 0f); assertEquals(listOf(3f, 4f), listOf(o[0], o[1]))
        veinPoint(o, 3f, 4f, 93f, 44f, 8f, 1f); assertEquals(listOf(93f, 44f), listOf(o[0], o[1]))
        veinPoint(o, 3f, 4f, 93f, 44f, 8f, 0.5f)
        assertTrue("a middle point bows off the straight line by half the bend", kotlin.math.abs(o[0] - 48f) < 8f && kotlin.math.abs(o[1] - 24f) < 8f)
    }

    @Test fun theBrainPaletteHasNoPink() {
        for (c in LobeColors) {
            assertTrue("$c leans pink", c.blue >= c.red && c.blue >= c.green)
            // hue with blue the largest channel: 240 + 60 * (r - g) / (max - min)
            val hue = 240f + 60f * (c.red - c.green) / (c.blue - minOf(c.red, c.green))
            assertTrue("$c hue $hue is not purple", hue in 240f..290f)
        }
    }

    @Test fun sharedTextBecomesTitleBodyAndLink() {
        val plain = parseShare("First line\nsecond line\n\nthird")
        assertEquals(Triple("First line", "First line\nsecond line\n\nthird", null), Triple(plain.title, plain.body, plain.link))
        val url = parseShare("  https://example.org/a?b=1 \n", null)
        assertEquals(Triple("https://example.org/a?b=1", "https://example.org/a?b=1", "https://example.org/a?b=1"), Triple(url.title, url.body, url.link))
        assertEquals("A page", parseShare("https://example.org", "A page").title)
        val mixed = parseShare("look at https://example.org now")
        assertNull(mixed.link) // only text that is nothing but an address is a link
        assertEquals("look at https://example.org now", mixed.title)
        assertEquals("ლექსი", parseShare("ლექსი\nმეორე").title)
    }

    // ---- the main map: the core, the heart, the learn brain, the tasks brain with its tasks ----

    private val pxdp = 2.4f
    private val viewW = 360f; private val viewH = 780f
    private val vault = Size(48f, 28f)

    /** A map of [n] tasks with short names, or long ones when [long]; their widths are the ones the scatter plans for. */
    private fun mapWith(n: Int, long: Boolean = false): Pair<Layout, List<Size>> {
        val tasks = (0 until n).map { Idea(id = it + 1L, lobeId = 1, title = if (long) "a task with a long name $it, so long it is cut" else "task $it", body = "", uid = "t$it", done = it % 4 == 3) }
        val l = buildLayout(tasks)
        return l to l.tasks.map { Size(0f, 0f) }
    }
    private fun aimOf(n: Int, w: Float = viewW, h: Float = viewH, maxZ: Float = NODE_ZOOM): FloatArray {
        val (l, _) = mapWith(n)
        return fitOverview({ z -> overviewBoxes(l, vault, z, pxdp) }, w, h, 76f, pxdp, maxZ, 0.3f)
    }
    private fun apart(a: FloatArray, b: FloatArray) = a[0] >= b[2] || a[2] <= b[0] || a[1] >= b[3] || a[3] <= b[1]

    /** Home: everything is in view, tasks and board too (the task flower has no names at any zoom). */
    @Test fun homeHoldsEveryTask() {
        for (n in 0..15) for (long in listOf(false, true)) {
            val (layout, sizes) = mapWith(n, long)
            val aim = fitOverview({ z -> overviewBoxes(layout, vault, z, pxdp) }, viewW, viewH, 76f, pxdp, NODE_ZOOM, 0.3f)
            val ctx = "$n tasks, long=$long, z=${aim[2]}"
            val k0 = pxdp * aim[2]
            for (b in overviewBoxes(layout, vault, aim[2], pxdp)) {
                val l = b[0] - aim[0] * k0 + viewW / 2; val r = b[2] - aim[0] * k0 + viewW / 2
                val t = b[1] - aim[1] * k0 + viewH / 2; val bt = b[3] - aim[1] * k0 + viewH / 2
                assertTrue("$ctx: a box is out of the view ($l,$t,$r,$bt)", l >= -0.01f && r <= viewW + 0.01f && t >= 76f - 0.01f && bt <= viewH + 0.01f)
            }
        }
    }

    @Test fun coldStartFitsOnceSizeAndTasksAreKnownInEitherOrder() {
        val want = aimOf(7)
        assertTrue("the fit depends on the tasks", !want.contentEquals(aimOf(0)))
        fun cam(c: BrainCamera) = listOf(c.x, c.y, c.scale)
        // size first, tasks load later: the fit on none must not stop the fit on seven
        BrainCamera().let { c ->
            c.fitUntouched(viewW.toInt()) { aimOf(0) }
            c.fitUntouched(viewW.toInt()) { aimOf(7) }
            assertEquals(want.toList(), cam(c))
        }
        // tasks first (no size yet, nothing happens), then the size
        BrainCamera().let { c ->
            c.fitUntouched(0) { aimOf(7) }
            assertEquals(listOf(0f, 0f, 0.85f), cam(c))
            c.fitUntouched(viewW.toInt()) { aimOf(7) }
            assertEquals(want.toList(), cam(c))
        }
        // a task count change re-fits while untouched, but never after a pan or zoom
        BrainCamera().let { c ->
            c.fitUntouched(viewW.toInt()) { aimOf(6) }
            c.fitUntouched(viewW.toInt()) { aimOf(7) }
            assertEquals(want.toList(), cam(c))
            c.x += 3f
            c.fitUntouched(viewW.toInt()) { aimOf(8) }
            assertEquals(want[0] + 3f, c.x, 0f)
        }
    }

    @Test fun overviewGrowsToFillTheViewUpToTheLargestZoom() {
        for ((w, h) in listOf(viewW to viewH, 1600f to 900f)) {
            val aim = aimOf(3, w, h)
            // 2.7.8: the daily brain and its name on the top ring make the map taller, so a 900 dp window fits it a little under zoom 1
            assertTrue("${w}x$h: zoom ${aim[2]}", aim[2] * pxdp >= HOME_K * 0.97f && aim[2] <= NODE_ZOOM && (w < 1000f || aim[2] > 0.8f))
        }
        assertTrue("the bigger window gets the bigger map", aimOf(3, 1600f, 900f)[2] > aimOf(3, 700f, 500f)[2])
    }
}
