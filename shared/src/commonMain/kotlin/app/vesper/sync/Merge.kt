package app.vesper.sync

import app.vesper.data.SYNCED_TABLES
import kotlinx.serialization.Serializable

/** A problem to show the user as is, in plain words. */
open class SyncException(message: String) : Exception(message)

/** The user has to sign in to Google (again). */
class SignInNeeded(message: String = "Sign in to Google again.") : SyncException(message)

interface SyncRow {
    val uid: String
    val syncAt: Long
}

// Wire records: references are uids, never local ids, which differ per device.
@Serializable data class SyncNote(override val uid: String, override val syncAt: Long, val title: String, val content: String, val updatedAt: Long, val position: Int, val parentUid: String? = null) : SyncRow
@Serializable data class SyncTask(override val uid: String, override val syncAt: Long, val text: String, val snuffedAt: Long? = null) : SyncRow
@Serializable data class SyncLobe(override val uid: String, override val syncAt: Long, val name: String, val colorIndex: Int, val slot: Int) : SyncRow
@Serializable data class SyncIdea(override val uid: String, override val syncAt: Long, val lobeUid: String, val title: String, val body: String, val noteUid: String? = null, val updatedAt: Long) : SyncRow
@Serializable data class SyncPlan(override val uid: String, override val syncAt: Long, val day: Long, val text: String, val done: Boolean, val createdAt: Long) : SyncRow
@Serializable data class SyncTomb(val tbl: String, val uid: String, val deletedAt: Long)

const val SNAPSHOT_VERSION = 1

/** One device's full state of the synced tables. Every list is sorted, so equal state means equal bytes. */
@Serializable
data class Snapshot(
    val v: Int = SNAPSHOT_VERSION,
    val deviceId: String,
    val notes: List<SyncNote> = emptyList(),
    val tasks: List<SyncTask> = emptyList(),
    val lobes: List<SyncLobe> = emptyList(),
    val ideas: List<SyncIdea> = emptyList(),
    val plans: List<SyncPlan> = emptyList(),
    val tombstones: List<SyncTomb> = emptyList(),
)

/** Refuses a snapshot that could not have come from a healthy device, before anything is written. */
fun Snapshot.validate() {
    val rows = notes + tasks + lobes + ideas + plans
    if (v != SNAPSHOT_VERSION) throw SyncException("The other device runs a different Vesper version. Update both.")
    if (rows.any { it.uid.isEmpty() || it.syncAt <= 0 } || tombstones.any { it.uid.isEmpty() || it.tbl !in SYNCED_TABLES }) {
        throw SyncException("The copy on Drive is damaged. Nothing was changed.")
    }
}

private typealias Tombs = Map<Pair<String, String>, Long>

private class Resolved<T : SyncRow>(val live: MutableMap<String, T>, val dead: Map<String, T>) {
    fun rows() = live.values.sortedBy { it.uid }
}

/** Last writer wins by syncAt; an exact tie goes to the larger device id, which both devices compute alike. */
private fun <T : SyncRow> newest(l: T, r: T, remoteWinsTie: Boolean) = when {
    r.syncAt != l.syncAt -> if (r.syncAt > l.syncAt) r else l
    remoteWinsTie -> r
    else -> l
}

/** Edit beats delete: a row survives its tombstone only when it is strictly newer. */
private fun <T : SyncRow> resolve(tbl: String, local: List<T>, remote: List<T>, tombs: Tombs, remoteWinsTie: Boolean): Resolved<T> {
    val l = local.associateBy { it.uid }
    val r = remote.associateBy { it.uid }
    val live = mutableMapOf<String, T>()
    val dead = mutableMapOf<String, T>()
    for (uid in l.keys + r.keys) {
        val a = l[uid]
        val b = r[uid]
        val winner = if (a != null && b != null) newest(a, b, remoteWinsTie) else a ?: b!!
        val deletedAt = tombs[tbl to uid]
        if (deletedAt != null && deletedAt >= winner.syncAt) dead[uid] = winner else live[uid] = winner
    }
    return Resolved(live, dead)
}

/**
 * The losers of real conflicts, as new rows. Real means both sides moved away from the version last [seen] from a
 * peer and the text differs. The copy's uid and stamp come from the loser, so both devices build the same row and a
 * copy the user deleted stays deleted, because its stamp never rises past that tombstone.
 */
private fun <T : SyncRow> conflictCopies(tbl: String, local: List<T>, remote: List<T>, seen: Map<String, Long>, remoteWinsTie: Boolean, text: (T) -> Any, copy: (T, String) -> T): List<T> {
    val mine = local.associateBy { it.uid }
    return remote.mapNotNull { r ->
        val l = mine[r.uid] ?: return@mapNotNull null
        val base = seen["$tbl:${r.uid}"]
        if (l.syncAt == base || r.syncAt == base || text(l) == text(r)) return@mapNotNull null
        val loser = if (newest(l, r, remoteWinsTie) === r) l else r
        copy(loser, "${loser.uid}~${loser.syncAt}")
    }
}

private fun copyTitle(title: String) = "$title (other copy)".trim()

/**
 * Pure merge of [remote] into [local]; the result is what [local] must become, equal to [local] when there is nothing
 * to do. [seen] holds the syncAt last seen from a peer per "notes:uid" / "ideas:uid", see [seenAfter].
 */
fun merge(local: Snapshot, remote: Snapshot, seen: Map<String, Long>): Snapshot {
    val tie = remote.deviceId > local.deviceId
    val tombs: Tombs = (local.tombstones + remote.tombstones).groupBy({ it.tbl to it.uid }, { it.deletedAt }).mapValues { it.value.max() }

    val noteCopies = conflictCopies("notes", local.notes, remote.notes, seen, tie, { it.title to it.content }) { n, uid -> n.copy(uid = uid, title = copyTitle(n.title)) }
    val ideaCopies = conflictCopies("ideas", local.ideas, remote.ideas, seen, tie, { it.title to it.body }) { i, uid -> i.copy(uid = uid, title = copyTitle(i.title)) }

    // associateBy keeps the last: a copy the peer already holds (maybe edited since) beats the one just built.
    val notes = resolve("notes", local.notes, (noteCopies + remote.notes).associateBy { it.uid }.values.toList(), tombs, tie)
    val ideas = resolve("ideas", local.ideas, (ideaCopies + remote.ideas).associateBy { it.uid }.values.toList(), tombs, tie)
    val tasks = resolve("tasks", local.tasks, remote.tasks, tombs, tie)
    val lobes = resolve("lobes", local.lobes, remote.lobes, tombs, tie)
    val plans = resolve("plans", local.plans, remote.plans, tombs, tie)

    // A surviving child keeps its parent: the parent is stamped just past its tombstone, the same on both devices.
    for (uid in ideas.live.values.map { it.lobeUid }.toSet() - lobes.live.keys) {
        val lobe = lobes.dead[uid] ?: throw SyncException("The copy on Drive is damaged. Nothing was changed.")
        lobes.live[uid] = lobe.copy(syncAt = maxOf(lobe.syncAt, tombs.getValue("lobes" to uid) + 1))
    }
    while (true) {
        val missing = notes.live.values.mapNotNull { it.parentUid }.filter { it !in notes.live && it in notes.dead }.toSet()
        if (missing.isEmpty()) break
        for (uid in missing) notes.dead.getValue(uid).let { notes.live[uid] = it.copy(syncAt = maxOf(it.syncAt, tombs.getValue("notes" to uid) + 1)) }
    }
    // A parent nowhere to be found: keep the page reachable at the top level rather than invisible.
    notes.live.replaceAll { _, n -> if (n.parentUid != null && n.parentUid !in notes.live) n.copy(parentUid = null) else n }

    // Each device fills its own first free slot, and two lobes on one slot would hide one on the map. Same result on both devices.
    val taken = mutableSetOf<Int>()
    for (l in lobes.live.values.sortedBy { it.uid }) {
        if (taken.add(l.slot)) continue
        val slot = generateSequence(l.slot + 1) { it + 1 }.first { s -> lobes.live.values.none { it.slot == s } }
        taken += slot
        lobes.live[l.uid] = l.copy(slot = slot, syncAt = l.syncAt + 1)
    }

    val live = mapOf("notes" to notes.live.keys, "tasks" to tasks.live.keys, "lobes" to lobes.live.keys, "ideas" to ideas.live.keys, "plans" to plans.live.keys)
    return local.copy(
        notes = notes.rows(), tasks = tasks.rows(), lobes = lobes.rows(), ideas = ideas.rows(), plans = plans.rows(),
        // A live row's tombstone goes away (revive); the rest are kept forever so a long-offline device can't bring rows back.
        tombstones = tombs.filterKeys { (t, u) -> u !in live.getValue(t) }.map { (k, at) -> SyncTomb(k.first, k.second, at) }.sortedWith(compareBy({ it.tbl }, { it.uid })),
    )
}

/** [seen] after merging [remote]: remembers the peer's versions, never our own, so an unsent local edit still counts as changed. */
fun seenAfter(seen: Map<String, Long>, remote: Snapshot): Map<String, Long> =
    seen + remote.notes.map { "notes:${it.uid}" to it.syncAt } + remote.ideas.map { "ideas:${it.uid}" to it.syncAt }
