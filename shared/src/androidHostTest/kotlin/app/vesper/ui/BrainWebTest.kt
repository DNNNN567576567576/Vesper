package app.vesper.ui

import androidx.compose.ui.geometry.Offset
import app.vesper.data.Branch
import app.vesper.data.Idea
import app.vesper.data.Lobe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

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

    private fun dist(a: Offset, b: Offset) = hypot(a.x - b.x, a.y - b.y)

    @Test fun bothDevicesGetTheSameMapWhateverTheirRowIds() {
        val (l1, b1, i1) = world(6, 4, 8, 12)
        val (l2, b2, i2) = world(6, 4, 8, 12, offset = 5000)
        val one = placeIdeas(l1, b1, i1); val two = placeIdeas(l2, b2.shuffled(), i2.shuffled())
        assertEquals(one.items.associate { it.idea.uid to it.pos }, two.items.associate { it.idea.uid to it.pos })
        assertEquals(one.branches.associate { it.branch.uid to it.pos }, two.branches.associate { it.branch.uid to it.pos })
    }

    @Test fun neighbouringCloudsNeverMix() {
        for (lobes in 1..14) {
            val (l, b, i) = world(lobes, 4, 10, 15)
            val web = placeIdeas(l, b, i)
            assertEquals(i.size, web.items.size)
            val nodes = web.items.map { it.lobe.id to it.pos } + web.branches.map { it.lobe.id to it.pos }
            for ((idA, a) in nodes) for ((idB, b2) in nodes) if (idA != idB) {
                assertTrue("$lobes lobes: clouds touch at $a and $b2", dist(a, b2) >= 6f)
            }
        }
    }

    @Test fun nothingLandsOnALobeTheCoreOrTheVault() {
        val (l, b, i) = world(12, 4, 10, 15)
        val web = placeIdeas(l, b, i)
        for (p in web.items.map { it.pos } + web.branches.map { it.pos }) {
            assertTrue("in the core: $p", hypot(p.x, p.y) >= 44f)
            assertTrue("in the vault: $p", dist(p, Offset(0f, 125f)) >= 21f)
        }
    }

    @Test fun fiftyPlusItemsKeepTheirSpacing() {
        val (l, b, i) = world(3, 5, 12, 20) // 80 items per lobe
        val web = placeIdeas(l, b, i)
        for (lobe in l) {
            val pts = web.items.filter { it.lobe.id == lobe.id }.map { it.pos } + web.branches.filter { it.lobe.id == lobe.id }.map { it.pos }
            assertEquals(85, pts.size)
            for (x in pts.indices) for (y in 0 until x) {
                // staggered cells are at least this far apart, less the jitter
                assertTrue("too close: ${pts[x]} ${pts[y]}", dist(pts[x], pts[y]) >= CELL_Y - 2f)
            }
        }
    }

    @Test fun anItemHangsOffItsGroupAndAnUnknownGroupMeansTheLobe() {
        val lobe = Lobe(id = 1, name = "L", colorIndex = 0, slot = 0, uid = "l")
        val group = Branch(id = 7, lobeId = 1, name = "G", uid = "g")
        val web = placeIdeas(
            listOf(lobe), listOf(group),
            listOf(
                Idea(id = 1, lobeId = 1, title = "a", body = "", uid = "a", branchId = 7),
                Idea(id = 2, lobeId = 1, title = "b", body = "", uid = "b"),
                Idea(id = 3, lobeId = 1, title = "c", body = "", uid = "c", branchId = 99),
            ),
        )
        assertEquals(mapOf("a" to 0, "b" to -1, "c" to -1), web.items.associate { it.idea.uid to it.parent })
    }

    @Test fun aGroupStaysCloseToItsItems() {
        val (l, b, i) = world(2, 3, 9, 0)
        val web = placeIdeas(l, b, i)
        for (p in web.items) assertTrue(dist(p.pos, web.branches[p.parent].pos) <= 40f)
    }
}
