package app.vesper.ui

import app.vesper.data.Branch
import app.vesper.data.Idea
import app.vesper.data.Lobe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the web puts things. Row ids deliberately differ per "device": only uids and slots may matter. */
class BrainWebTest {
    /** [offset] plays a device whose local row ids differ. */
    private fun world(lobeCount: Int, groups: Int, perGroup: Int, loose: Int, offset: Long = 0): Triple<List<Lobe>, List<Branch>, List<Idea>> {
        val lobes = (0 until lobeCount).map { Lobe(id = it + 1 + offset, name = "L$it", colorIndex = it, slot = it, uid = "lobe$it") }
        val branches = lobes.flatMap { l -> (0 until groups).map { g -> Branch(id = l.id * 100 + g + offset, lobeId = l.id, name = "G$g", uid = "${l.uid}-g$g") } }
        var next = 1L + offset
        val ideas = lobes.flatMap { l ->
            val inGroups = branches.filter { it.lobeId == l.id }.flatMap { b ->
                (0 until perGroup).map { Idea(id = next++, lobeId = l.id, title = "x", body = "", uid = "${b.uid}-i$it", branchId = b.id, updatedAt = 1) }
            }
            inGroups + (0 until loose).map { Idea(id = next++, lobeId = l.id, title = "x", body = "", uid = "${l.uid}-loose$it", updatedAt = 1) }
        }
        return Triple(lobes, branches, ideas)
    }

    @Test fun bothDevicesGetTheSameWorldWhateverTheirRowIds() {
        val (l1, b1, i1) = world(6, 4, 8, 12)
        val (l2, b2, i2) = world(6, 4, 8, 12, offset = 5000)
        for (n in 0 until 6) {
            val one = placeIdeas(l1, b1, i1, focus = l1[n].id); val two = placeIdeas(l2, b2.shuffled(), i2.shuffled(), focus = l2[n].id)
            assertEquals(one.items.associate { it.idea.uid to it.pos }, two.items.associate { it.idea.uid to it.pos })
            assertEquals(one.branches.associate { it.branch.uid to it.pos }, two.branches.associate { it.branch.uid to it.pos })
        }
    }

    @Test fun portalsSitEvenlyOnOneRingAllOneSizeAndTheHeartIsOffIt() {
        for (n in 1..9) for (rx in listOf(58f, 66f, 190f)) {
            val (l, b, i) = world(n, 0, 0, 0)
            val lay = buildLayout(l.shuffled(), placeIdeas(l, b, i), now = 0, rx = rx)
            val angles = (0 until n).map { k ->
                val ex = lay.lx[k] / rx; val ey = lay.ly[k] / RING_RY
                assertEquals("n=$n rx=$rx: portal $k is off the ring", 1f, ex * ex + ey * ey, 1e-3f)
                kotlin.math.atan2(ey, ex)
            }.sorted()
            val gaps = angles.zipWithNext { a, c -> c - a } + (angles.first() + TAU - angles.last())
            gaps.forEach { assertEquals("n=$n rx=$rx: uneven ring", TAU / n, it, 1e-3f) }
            for (z in listOf(0.6f, 0.85f, 2f)) for (k in 0 until n) assertEquals(nodeScale(z), portalScale(k, nodeScale(z), -1, 0f, 0f), 0f)
            // the heart sits below the ring, clear of every portal
            assertTrue((SUN_Y + HEART_BOX[1]) > lay.ly.maxOrNull()!! + PORTAL_OUT)
        }
    }

    @Test fun theMainMapPlacesNothingButPortals() {
        val (l, b, i) = world(6, 4, 8, 12)
        val web = placeIdeas(l, b, i)
        assertTrue(web.items.isEmpty() && web.branches.isEmpty())
    }

    @Test fun everyLobeWorldIsPlacedWithoutCrashing() {
        val (l, b, i) = world(7, 2, 2, 1)
        for (lobe in l) {
            val web = placeIdeas(l, b, i, focus = lobe.id)
            val mine = i.filter { it.lobeId == lobe.id }
            assertEquals(mine.map { it.uid }.toSet(), web.items.map { it.idea.uid }.toSet())
            assertEquals(b.filter { it.lobeId == lobe.id }.map { it.uid }.toSet(), web.branches.map { it.branch.uid }.toSet())
            assertTrue(web.items.all { it.lobe.id == lobe.id } && web.branches.all { it.lobe.id == lobe.id })
            val pts = web.items.map { it.pos } + web.branches.map { it.pos }
            assertEquals("lobe ${lobe.id}: positions collide", pts.size, pts.toSet().size)
        }
    }

    @Test fun anItemHangsOffItsGroupAndAnUnknownGroupMeansLoose() {
        val lobe = Lobe(id = 1, name = "L", colorIndex = 0, slot = 0, uid = "l")
        val group = Branch(id = 7, lobeId = 1, name = "G", uid = "g")
        val web = placeIdeas(
            listOf(lobe), listOf(group),
            listOf(
                Idea(id = 1, lobeId = 1, title = "a", body = "", uid = "a", branchId = 7),
                Idea(id = 2, lobeId = 1, title = "b", body = "", uid = "b"),
                Idea(id = 3, lobeId = 1, title = "c", body = "", uid = "c", branchId = 99),
            ),
            focus = 1,
        )
        assertEquals(mapOf("a" to 0, "b" to -1, "c" to -1), web.items.associate { it.idea.uid to it.parent })
        // each constellation counts its stars from 0, in order
        assertEquals(mapOf("a" to 0, "b" to 0, "c" to 1), web.items.associate { it.idea.uid to it.j })
    }
}
