package app.vesper.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.hypot
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
        assertEquals(48L * 3_600_000L, RECENT_MS) // two days, not a week
        val ideas = listOf(
            Idea(id = 1, lobeId = 1, title = "fresh", body = "x", uid = "a", updatedAt = now - RECENT_MS),
            Idea(id = 2, lobeId = 1, title = "old", body = "", uid = "b", updatedAt = now - RECENT_MS - 1),
            Idea(id = 3, lobeId = 1, title = "yesterday", body = "", uid = "c", updatedAt = now - 86_400_000L),
            Idea(id = 4, lobeId = 1, title = "this week", body = "", uid = "d", updatedAt = now - 5 * 86_400_000L),
        )
        val web = placeIdeas(listOf(lobe), emptyList(), ideas)
        val layout = buildLayout(listOf(lobe), web, now)
        val at = web.items.withIndex().associate { it.value.idea.uid to it.index }
        assertEquals(listOf(true, false), listOf(layout.irecent[at["a"]!!], layout.irecent[at["b"]!!]))
        assertEquals(listOf(1, 0), listOf(layout.irings[at["a"]!!], layout.irings[at["b"]!!]))
        assertEquals(listOf(true, false), listOf(layout.irecent[at["c"]!!], layout.irecent[at["d"]!!]))
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

    // ---- the focused sub brain: a family tree, every name under its node, nothing over a neuron or ring, all of it in view ----

    private class Shape(val name: String, val loose: Int, val groups: List<Int>)

    /** The shapes seen on the phone: Poetry, Coding, Tasks, Languages, Music/Dances, twelve items loose and in groups, five groups, and a mix. */
    private val shapes = listOf(
        Shape("poetry", 0, listOf(6, 1)), Shape("coding", 0, listOf(2, 4, 3)), Shape("tasks", 6, emptyList()), Shape("languages", 0, listOf(0, 0)),
        Shape("music", 0, listOf(1, 4)), Shape("twelve", 12, emptyList()), Shape("twelve in groups", 0, listOf(4, 4, 4)),
        Shape("five groups", 0, listOf(1, 2, 1, 0, 1)), Shape("mixed", 2, listOf(2, 0, 1)),
    )

    private fun layoutOf(shape: Shape, slot: Int, body: Int): Layout { // body: characters in each note; -1 = a mix of 0, 1, 2 and 3 rings
        val lobe = Lobe(id = 1, name = "L", colorIndex = 0, slot = slot, uid = "lobe-$slot")
        val branches = shape.groups.indices.map { Branch(id = it + 1L, lobeId = 1, name = "G$it", uid = "g$it") }
        var n = 1L
        fun text(j: Int) = "b".repeat(if (body >= 0) body else intArrayOf(0, 60, 150, 450)[(j * 7 + 1) % 4])
        val ideas = (0 until shape.loose).map { Idea(id = n++, lobeId = 1, title = "t", body = text(it), uid = "l$it") } +
            shape.groups.flatMapIndexed { g, c -> (0 until c).map { Idea(id = n++, lobeId = 1, title = "t", body = text(it + g), uid = "g$g-$it", branchId = g + 1L) } }
        return buildLayout(listOf(lobe), placeIdeas(listOf(lobe), branches, ideas, focus = 1), now = 0)
    }

    /** Names as wide as BrainLabels makes them: two lines (long), or one short word. */
    private fun sceneOf(shape: Shape, slot: Int, longNames: Boolean, body: Int = -1): FocusScene {
        val layout = layoutOf(shape, slot, body)
        val cols = focusCols(shape.loose, shape.groups.size)
        val iw = focusItemWidth(cols, focusSub(shape.loose, shape.groups.size)) + 2f
        return FocusScene(
            Offset(layout.lx[0], layout.ly[0]),
            (0 until layout.bstart[1]).map { Offset(layout.bx[it], layout.by[it]) }, List(layout.bstart[1]) { Size(focusGroupWidth(cols) + 2f, 46f) },
            (0 until layout.nI).map { Offset(layout.ix[it], layout.iy[it]) }, layout.irings, List(layout.nI) { if (longNames) Size(iw, 44f) else Size(50f, 23f) },
            IntArray(layout.nI) { layout.ipar[it] },
        )
    }

    /** Trees this tall (columns of notes with 2 or 3 rings, or two rows of groups) cannot keep every name clear of every ring on a phone at readable name sizes: they are held to the no-overlap rule only with one-ring notes, and to staying in view with every name shown (crowdedTreesStillShowEveryNameInView). */
    private val tooTall = setOf("coding", "twelve in groups", "five groups")

    private val pxdp = 2.4f
    private val viewW = 360f; private val viewH = 780f; private val header = 60f

    @Test fun focusedNamesNeverSitOnNeuronsRingsOrEachOther() {
        for (shape in shapes) for (slot in 0..6) for (long in listOf(true, false)) for (body in listOf(-1, 60, 450)) {
            if (body != 60 && shape.name in tooTall) continue
            val s = sceneOf(shape, slot, long, body)
            val aim = fitFocus(s, viewW, viewH, header, pxdp, 0.8f, 6f)
            val p = planFocus(s, aim[2], pxdp)
            val tag = "${shape.name} slot $slot body $body z=${aim[2]}"
            // every name got a place (these shapes hold at most 12 items)
            assertTrue("$tag: a name was dropped", p.groups.none { it == null } && p.items.none { it == null })
            val boxes = ArrayList<FloatArray>()
            val k = pxdp * aim[2]
            fun add(node: Offset, at: Offset?, size: Size) { boxes.add(floatArrayOf(node.x * k + at!!.x, node.y * k + at.y, node.x * k + at.x + size.width, node.y * k + at.y + size.height)) }
            s.groups.forEachIndexed { g, o -> add(o, p.groups[g], s.groupNames[g]) }
            s.items.forEachIndexed { i, o -> add(o, p.items[i], s.itemNames[i]) }
            for ((b, box) in boxes.withIndex()) {
                for (d in p.discs) {
                    val d0 = hypot(d[0] - d[0].coerceIn(box[0], box[2]), d[1] - d[1].coerceIn(box[1], box[3]))
                    assertTrue("$tag: name $b sits on a neuron or ring at ${d[0]},${d[1]}", d0 >= d[2] - 0.01f)
                }
                for (o in b + 1 until boxes.size) {
                    val q = boxes[o]
                    assertTrue("$tag: names $b and $o overlap", box[0] >= q[2] || box[2] <= q[0] || box[1] >= q[3] || box[3] <= q[1])
                }
                // on screen: the name lands inside the view, below the header
                val l = box[0] - aim[0] * k + viewW / 2; val r = box[2] - aim[0] * k + viewW / 2
                val t = box[1] - aim[1] * k + viewH / 2; val bt = box[3] - aim[1] * k + viewH / 2
                assertTrue("$tag: name $b clipped ($l,$t,$r,$bt)", l >= 10f - 0.01f && r <= viewW - 10f + 0.01f && t >= header && bt <= viewH)
            }
        }
    }

    @Test fun focusPutsTheLobeAtTheTopCentreAndFillsTheWidth() {
        for (shape in shapes) for (slot in listOf(0, 1, 4)) {
            val s = sceneOf(shape, slot, true)
            val aim = fitFocus(s, viewW, viewH, header, pxdp, 0.8f, 6f)
            val z = aim[2]
            assertTrue(z in 0.8f..6f)
            val b = planFocus(s, z, pxdp, force = true).box
            val k = pxdp * z
            // where the box lands on screen (dp): inside the area below the header
            val l = b[0] - aim[0] * k + viewW / 2; val r = b[2] - aim[0] * k + viewW / 2
            val t = b[1] - aim[1] * k + viewH / 2; val bt = b[3] - aim[1] * k + viewH / 2
            val tag = "${shape.name} slot $slot z=$z"
            assertTrue("$tag: out of the view ($l,$t,$r,$bt)", l >= 10f - 0.01f && r <= viewW - 10f + 0.01f && t >= header && bt <= viewH)
            assertEquals("$tag: lobe not at the centre", viewW / 2, s.lobe.x * k - aim[0] * k + viewW / 2, 0.01f)
            // the lobe's ring is the top of the tree
            assertEquals("$tag: lobe not at the top", t, planFocus(s, z, pxdp, force = true).discs[0].let { it[1] - it[2] } - aim[1] * k + viewH / 2, 0.01f)
            // it fills the width or the height; a short tree is not stretched, and sits above the middle
            assertTrue("$tag: neither full width nor full height", r - l >= viewW - 50f || bt - t >= viewH - header - 30f)
            assertTrue("$tag: a short tree should sit above the middle", t - header <= viewH - bt + 0.5f)
        }
    }

    @Test fun focusFormationIsAFamilyTree() {
        val lobe = Offset(10f, 60f)
        fun rings(n: Int) = List(n) { it * 5 % 4 }
        for (shape in shapes) {
            val f = focusFormation(lobe, rings(shape.loose), shape.groups.map { rings(it) })
            val tag = shape.name
            assertEquals(tag, shape.groups, f.items.map { it.size })
            assertEquals(tag, shape.loose, f.loose.size)
            assertEquals(tag, f.groups, focusFormation(lobe, rings(shape.loose), shape.groups.map { rings(it) }).groups) // same counts, same tree
            val cols = focusCols(shape.loose, shape.groups.size)
            val colW = TREE_W / cols
            // groups in a row share one y; each row is evenly spread and centred on the lobe; every row is below the lobe
            val rows = f.groups.groupBy { it.y }
            assertTrue("$tag: a group touches the lobe", f.groups.all { it.y > lobe.y + 15f + 8f })
            rows.values.forEach { row ->
                assertTrue("$tag: a row is wider than its columns", row.size <= cols)
                if (shape.loose == 0) assertEquals(tag, lobe.x, row.map { it.x }.average().toFloat(), 0.01f)
                val xs = row.map { it.x }.sorted()
                xs.zipWithNext { a, b -> assertEquals("$tag: uneven", colW, b - a, 0.01f) }
            }
            // each item sits in its own group's column, below it
            f.groups.forEachIndexed { g, o ->
                f.items[g].forEach {
                    assertTrue("$tag: item at ${it.x} outside group $g's column", abs(it.x - o.x) <= colW / 2 - 4f)
                    assertTrue("$tag: item not below its group", it.y > o.y + 20f)
                }
            }
            // loose items hang off the lobe, below it, and never inside the column of a group beside it
            assertTrue("$tag: loose above the lobe", f.loose.all { it.y > lobe.y + 15f })
            if (shape.groups.isNotEmpty()) f.loose.forEach { l -> assertTrue("$tag: loose item in a group's column", f.groups.filter { it.y == f.groups.minOf { q -> q.y } }.all { abs(it.x - l.x) > colW / 2 - 4f }) }
        }
        // six loose items with no groups: a tidy grid, 3 across and 2 down, centred under the lobe
        val grid = focusFormation(lobe, List(6) { 2 }, emptyList()).loose
        assertEquals(grid[0].y, grid[2].y, 0.01f); assertEquals(grid[3].y, grid[5].y, 0.01f); assertEquals(grid[0].x, grid[3].x, 0.01f)
        assertEquals(lobe.x, grid.map { it.x }.average().toFloat(), 0.01f)
    }

    @Test fun rotHolesKeepOffTheWholeTreeOfAFocusedSubBrain() {
        for (shape in shapes) for (slot in 0..6) {
            val layout = layoutOf(shape, slot, 10)
            val boxes = focusKeepOutBoxes(layout.lx[0], layout.ly[0], layout.bx.toList(), layout.by.toList(), layout.ix.toList(), layout.iy.toList())
            for (k in 0 until layout.blots.size / 4) {
                assertTrue("${shape.name} slot $slot: hole $k sits on the tree", holeClears(layout.blots[k * 4], layout.blots[k * 4 + 1], layout.blots[k * 4 + 2] + 1f, boxes))
            }
            // every node and the room for its name is inside the box
            for (k in 0 until layout.nI) assertTrue(layout.ix[k] in boxes[0]..boxes[2] && layout.iy[k] + 18f <= boxes[3])
        }
    }

    @Test fun crowdedTreesStillShowEveryNameInView() {
        // too tall for every name to be clear of every ring: the fit keeps the whole tree in view and no name goes missing
        for (name in tooTall) {
            val shape = shapes.first { it.name == name }
            val s = sceneOf(shape, 0, true, 450)
            val aim = fitFocus(s, viewW, viewH, header, pxdp, 0.8f, 6f)
            val p = planFocus(s, aim[2], pxdp, force = true)
            val k = pxdp * aim[2]
            assertTrue("$name: a name went missing", p.groups.none { it == null } && p.items.none { it == null })
            assertTrue("$name: out of the view", p.box[0] - aim[0] * k + viewW / 2 >= 0f && p.box[2] - aim[0] * k + viewW / 2 <= viewW &&
                p.box[1] - aim[1] * k + viewH / 2 >= header && p.box[3] - aim[1] * k + viewH / 2 <= viewH)
        }
    }

    // ---- the overview: every lobe, item, name and the heart in view, names apart ----

    private val names = listOf("Tasks", "Music/Dances", "Personal", "Languages", "Coding", "Poetry", "My Father", "Recipes")

    /** [perLobe] notes in each lobe: none, or a cloud with short notes (one ring at most) loose and in two groups. */
    private fun overviewOf(n: Int, perLobe: Int = 5): Pair<Layout, List<Size?>> {
        val lobes = (0 until n).map { Lobe(id = it + 1L, name = names[it], colorIndex = it, slot = it, uid = "lobe-$it") }
        val branches = lobes.flatMap { l -> (0..1).map { Branch(id = l.id * 10 + it, lobeId = l.id, name = "G$it", uid = "g${l.id}$it") } }
        val ideas = lobes.flatMap { l -> (0 until perLobe).map { Idea(id = l.id * 100 + it, lobeId = l.id, title = "t", body = "b".repeat(it * 15), uid = "i${l.id}-$it", branchId = if (it % 3 == 0) null else l.id * 10 + it % 2) } }
        return buildLayout(lobes, placeIdeas(lobes, branches, ideas), now = 0) to lobes.map { Size(minOf(150f, it.name.length * 11f), 28f) }
    }

    @Test fun overviewFitsEveryLobeNameAndTheHeartOnAPhone() {
        val vault = Size(48f, 28f)
        // lobes and names for 6 to 8 lobes; with a cloud too up to 7 (8 lobes with clouds are wider than the 0.6 minimum zoom on 360dp)
        for (n in 6..8) for (perLobe in listOf(0, 5)) {
            if (n == 8 && perLobe > 0) continue
            val (layout, sizes) = overviewOf(n, perLobe)
            val aim = fitOverview({ z -> overviewBoxes(layout, sizes, vault, z, pxdp) }, viewW, viewH, 76f, pxdp, 0.85f, 0.6f)
            val z = aim[2]
            assertTrue("$n lobes: zoom $z", z in 0.6f..0.85f)
            val k = pxdp * z
            val boxes = overviewBoxes(layout, sizes, vault, z, pxdp)
            for (b in boxes) {
                val l = b[0] - aim[0] * k + viewW / 2; val r = b[2] - aim[0] * k + viewW / 2
                val t = b[1] - aim[1] * k + viewH / 2; val bt = b[3] - aim[1] * k + viewH / 2
                assertTrue("$n lobes at z=$z: a box is out of the area ($l,$t,$r,$bt)", l >= 10f - 0.01f && r <= viewW - 10f + 0.01f && t >= 76f + 10f - 0.01f && bt <= viewH - 10f + 0.01f)
            }
            // names keep apart from each other and from the heart
            val ns = nodeScale(z)
            val at = lobeNames(layout, sizes, k, ns)
            val named = (0 until n).map { i -> floatArrayOf(at[i]!!.x, at[i]!!.y, at[i]!!.x + sizes[i]!!.width, at[i]!!.y + sizes[i]!!.height) }
            val heart = floatArrayOf(HEART_BOX[0] * k, (SUN_Y + HEART_BOX[1]) * k, HEART_BOX[2] * k, (SUN_Y + HEART_BOX[3]) * k)
            val vaultBox = floatArrayOf(-vault.width / 2, (SUN_Y + 12f) * k, vault.width / 2, (SUN_Y + 12f) * k + vault.height)
            fun apart(a: FloatArray, b: FloatArray) = a[0] >= b[2] || a[2] <= b[0] || a[1] >= b[3] || a[3] <= b[1]
            for (a in named.indices) {
                assertTrue("$n lobes: name $a on the heart", apart(named[a], heart) && apart(named[a], vaultBox))
                for (b in a + 1 until n) assertTrue("$n lobes: names $a and $b collide", apart(named[a], named[b]))
            }
        }
    }

    @Test fun overviewKeepsTheHomeZoomWhenItAlreadyFits() {
        val (layout, sizes) = overviewOf(2)
        val aim = fitOverview({ z -> overviewBoxes(layout, sizes, Size(48f, 28f), z, pxdp) }, viewW, viewH, 76f, pxdp, 0.85f, 0.6f)
        assertEquals(0.85f, aim[2], 0f)
    }
}
