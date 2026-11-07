package app.vesper.ui

import androidx.compose.ui.geometry.Offset
import app.vesper.data.Branch
import app.vesper.data.Idea
import app.vesper.data.Lobe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the Brain-as-notes work: rings, search, share parsing, hole placement, camera fit. */
class BrainNotesTest {
    @Test fun ringsFollowHowMuchIsWritten() {
        assertEquals(0, ringCount("a long title and nothing else", ""))
        assertEquals(0, ringCount("title", "  \n "))
        assertEquals(1, ringCount("", "x"))
        assertEquals(1, ringCount("t".repeat(40), "b".repeat(60))) // exactly 100
        assertEquals(2, ringCount("t".repeat(40), "b".repeat(61))) // 101
        assertEquals(2, ringCount("t".repeat(100), "b".repeat(300))) // exactly 400
        assertEquals(3, ringCount("t".repeat(100), "b".repeat(301))) // 401
        assertEquals(3, ringCount("", "ა".repeat(800)))
    }

    @Test fun layoutKnowsRingsAndRecentItems() {
        val lobe = Lobe(id = 1, name = "L", colorIndex = 0, slot = 0, uid = "l")
        val now = 10L * 86_400_000L
        val ideas = listOf(
            Idea(id = 1, lobeId = 1, title = "fresh", body = "x", uid = "a", updatedAt = now - RECENT_MS),
            Idea(id = 2, lobeId = 1, title = "old", body = "", uid = "b", updatedAt = now - RECENT_MS - 1),
        )
        val web = placeIdeas(listOf(lobe), emptyList(), ideas)
        val layout = buildLayout(listOf(lobe), web, now)
        val at = web.items.withIndex().associate { it.value.idea.uid to it.index }
        assertEquals(listOf(true, false), listOf(layout.irecent[at["a"]!!], layout.irecent[at["b"]!!]))
        assertEquals(listOf(1, 0), listOf(layout.irings[at["a"]!!], layout.irings[at["b"]!!]))
    }

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

    private fun crowded(): Triple<List<Lobe>, List<Branch>, List<Idea>> {
        val lobes = (0 until 6).map { Lobe(id = it + 1L, name = "L$it", colorIndex = it, slot = it, uid = "lobe$it") }
        val branches = lobes.flatMap { l -> (0 until 3).map { Branch(id = l.id * 10 + it, lobeId = l.id, name = "G$it", uid = "${l.uid}-g$it") } }
        var n = 1L
        val ideas = lobes.flatMap { l ->
            branches.filter { it.lobeId == l.id }.flatMap { b -> (0 until 4).map { Idea(id = n++, lobeId = l.id, title = "t", body = "b", uid = "${b.uid}-$it", branchId = b.id, updatedAt = 1) } } +
                (0 until 6).map { Idea(id = n++, lobeId = l.id, title = "t", body = "", uid = "${l.uid}-loose$it", updatedAt = 1) }
        }
        return Triple(lobes, branches, ideas)
    }

    @Test fun rotHolesNeverSitBehindNodesOrLabels() {
        val (l, b, i) = crowded()
        val layout = buildLayout(l, placeIdeas(l, b, i), now = 0)
        val boxes = keepOutBoxes(layout.lx, layout.ly, layout.bx, layout.by, layout.ix, layout.iy, 0f, 125f)
        assertTrue("every hole was dropped", layout.blots.size >= 4 * 8)
        for (k in 0 until layout.blots.size / 4) {
            val x = layout.blots[k * 4]; val y = layout.blots[k * 4 + 1]; val r = layout.blots[k * 4 + 2]
            assertTrue("hole $k at $x,$y (r=$r) sits on something", holeClears(x, y, r + 1f, boxes))
        }
    }

    @Test fun clearingAHoleIsDeterministicAndDropsItWhenThereIsNoRoom() {
        val box = floatArrayOf(-10f, -10f, 10f, 10f)
        val a = clearHole(0f, 0f, 3f, box)!!
        assertTrue(holeClears(a[0], a[1], 3f, box))
        assertEquals(a.toList(), clearHole(0f, 0f, 3f, box)!!.toList())
        assertEquals(listOf(30f, 0f), clearHole(30f, 0f, 3f, box)!!.toList()) // already clear: untouched
        // a crowd of boxes: whatever comes out is clear of all of them, or the hole is dropped
        val crowd = FloatArray(40 * 4) { i -> if (i % 4 < 2) (i / 4 % 7) * 9f - 27f + (i % 4) * 5f else (i / 4 % 7) * 9f - 27f + (i % 4) * 5f + 8f }
        for (n in 0 until 30) {
            val got = clearHole(n * 2f - 20f, n % 5 * 3f, 3f, crowd)
            if (got != null) assertTrue(holeClears(got[0], got[1], 3f, crowd))
        }
    }

    @Test fun focusFitCentresTheCloudBelowTheHeader() {
        val pts = listOf(Offset(-20f, 40f), Offset(20f, 40f), Offset(0f, 90f))
        val w = 180f; val h = 380f; val top = 10f
        val aim = fitCloud(pts, w, h, top, 0.8f, 3f)
        val z = aim[2]
        assertTrue(z in 0.8f..3f)
        // where the cloud lands on screen (low-res px): inside the view, below the header, and not pushed to one side
        fun sx(x: Float) = (x - aim[0]) * z + w / 2f
        fun sy(y: Float) = (y - aim[1]) * z + h / 2f
        assertTrue(sx(-20f) > 0f && sx(20f) < w && sy(40f) > top && sy(90f) < h)
        assertEquals(w / 2f, (sx(-20f) + sx(20f)) / 2f, 0.01f)
        assertEquals(sy(40f) - top, h - sy(90f), 40f) // roughly the same air above and below
    }
}
