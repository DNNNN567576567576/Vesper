package app.vesper.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.hypot
import app.vesper.data.Branch
import app.vesper.data.Idea
import app.vesper.data.Lobe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the Brain-as-notes work: rings, search, share parsing, dimension layout, camera fit. */
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
        val web = placeIdeas(listOf(lobe), emptyList(), ideas, focus = 1)
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

    @Test fun searchCoversSubBrainsGroupsAndNotes() {
        val lobes = listOf(Lobe(id = 1, name = "Coding", colorIndex = 0, slot = 0, uid = "l1"), Lobe(id = 2, name = "Поэзия", colorIndex = 1, slot = 1, uid = "l2"))
        val groups = listOf(Branch(id = 7, lobeId = 1, name = "Vesper", uid = "g7"))
        val notes = listOf(
            Idea(id = 1, lobeId = 1, title = "bolts", body = "draw the LIGHTNING twice", uid = "a", branchId = 7),
            Idea(id = 2, lobeId = 2, title = "ღამე", body = "Стихи о ночи", uid = "b", link = "https://example.org/Moon"),
        )
        fun find(q: String) = searchLibrary(lobes, groups, notes, q).map { "${it.kind}:${it.label}:${it.where}" }
        assertEquals(listOf("note:bolts:Coding > Vesper"), find("lightning")) // body text, any case
        assertEquals(listOf("group:Vesper:Coding"), find("VESPER"))
        assertEquals(listOf("sub brain:Coding:Brain"), find("CODING"))
        assertEquals(listOf("sub brain:Поэзия:Brain"), find("поэзия")) // Russian capitals
        assertEquals(listOf("note:ღამე:Поэзия"), find("стихи"))
        assertEquals(listOf("note:ღამე:Поэзия"), find("moon")) // the link
        assertTrue(find("  ").isEmpty())
    }

    @Test fun sparksAreSeededAndRunTheirVeinEndToEnd() {
        assertEquals(sparkProgress(7, 3.3f, 5f), sparkProgress(7, 3.3f, 5f), 0f)
        val runs = (0 until 4000).map { sparkProgress(7, it * 0.01f, 5f) }
        assertTrue("it rests most of the time", runs.count { it < 0f } > runs.size / 2)
        assertTrue("progress stays within the line", runs.all { it == -1f || it in 0f..1f })
        assertTrue("it runs at all", runs.any { it >= 0f })
        assertTrue("other seeds run at other moments", (0 until 4000).any { (sparkProgress(8, it * 0.01f, 5f) >= 0f) != (runs[it] >= 0f) })
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

    // ---- a world: the name at the top, constellations below; one column pair on a tall view, several on a wide one ----

    private val pxdp = 2.4f
    private val viewW = 360f; private val viewH = 780f

    private val tall = DimFrame(411f, 700f, 60f, 2.286f, 46f, 16f, 48f) // the phone: 1440 px across at 3.5x
    private val frames = listOf(
        "phone" to tall, "small phone" to DimFrame(360f, 620f, 60f, 2.5f, 46f, 16f, 48f),
        "wide" to DimFrame(1500f, 820f, 60f, 2.4f, 46f, 16f, 48f), "half desktop" to DimFrame(900f, 700f, 60f, 2f, 52f, 18f, 54f),
    )
    // loose notes, then notes per group; "coding" is the real Coding: 12 notes in 5 groups, "tasks" the real Tasks: 9 loose notes
    private val dims = listOf(
        "coding" to (0 to listOf(2, 3, 4, 2, 1)), "languages" to (0 to listOf(0, 0)), "tasks" to (9 to emptyList()),
        "mixed" to (3 to listOf(2, 0, 1)), "big" to (12 to listOf(5, 5, 5, 5, 5, 5, 5)), "one" to (0 to listOf(1)), "empty" to (0 to emptyList()),
        "lone" to (1 to emptyList()),
    )

    private fun apart(a: FloatArray, b: FloatArray) = a[0] >= b[2] || a[2] <= b[0] || a[1] >= b[3] || a[3] <= b[1]

    @Test fun worldKeepsEveryNameInTheViewApartAndOffStarsAndLines() {
        for ((fn, f) in frames) for ((dn, shape) in dims) {
            val (loose, groups) = shape
            val d = dimensionLayout(loose, groups, f)
            val tag = "$dn on $fn"
            val k = f.pxdp * d.aim[2]
            fun sx(wx: Float) = (wx - d.aim[0]) * k + f.w / 2f
            fun sy(wy: Float) = (wy - d.aim[1]) * k + f.h / 2f
            fun box(o: Offset, b: FloatArray) = floatArrayOf(sx(o.x) + b[0], sy(o.y) + b[1], sx(o.x) + b[2], sy(o.y) + b[3])
            val looseStars = d.loose.flatten(); val looseNames = d.looseNames.flatten()
            assertEquals(tag, loose, looseStars.size)
            val names = listOf(box(Offset.Zero, d.coreName)) + d.groups.indices.map { box(d.groups[it], d.groupNames[it]) } +
                looseStars.indices.map { box(looseStars[it], looseNames[it]) } +
                d.items.indices.flatMap { g -> d.items[g].indices.map { box(d.items[g][it], d.itemNames[g][it]) } }
            assertEquals(tag, 1 + groups.size + loose + groups.sum(), names.size)
            // every name inside the view's width, none on another
            for (n in names) assertTrue("$tag: a name leaves the view (${n.toList()})", n[0] >= 10f - 0.01f && n[2] <= f.w - 10f + 0.01f)
            for (a in names.indices) for (b in 0 until a) assertTrue("$tag: names $a and $b overlap", apart(names[a], names[b]))
            // nor on a star
            val stars = (looseStars + d.items.flatten()).map { floatArrayOf(sx(it.x) - STAR_R, sy(it.y) - STAR_R, sx(it.x) + STAR_R, sy(it.y) + STAR_R) }
            for (n in names) for (b in stars) assertTrue("$tag: a name sits on a star", apart(n, b))
            // and no constellation line runs through a name: each star to the next in its constellation
            val lines = (d.loose + d.items).flatMap { c -> c.zipWithNext { p, q -> Offset(sx(p.x), sy(p.y)) to Offset(sx(q.x), sy(q.y)) } }
            for ((p, q) in lines) for (n in names) assertTrue("$tag: a line from $p to $q crosses a name (${n.toList()})", !segmentHitsBox(p.x, p.y, q.x, q.y, n[0] + 1f, n[1] + 1f, n[2] - 1f, n[3] - 1f))
            // the page starts under the header, its title first
            assertTrue("$tag: the title is under the header", sy(0f) >= f.top)
            assertTrue("$tag: the title is at the top", names.drop(1).all { it[1] >= names[0][3] })
        }
    }

    @Test fun aTallViewGetsOnePairOfColumnsAWideOneSeveral() {
        val (loose, groups) = dims[0].second
        val phone = dimensionLayout(loose, groups, tall)
        val k = tall.pxdp * phone.aim[2]
        assertTrue("one pair: every star near the middle", phone.items.flatten().all { kotlin.math.abs(it.x * k) < 40f })
        assertTrue("constellations on both sides", phone.groups.any { it.x < 0f } && phone.groups.any { it.x > 0f })
        val wide = dimensionLayout(loose, groups, frames[2].second)
        val kw = frames[2].second.pxdp * wide.aim[2]
        assertTrue("a wide view spreads the groups across", (wide.groups.maxOf { it.x } - wide.groups.minOf { it.x }) * kw > 600f)
        assertEquals("same counts, same page", wide.groups, dimensionLayout(loose, groups, frames[2].second).groups)
    }

    @Test fun nineLooseNotesAreSplitAcrossBothSides() {
        for ((fn, f) in frames) {
            val d = dimensionLayout(9, emptyList(), f)
            val xs = d.loose.flatten().map { it.x }
            val left = xs.count { it < 0f }; val right = xs.count { it > 0f }
            assertTrue("$fn: $left left, $right right", left >= 4 && right >= 4 || d.loose.size > 2 && left + right == 9 && left > 0 && right > 0)
            assertTrue("$fn: no constellation holds them all", d.loose.all { it.size < 9 })
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
            assertTrue("$n lobes: zoom $z", z in 0.6f..0.85f) // capped at 0.85 here
            val k = pxdp * z
            val boxes = overviewBoxes(layout, sizes, vault, z, pxdp)
            for (b in boxes) {
                val l = b[0] - aim[0] * k + viewW / 2; val r = b[2] - aim[0] * k + viewW / 2
                val t = b[1] - aim[1] * k + viewH / 2; val bt = b[3] - aim[1] * k + viewH / 2
                // at the smallest zoom (names stepping round veins and threads made the map wider) the margin may go, the view may not
                if (n == 8 && z <= 0.6f) continue // 8 lobes with groups at the minimum zoom: wider than 360 dp by a hair, simply cut off
                val m = if (z <= 0.6f) 0f else 10f
                assertTrue("$n lobes at z=$z: a box is out of the area ($l,$t,$r,$bt)", l >= m - 0.01f && r <= viewW - m + 0.01f && t >= 76f + m - 0.01f && bt <= viewH - m + 0.01f)
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

    @Test fun everyLobeNameIsPlacedInEverySlotArrangement() {
        // every choice of n of the first 10 slots, which includes the real phone (Tasks, Music/Dances, Personal, Languages, Coding, Poetry, My Father in slots 0..6)
        for (n in 6..8) for (slots in subsets(10, n)) {
            val lobes = slots.mapIndexed { i, s -> Lobe(id = i + 1L, name = names[i], colorIndex = i, slot = s, uid = "l$i") }
            val ideas = lobes.flatMap { l -> (0 until 5).map { Idea(id = l.id * 100 + it, lobeId = l.id, title = "t", body = "", uid = "i${l.id}-$it") } }
            val layout = buildLayout(lobes, placeIdeas(lobes, emptyList(), ideas), now = 0)
            val sizes = lobes.map { Size(minOf(150f, it.name.length * 11f), 28f) }
            for (z in listOf(0.6f, 0.85f)) {
                val at = lobeNames(layout, sizes, pxdp * z, nodeScale(z))
                assertTrue("slots $slots z=$z: a lobe name was dropped", at.size == n && at.none { it == null })
                // deterministic: the same input twice gives the same places
                assertEquals(at, lobeNames(layout, sizes, pxdp * z, nodeScale(z)))
            }
        }
    }

    private fun subsets(of: Int, n: Int): List<List<Int>> =
        if (n == 0) listOf(emptyList()) else (0 until of).flatMap { s -> subsets(of, n - 1).filter { it.isEmpty() || it[0] > s }.map { listOf(s) + it } }

    @Test fun coldStartFitsOnceSizeAndLobesAreKnownInEitherOrder() {
        val vault = Size(48f, 28f)
        fun aimOf(n: Int): FloatArray { val (l, s) = overviewOf(n); return fitOverview({ z -> overviewBoxes(l, s, vault, z, pxdp) }, viewW, viewH, 76f, pxdp, 0.85f, 0.6f) }
        val want = aimOf(7)
        fun cam(c: BrainCamera) = listOf(c.x, c.y, c.scale)
        // size first, lobes load later: the fit on no lobes must not stop the fit on seven
        BrainCamera().let { c ->
            c.fitUntouched(viewW.toInt()) { aimOf(0) }
            c.fitUntouched(viewW.toInt()) { aimOf(7) }
            assertEquals(want.toList(), cam(c))
        }
        // lobes first (no size yet, nothing happens), then the size
        BrainCamera().let { c ->
            c.fitUntouched(0) { aimOf(7) }
            assertEquals(listOf(0f, 0f, 0.85f), cam(c))
            c.fitUntouched(viewW.toInt()) { aimOf(7) }
            assertEquals(want.toList(), cam(c))
        }
        // a lobe count change re-fits while untouched, but never after a pan or zoom
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
        val (layout, sizes) = overviewOf(2)
        val vault = Size(48f, 28f)
        for ((w, h) in listOf(viewW to viewH, 1600f to 900f)) {
            val aim = fitOverview({ z -> overviewBoxes(layout, sizes, vault, z, pxdp) }, w, h, 76f, pxdp, NODE_ZOOM, 0.6f)
            // 2.5.10: one ring as tall as the phone allows whatever the count, so a phone fits it near home zoom; a big window zooms in
            assertTrue("${w}x$h: zoom ${aim[2]}", aim[2] >= 0.75f && aim[2] <= NODE_ZOOM && (w < 1000f || aim[2] > 0.85f))
            val k = pxdp * aim[2]
            for (b in overviewBoxes(layout, sizes, vault, aim[2], pxdp)) {
                assertTrue("${w}x$h: a box is out of view", b[0] - aim[0] * k + w / 2 >= 9.9f && b[2] - aim[0] * k + w / 2 <= w - 9.9f && b[1] - aim[1] * k + h / 2 >= 76f + 9.9f && b[3] - aim[1] * k + h / 2 <= h - 9.9f)
            }
        }
        // the bigger window gets the bigger map
        val small = fitOverview({ z -> overviewBoxes(layout, sizes, vault, z, pxdp) }, 700f, 500f, 76f, pxdp, NODE_ZOOM, 0.6f)
        val big = fitOverview({ z -> overviewBoxes(layout, sizes, vault, z, pxdp) }, 1600f, 900f, 76f, pxdp, NODE_ZOOM, 0.6f)
        assertTrue(big[2] > small[2])
    }

    @Test fun lobeNamesKeepOffTheVeins() {
        // the real phone's sub brains (slots 0..6), at the phone's home zoom: no vein from the core crosses a name that has a clear spot
        val lobes = names.take(7).mapIndexed { i, n -> Lobe(id = i + 1L, name = n, colorIndex = i, slot = i, uid = "l$i") }
        val layout = buildLayout(lobes, placeIdeas(lobes, emptyList(), emptyList()), now = 0)
        val sizes = lobes.map { Size(minOf(150f, it.name.length * 11f), 28f) }
        val z = 0.85f; val k = pxdp * z
        val at = lobeNames(layout, sizes, k, nodeScale(z))
        val o = FloatArray(2)
        for (i in lobes.indices) for (j in lobes.indices) {
            val b = floatArrayOf(at[i]!!.x, at[i]!!.y, at[i]!!.x + sizes[i]!!.width, at[i]!!.y + sizes[i]!!.height)
            var px = 0f; var py = 0f
            for (q in 1..8) {
                veinPoint(o, 0f, 0f, layout.lx[j], layout.ly[j], layout.lbend[j], q / 8f)
                assertTrue("${lobes[i].name} is crossed by the vein to ${lobes[j].name}", !segmentHitsBox(px * k, py * k, o[0] * k, o[1] * k, b[0], b[1], b[2], b[3]))
                px = o[0]; py = o[1]
            }
        }
    }
}
