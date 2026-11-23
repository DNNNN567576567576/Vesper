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

    private fun goal(uid: String, at: Long, name: String = "Panduri") = SyncGoal(uid, at, name, 0, 1, at)
    private fun step(uid: String, at: Long, goal: String = "G", title: String = "s", done: Boolean = false, day: Long? = null) = SyncStep(uid, at, goal, title, "", done, day, 0, 1, at)

    @Test fun goalsAndStepsMergeNewestWins() {
        val mine = Snapshot(deviceId = "a", goals = listOf(goal("G", 1)), steps = listOf(step("s", 5, done = true)))
        val theirs = Snapshot(deviceId = "b", goals = listOf(goal("G", 2, "Panduri lessons")), steps = listOf(step("s", 3, title = "older"), step("t", 4, day = 20000)))
        val m = merge(mine, theirs, emptyMap())
        assertEquals(listOf(goal("G", 2, "Panduri lessons")), m.goals)
        assertEquals(listOf(step("s", 5, done = true), step("t", 4, day = 20000)), m.steps)
    }

    @Test fun aDeletedGoalTakesItsStepsAlong() {
        val g = goal("G", 1)
        val mine = Snapshot(deviceId = "a", goals = listOf(g), steps = listOf(step("s", 2)))
        val theirs = Snapshot(deviceId = "b", tombstones = listOf(SyncTomb("goals", "G", 10), SyncTomb("steps", "s", 10)))
        val m = merge(mine, theirs, emptyMap())
        assertTrue(m.goals.isEmpty() && m.steps.isEmpty())
        assertEquals(setOf("goals:G", "steps:s"), m.tombstones.map { "${it.tbl}:${it.uid}" }.toSet())
    }

    @Test fun aStepEditedAfterItsGoalWasDeletedBringsTheGoalBack() {
        val mine = Snapshot(deviceId = "a", goals = listOf(goal("G", 1)), steps = listOf(step("s", 20, title = "edited later")))
        val theirs = Snapshot(deviceId = "b", tombstones = listOf(SyncTomb("goals", "G", 10)))
        val m = merge(mine, theirs, emptyMap())
        assertEquals(listOf("G"), m.goals.map { it.uid })
        assertTrue(m.goals.single().syncAt > 10)
        assertEquals("edited later", m.steps.single().title)
    }

    /** A version 2 file has no goals or steps: merging it keeps ours as they are. */
    @Test fun aVersion2SnapshotLeavesGoalsAndStepsAlone() {
        val mine = Snapshot(deviceId = "a", lobes = listOf(lobe("L", 1)), goals = listOf(goal("G", 1)), steps = listOf(step("s", 2)))
        val v2 = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(Snapshot.serializer(), """{"v":2,"deviceId":"b","lobes":[{"uid":"L","syncAt":5,"name":"Work","colorIndex":0,"slot":0}]}""")
        v2.validate()
        val m = merge(mine, v2, emptyMap())
        assertEquals(mine.goals, m.goals)
        assertEquals(mine.steps, m.steps)
        assertEquals("Work", m.lobes.single().name)
    }

    @Test fun learnTombstonesAreValid() {
        b(tombs = listOf(SyncTomb("goals", "g", 1), SyncTomb("steps", "s", 1))).validate()
        assertThrows(SyncException::class.java) { Snapshot(deviceId = "b", steps = listOf(step("s", 0))).validate() }
    }

    // ---- 2.7.9 the Bible canvas: cards and links ----

    private fun card(uid: String, at: Long, name: String = "Isaac", note: String = "", x: Float = 0f, y: Float = 0f, locked: Boolean = false) = SyncBibleCard(uid, at, name, note, "person", x, y, locked)
    private fun link(uid: String, at: Long, from: String, to: String, label: String = "son") = SyncBibleLink(uid, at, from, to, label)
    private fun bible(id: String, cards: List<SyncBibleCard> = emptyList(), links: List<SyncBibleLink> = emptyList(), tombs: List<SyncTomb> = emptyList()) =
        Snapshot(v = SNAPSHOT_VERSION, deviceId = id, bibleCards = cards, bibleLinks = links, tombstones = tombs)

    @Test fun cardsAndLinksUnionAndNewestWins() {
        val m = merge(bible("a", listOf(card("i", 1)), listOf(link("l", 2, "i", "x"))), bible("b", listOf(card("x", 1, "Abraham"), card("i", 5, "Isaac!"))), mapOf("bible_cards:i" to 1))
        assertEquals(listOf("Isaac!", "Abraham"), m.bibleCards.map { it.name })
        assertEquals(listOf("l"), m.bibleLinks.map { it.uid })
    }

    /** Moving (and locking) is no text conflict: both sides moved, the later one wins and no copy is made. */
    @Test fun bothMovedIsNoConflict() {
        val seen = mapOf("bible_cards:i" to 1L)
        val m = merge(bible("a", listOf(card("i", 10, x = 5f, y = 5f))), bible("b", listOf(card("i", 20, x = 90f, y = -3f, locked = true))), seen)
        assertEquals(listOf(card("i", 20, x = 90f, y = -3f, locked = true)), m.bibleCards)
        val back = merge(bible("b", listOf(card("i", 20, x = 90f, y = -3f, locked = true))), bible("a", listOf(card("i", 10, x = 5f, y = 5f))), seen)
        assertEquals(m.bibleCards, back.bibleCards)
    }

    @Test fun bothRenamedKeepsTheLoserAsACopyBesideIt() {
        val seen = mapOf("bible_cards:i" to 1L)
        val mine = bible("a", listOf(card("i", 10, "Isaac son of Abraham", x = 40f, y = 40f)))
        val theirs = bible("b", listOf(card("i", 20, "Yitzhak", x = 40f, y = 40f)))
        val m = merge(mine, theirs, seen)
        assertEquals(setOf("Yitzhak", "Isaac son of Abraham (other copy)"), m.bibleCards.map { it.name }.toSet())
        val copy = m.bibleCards.single { it.uid == "i~10" }
        assertEquals(64f to 64f, copy.x to copy.y) // beside its card, not under it
        assertEquals(m.bibleCards, merge(theirs.copy(deviceId = "a"), mine.copy(deviceId = "b"), seen).bibleCards) // the same copy on both devices
    }

    @Test fun aNoteEditedOnBothSidesIsAConflictToo() {
        val m = merge(bible("a", listOf(card("i", 10, note = "mine"))), bible("b", listOf(card("i", 20, note = "theirs"))), mapOf("bible_cards:i" to 1L))
        assertEquals(setOf("mine", "theirs"), m.bibleCards.map { it.note }.toSet())
    }

    /** A move on one side, a rename on the other: the later move wins, the rename is kept as a copy rather than lost. */
    @Test fun aRenameNeverGetsLostToAMove() {
        val m = merge(bible("a", listOf(card("i", 10, "Yitzhak"))), bible("b", listOf(card("i", 20, x = 50f))), mapOf("bible_cards:i" to 1L))
        assertTrue(m.bibleCards.any { it.name.startsWith("Yitzhak") })
        assertEquals(50f, m.bibleCards.single { it.uid == "i" }.x)
    }

    @Test fun anEchoOfOurOwnOlderCardIsNoConflict() {
        val sent = mapOf("bible_cards:i" to listOf(10L))
        val m = merge(bible("a", listOf(card("i", 12, "Isaac."))), bible("b", listOf(card("i", 10, "Isaac"))), mapOf("bible_cards:i" to 1L), sent = sent)
        assertEquals(listOf("Isaac."), m.bibleCards.map { it.name })
        assertEquals(listOf(10L), sentAfter(emptyMap(), emptyMap(), bible("a", listOf(card("i", 10))))["bible_cards:i"])
        assertEquals(7L, seenAfter(emptyMap(), bible("b", listOf(card("i", 7))))["bible_cards:i"])
    }

    @Test fun linksAreLastWriterWinsWithNoCopy() {
        val cards = listOf(card("i", 1), card("x", 1))
        val m = merge(bible("a", cards, listOf(link("l", 10, "i", "x", "son"))), bible("b", cards, listOf(link("l", 20, "i", "x", "heir"))), emptyMap())
        assertEquals(listOf(link("l", 20, "i", "x", "heir")), m.bibleLinks)
    }

    /** The other device deleted Abraham (and its links with him); our newer link to him must not dangle: it goes too. */
    @Test fun aLinkToACardDeletedElsewhereIsDropped() {
        val mine = bible("a", listOf(card("i", 1), card("x", 1, "Abraham")), listOf(link("l", 50, "i", "x"), link("keep", 2, "x", "i")))
        val theirs = bible("b", listOf(card("i", 1)), tombs = listOf(SyncTomb("bible_cards", "x", 10)))
        val m = merge(mine, theirs, emptyMap())
        assertEquals(listOf("i"), m.bibleCards.map { it.uid })
        assertTrue(m.bibleLinks.isEmpty())
        assertEquals(m.bibleLinks, merge(theirs.copy(deviceId = "a"), mine.copy(deviceId = "b"), emptyMap()).bibleLinks)
        assertEquals(m, merge(m, theirs, emptyMap())) // and stays settled
    }

    @Test fun aLinkToACardThatExistsNowhereIsDroppedNotRefused() {
        val m = merge(bible("a", listOf(card("i", 1))), bible("b", links = listOf(link("l", 3, "i", "ghost"), link("l2", 3, "ghost", "i"))), emptyMap())
        assertTrue(m.bibleLinks.isEmpty())
        assertEquals(listOf("i"), m.bibleCards.map { it.uid })
    }

    @Test fun aCardEditedAfterItsDeleteComesBackWithoutItsDeletedLinks() {
        val mine = bible("a", listOf(card("x", 30, "Abraham", x = 9f), card("i", 1)), listOf(link("l", 5, "i", "x")))
        val theirs = bible("b", listOf(card("i", 1)), tombs = listOf(SyncTomb("bible_cards", "x", 10), SyncTomb("bible_links", "l", 10)))
        val m = merge(mine, theirs, emptyMap())
        assertEquals(setOf("x", "i"), m.bibleCards.map { it.uid }.toSet())
        assertTrue(m.bibleLinks.isEmpty())
    }

    /** A version 3 file has no Bible: merging it keeps ours as they are. */
    @Test fun aVersion3SnapshotLeavesTheBibleAlone() {
        val mine = bible("a", listOf(card("i", 1), card("x", 1)), listOf(link("l", 2, "i", "x"))).copy(lobes = listOf(lobe("L", 1)))
        val v3 = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(Snapshot.serializer(), """{"v":3,"deviceId":"b","lobes":[{"uid":"L","syncAt":5,"name":"Work","colorIndex":0,"slot":0}],"goals":[{"uid":"G","syncAt":3,"name":"Panduri","position":0,"createdAt":1,"updatedAt":1}]}""")
        v3.validate()
        val m = merge(mine, v3, emptyMap())
        assertEquals(mine.bibleCards, m.bibleCards)
        assertEquals(mine.bibleLinks, m.bibleLinks)
        assertEquals("Work", m.lobes.single().name)
        assertEquals("Panduri", m.goals.single().name)
    }

    @Test fun aVersion4FileRoundTripsAndOlderBuildsStopAtItsNumber() {
        val s = bible("a", listOf(card("i", 1, note = "laughter", locked = true)), listOf(link("l", 2, "i", "i")))
        val text = kotlinx.serialization.json.Json.encodeToString(Snapshot.serializer(), s)
        assertTrue(text.contains("\"v\":4"))
        assertEquals(s, kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(Snapshot.serializer(), text))
        assertEquals(4, SNAPSHOT_VERSION)
        assertThrows(SyncException::class.java) { s.copy(v = SNAPSHOT_VERSION + 1).validate() } // how a v3 build met our v4
    }

    @Test fun bibleTombstonesAreValidAndDamagedRowsAreNot() {
        b(tombs = listOf(SyncTomb("bible_cards", "c", 1), SyncTomb("bible_links", "l", 1))).validate()
        assertThrows(SyncException::class.java) { bible("b", listOf(card("", 1))).validate() }
        assertThrows(SyncException::class.java) { bible("b", links = listOf(link("l", 0, "a", "b"))).validate() }
    }
}
