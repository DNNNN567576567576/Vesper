package app.vesper.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure merge rules. Device "a" merges from "b" unless a test swaps them. */
class SyncMergeTest {
    private fun note(uid: String, at: Long, text: String = "t", parent: String? = null, pos: Int = 0) = SyncNote(uid, at, "title", text, at, pos, parent)
    private fun lobe(uid: String, at: Long) = SyncLobe(uid, at, "L", 0, 0)
    private fun idea(uid: String, at: Long, lobe: String = "L", title: String = "i") = SyncIdea(uid, at, lobe, title, "", null, at)
    private fun branch(uid: String, at: Long, lobe: String = "L", name: String = "G") = SyncBranch(uid, at, lobe, name)
    private fun a(notes: List<SyncNote> = emptyList(), lobes: List<SyncLobe> = emptyList(), ideas: List<SyncIdea> = emptyList(), tombs: List<SyncTomb> = emptyList(), branches: List<SyncBranch> = emptyList()) = Snapshot(deviceId = "a", notes = notes, lobes = lobes, branches = branches, ideas = ideas, tombstones = tombs)
    private fun b(notes: List<SyncNote> = emptyList(), lobes: List<SyncLobe> = emptyList(), ideas: List<SyncIdea> = emptyList(), tombs: List<SyncTomb> = emptyList(), branches: List<SyncBranch> = emptyList()) = Snapshot(deviceId = "b", notes = notes, lobes = lobes, branches = branches, ideas = ideas, tombstones = tombs)

    @Test fun newestWins() {
        assertEquals(listOf(note("n", 2, "new")), merge(a(listOf(note("n", 1, "old"))), b(listOf(note("n", 2, "new"))), mapOf("notes:n" to 1)).notes)
        assertEquals(listOf(note("n", 2, "new")), merge(a(listOf(note("n", 2, "new"))), b(listOf(note("n", 1, "old"))), mapOf("notes:n" to 1)).notes)
    }

    @Test fun exactTieGoesToTheLargerDeviceIdOnBothDevices() {
        val x = note("n", 5, "from-a", pos = 1); val y = note("n", 5, "from-b", pos = 2)
        val seen = mapOf("notes:n" to 5L) // not a conflict: only the tie rule is in play
        assertEquals(listOf(y), merge(a(listOf(x)), b(listOf(y)), seen).notes)
        assertEquals(listOf(y), merge(b(listOf(y)), a(listOf(x)), seen).notes)
    }

    @Test fun oneSidedEditIsNoConflict() {
        val merged = merge(a(listOf(note("n", 2, "edited"))), b(listOf(note("n", 1, "base"))), mapOf("notes:n" to 1))
        assertEquals(1, merged.notes.size)
    }

    @Test fun bothEditedKeepsTheLoserAsCopy() {
        val merged = merge(a(listOf(note("n", 10, "mine"))), b(listOf(note("n", 20, "theirs"))), mapOf("notes:n" to 1))
        assertEquals(setOf("theirs", "mine"), merged.notes.map { it.content }.toSet())
        val copy = merged.notes.single { it.content == "mine" }
        assertEquals("title (other copy)", copy.title)
        assertEquals("n~10", copy.uid)
    }

    /** The hole in a wall-clock lastSyncAt rule: B's edit is older than A's last sync, yet A has never seen it. */
    @Test fun offlineEditOlderThanTheLastSyncIsStillAConflict() {
        val seen = mapOf("notes:n" to 50L) // A last saw version 50 from B, at wall-clock 200
        val merged = merge(a(listOf(note("n", 300, "A at 300"))), b(listOf(note("n", 100, "B at 100"))), seen)
        assertEquals(setOf("A at 300", "B at 100"), merged.notes.map { it.content }.toSet())
    }

    @Test fun bothDevicesBuildTheSameCopy() {
        val mine = a(listOf(note("n", 10, "mine"))); val theirs = b(listOf(note("n", 20, "theirs")))
        val seen = mapOf("notes:n" to 1L)
        assertEquals(merge(mine, theirs, seen).notes, merge(theirs.copy(deviceId = "a"), mine.copy(deviceId = "b"), seen).notes)
    }

    @Test fun aCopyDeletedByTheUserStaysDeleted() {
        val merged = merge(a(listOf(note("n", 10, "mine")), tombs = listOf(SyncTomb("notes", "n~10", 15))), b(listOf(note("n", 20, "theirs"))), mapOf("notes:n" to 1))
        assertEquals(listOf("n"), merged.notes.map { it.uid })
        assertEquals(listOf(SyncTomb("notes", "n~10", 15)), merged.tombstones)
    }

    @Test fun ideasConflictToo() {
        val l = lobe("L", 1)
        val merged = merge(a(lobes = listOf(l), ideas = listOf(idea("i", 10, title = "mine"))), b(lobes = listOf(l), ideas = listOf(idea("i", 20, title = "theirs"))), mapOf("ideas:i" to 1))
        assertEquals(setOf("theirs", "mine (other copy)"), merged.ideas.map { it.title }.toSet())
    }

    @Test fun firstSyncSameUidDifferentTextKeepsBoth() {
        assertEquals(2, merge(a(listOf(note("notes-1", 5, "x"))), b(listOf(note("notes-1", 6, "y"))), emptyMap()).notes.size)
    }

    @Test fun firstSyncSameUidSameTextIsOneRow() {
        val n = note("notes-1", 5)
        assertEquals(listOf(n), merge(a(listOf(n)), b(listOf(n)), emptyMap()).notes)
    }

    @Test fun firstSyncUnion() {
        val merged = merge(a(listOf(note("x", 5))), b(listOf(note("y", 6))), emptyMap())
        assertEquals(listOf("x", "y"), merged.notes.map { it.uid })
    }

    @Test fun oneSideEmpty() {
        assertEquals(listOf(note("y", 6)), merge(a(), b(listOf(note("y", 6))), emptyMap()).notes)
        val mine = a(listOf(note("x", 6)))
        assertEquals(mine, merge(mine, b(), emptyMap()))
    }

    @Test fun editBeatsOlderDelete() {
        val merged = merge(a(tombs = listOf(SyncTomb("notes", "n", 10))), b(listOf(note("n", 11))), emptyMap())
        assertEquals(listOf("n"), merged.notes.map { it.uid })
        assertTrue(merged.tombstones.isEmpty()) // revived rows clear their tombstone
    }

    @Test fun deleteBeatsOlderEditAndATieGoesToTheDelete() {
        for (at in listOf(9L, 10L)) {
            val merged = merge(a(listOf(note("n", at))), b(tombs = listOf(SyncTomb("notes", "n", 10))), emptyMap())
            assertTrue(merged.notes.isEmpty())
            assertEquals(listOf(SyncTomb("notes", "n", 10)), merged.tombstones)
        }
    }

    @Test fun tombstonesKeepTheNewestTime() {
        val merged = merge(a(tombs = listOf(SyncTomb("notes", "n", 3))), b(tombs = listOf(SyncTomb("notes", "n", 7))), emptyMap())
        assertEquals(listOf(SyncTomb("notes", "n", 7)), merged.tombstones)
    }

    @Test fun tombstonesOfDifferentTablesDontMix() {
        val merged = merge(a(listOf(note("n", 1))), b(tombs = listOf(SyncTomb("tasks", "n", 7))), emptyMap())
        assertEquals(1, merged.notes.size)
    }

    @Test fun aPageEditedAfterItsScrollWasDeletedBringsTheScrollBack() {
        val parent = note("p", 5); val page = note("c", 20, parent = "p")
        val tombs = listOf(SyncTomb("notes", "p", 10), SyncTomb("notes", "c", 10))
        val onDeleter = merge(a(tombs = tombs), b(listOf(parent, page)), emptyMap())
        val onEditor = merge(b(listOf(parent, page)).copy(deviceId = "a"), a(tombs = tombs).copy(deviceId = "b"), emptyMap())
        assertEquals(listOf(note("c", 20, parent = "p"), parent.copy(syncAt = 11)), onDeleter.notes)
        assertEquals(onDeleter.notes, onEditor.notes)
        assertTrue(onDeleter.tombstones.isEmpty())
    }

    @Test fun aLiveIdeaBringsItsDeletedLobeBack() {
        val l = lobe("L", 5); val i = idea("i", 20)
        val tombs = listOf(SyncTomb("lobes", "L", 10), SyncTomb("ideas", "i", 10))
        val merged = merge(a(tombs = tombs), b(lobes = listOf(l), ideas = listOf(i)), emptyMap())
        assertEquals(listOf(l.copy(syncAt = 11)), merged.lobes)
        assertEquals(listOf(i), merged.ideas)
    }

    @Test fun anIdeaWhoseLobeExistsNowhereIsRefused() {
        assertThrows(SyncException::class.java) { merge(a(), b(ideas = listOf(idea("i", 20, lobe = "ghost"))), emptyMap()) }
    }

    @Test fun aPageWhoseParentExistsNowhereStaysReachable() {
        assertNull(merge(a(), b(listOf(note("c", 1, parent = "ghost"))), emptyMap()).notes.single().parentUid)
    }

    @Test fun mergingTwiceChangesNothing() {
        val seen = mapOf("notes:n" to 1L)
        val mine = a(listOf(note("n", 10, "mine"), note("other", 3)), tombs = listOf(SyncTomb("notes", "x", 4)))
        val theirs = b(listOf(note("n", 20, "theirs"), note("y", 3)), tombs = listOf(SyncTomb("notes", "z", 4)))
        val once = merge(mine, theirs, seen)
        assertEquals(once, merge(once, theirs, seenAfter(seen, theirs)))
        assertEquals(once, merge(once, theirs, seen)) // even with the cursor lost, the copy already exists
    }

    @Test fun seenRemembersThePeersVersionNotOurs() {
        val seen = seenAfter(mapOf("notes:n" to 1), b(listOf(note("n", 5))))
        assertEquals(5L, seen["notes:n"])
        // our own edit stays "changed" against it, so a later peer edit is still a conflict
        assertEquals(2, merge(a(listOf(note("n", 9, "mine"))), b(listOf(note("n", 12, "theirs"))), seen).notes.size)
    }

    @Test fun lobesOnTheSameSlotSpreadOutTheSameWayOnBothDevices() {
        val mine = a(lobes = listOf(lobe("x", 5), SyncLobe("w", 4, "L", 0, 1))); val theirs = b(lobes = listOf(lobe("y", 6), SyncLobe("z", 7, "L", 0, 1)))
        val merged = merge(mine, theirs, emptyMap())
        assertEquals(4, merged.lobes.map { it.slot }.toSet().size)
        assertEquals(merged.lobes, merge(theirs.copy(deviceId = "a"), mine.copy(deviceId = "b"), emptyMap()).lobes)
        assertEquals(merged, merge(merged, theirs, emptyMap())) // settled: nothing moves again
    }

    @Test fun newestGroupNameWinsAndNeverMakesACopy() {
        val l = lobe("L", 1)
        val merged = merge(a(lobes = listOf(l), branches = listOf(branch("g", 10, name = "mine"))), b(lobes = listOf(l), branches = listOf(branch("g", 20, name = "theirs"))), emptyMap())
        assertEquals(listOf("theirs"), merged.branches.map { it.name })
    }

    @Test fun aDeletedGroupStaysDeletedAndAnOlderCopyDoesNotReviveIt() {
        val l = lobe("L", 1)
        val merged = merge(a(lobes = listOf(l), tombs = listOf(SyncTomb("branches", "g", 10))), b(lobes = listOf(l), branches = listOf(branch("g", 5))), emptyMap())
        assertTrue(merged.branches.isEmpty())
        assertEquals(listOf(SyncTomb("branches", "g", 10)), merged.tombstones)
    }

    @Test fun aGroupRenamedAfterItsDeleteComesBack() {
        val l = lobe("L", 1)
        val merged = merge(a(lobes = listOf(l), tombs = listOf(SyncTomb("branches", "g", 10))), b(lobes = listOf(l), branches = listOf(branch("g", 11))), emptyMap())
        assertEquals(listOf("g"), merged.branches.map { it.uid })
        assertTrue(merged.tombstones.isEmpty())
    }

    @Test fun anIdeaInADeletedGroupLandsOnTheLobeTheSameOnBothDevices() {
        val l = lobe("L", 1); val i = idea("i", 20).copy(branchUid = "g", link = "x", done = true)
        val deleter = a(lobes = listOf(l), tombs = listOf(SyncTomb("branches", "g", 10)))
        val mover = b(lobes = listOf(l), branches = listOf(branch("g", 5)), ideas = listOf(i))
        val here = merge(deleter, mover, emptyMap())
        val there = merge(mover.copy(deviceId = "a"), deleter.copy(deviceId = "b"), emptyMap())
        val landed = i.copy(branchUid = null, syncAt = 21)
        assertEquals(listOf(landed), here.ideas)
        assertEquals(here.ideas, there.ideas)
        assertEquals(here.branches, there.branches)
        assertEquals(here.ideas, merge(here, mover, emptyMap()).ideas) // settled
    }

    @Test fun aLiveGroupBringsItsDeletedLobeBack() {
        val l = lobe("L", 5); val g = branch("g", 20)
        val tombs = listOf(SyncTomb("lobes", "L", 10), SyncTomb("branches", "g", 10))
        val merged = merge(a(tombs = tombs), b(lobes = listOf(l), branches = listOf(g)), emptyMap())
        assertEquals(listOf(l.copy(syncAt = 11)), merged.lobes)
        assertEquals(listOf(g), merged.branches)
    }

    @Test fun aGroupWhoseLobeExistsNowhereIsRefused() {
        assertThrows(SyncException::class.java) { merge(a(), b(branches = listOf(branch("g", 20, lobe = "ghost"))), emptyMap()) }
    }

    @Test fun aConflictCopyKeepsItsGroupAndLink() {
        val l = lobe("L", 1); val g = branch("g", 1)
        val mine = idea("i", 10, title = "mine").copy(branchUid = "g", link = "x", done = true)
        val merged = merge(a(lobes = listOf(l), branches = listOf(g), ideas = listOf(mine)), b(lobes = listOf(l), branches = listOf(g), ideas = listOf(idea("i", 20, title = "theirs"))), mapOf("ideas:i" to 1))
        assertEquals(mine.copy(uid = "i~10", title = "mine (other copy)"), merged.ideas.single { it.uid != "i" })
    }

    @Test fun onlyTheLinkDifferingIsAConflictButOnlyDoneOrGroupIsNot() {
        val l = lobe("L", 1); val g = branch("g", 1)
        val seen = mapOf("ideas:i" to 1L)
        fun run(x: SyncIdea, y: SyncIdea) = merge(a(lobes = listOf(l), branches = listOf(g), ideas = listOf(x)), b(lobes = listOf(l), branches = listOf(g), ideas = listOf(y)), seen).ideas.size
        assertEquals(2, run(idea("i", 10).copy(link = "x"), idea("i", 20).copy(link = "y")))
        assertEquals(1, run(idea("i", 10).copy(done = true), idea("i", 20)))
        assertEquals(1, run(idea("i", 10).copy(branchUid = "g"), idea("i", 20)))
    }

    @Test fun damagedSnapshotsAreRefused() {
        assertThrows(SyncException::class.java) { b(listOf(note("n", 0))).validate() }
        assertThrows(SyncException::class.java) { b(listOf(note("", 1))).validate() }
        assertThrows(SyncException::class.java) { b(tombs = listOf(SyncTomb("posts", "n", 1))).validate() }
        assertThrows(SyncException::class.java) { b().copy(v = 99).validate() }
    }
}
