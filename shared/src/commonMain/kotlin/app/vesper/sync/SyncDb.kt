package app.vesper.sync

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import app.vesper.data.AppDatabase
import app.vesper.data.Idea
import app.vesper.data.Lobe
import app.vesper.data.Note
import app.vesper.data.Plan
import app.vesper.data.SyncDao
import app.vesper.data.SyncTombstone
import app.vesper.data.Task

private suspend fun SyncDao.read(deviceId: String): Snapshot {
    val notes = notes()
    val lobes = lobes()
    val noteUid = notes.associate { it.id to it.uid }
    val lobeUid = lobes.associate { it.id to it.uid }
    return Snapshot(
        deviceId = deviceId,
        notes = notes.map { SyncNote(it.uid, it.syncAt, it.title, it.content, it.updatedAt, it.position, noteUid[it.parentId]) }.sortedBy { it.uid },
        tasks = tasks().map { SyncTask(it.uid, it.syncAt, it.text, it.snuffedAt) }.sortedBy { it.uid },
        lobes = lobes.map { SyncLobe(it.uid, it.syncAt, it.name, it.colorIndex, it.slot) }.sortedBy { it.uid },
        ideas = ideas().map { SyncIdea(it.uid, it.syncAt, lobeUid.getValue(it.lobeId), it.title, it.body, noteUid[it.noteId], it.updatedAt) }.sortedBy { it.uid },
        plans = plans().map { SyncPlan(it.uid, it.syncAt, it.day, it.text, it.done, it.createdAt) }.sortedBy { it.uid },
        tombstones = tombstones().map { SyncTomb(it.tbl, it.uid, it.deletedAt) }.sortedWith(compareBy({ it.tbl }, { it.uid })),
    )
}

/** One consistent view of everything synced. */
suspend fun AppDatabase.exportSnapshot(deviceId: String): Snapshot =
    useWriterConnection { c -> c.immediateTransaction { syncDao().read(deviceId) } }

/** Merges [remote] in one transaction, so any failure leaves the DB untouched. Returns whether anything changed. */
suspend fun AppDatabase.mergeIn(remote: Snapshot, deviceId: String, seen: Map<String, Long>): Boolean =
    useWriterConnection { c ->
        c.immediateTransaction {
            val dao = syncDao()
            val local = dao.read(deviceId)
            val merged = merge(local, remote, seen)
            if (merged != local) dao.write(local, merged)
            merged != local
        }
    }

private fun <T : SyncRow> gone(old: List<T>, new: List<T>) = old.map { it.uid } - new.map { it.uid }.toSet()
private fun <T : SyncRow> changed(old: List<T>, new: List<T>) = new - old.toSet()

/**
 * Remote rows carry an explicit syncAt above the local one, so the update trigger stays quiet and nothing echoes.
 * Only an exact tie won by the peer is re-stamped by the trigger; that one row goes back once and then settles.
 */
private suspend fun SyncDao.write(local: Snapshot, merged: Snapshot) {
    // Children first; merge() never deletes a parent of a live row, so no cascade removes anything it didn't choose.
    gone(local.ideas, merged.ideas).forEach { deleteIdea(it) }
    gone(local.notes, merged.notes).forEach { deleteNote(it) }
    gone(local.tasks, merged.tasks).forEach { deleteTask(it) }
    gone(local.plans, merged.plans).forEach { deletePlan(it) }
    gone(local.lobes, merged.lobes).forEach { deleteLobe(it) }

    val lobeIds = lobes().associate { it.uid to it.id }.toMutableMap()
    for (l in changed(local.lobes, merged.lobes)) {
        val row = Lobe(lobeIds[l.uid] ?: 0, l.name, l.colorIndex, l.slot, l.uid, l.syncAt)
        if (row.id == 0L) lobeIds[l.uid] = insert(row) else update(row)
    }

    val noteIds = notes().associate { it.uid to it.id }.toMutableMap()
    var rest = changed(local.notes, merged.notes)
    while (rest.isNotEmpty()) {
        // A new parent gets its id on insert, so it goes first. A cycle can't come from the UI; it lands at the top level.
        val (ready, blocked) = rest.partition { it.parentUid == null || it.parentUid in noteIds }
        for (n in ready.ifEmpty { rest }) {
            val row = Note(noteIds[n.uid] ?: 0, n.title, n.content, n.updatedAt, n.position, noteIds[n.parentUid], n.uid, n.syncAt)
            if (row.id == 0L) noteIds[n.uid] = insert(row) else update(row)
        }
        rest = if (ready.isEmpty()) emptyList() else blocked
    }

    val ideaIds = ideas().associate { it.uid to it.id }
    for (i in changed(local.ideas, merged.ideas)) {
        val row = Idea(ideaIds[i.uid] ?: 0, lobeIds.getValue(i.lobeUid), i.title, i.body, noteIds[i.noteUid], i.updatedAt, i.uid, i.syncAt)
        if (row.id == 0L) insert(row) else update(row)
    }
    val taskIds = tasks().associate { it.uid to it.id }
    for (t in changed(local.tasks, merged.tasks)) {
        val row = Task(taskIds[t.uid] ?: 0, t.text, t.snuffedAt, t.uid, t.syncAt)
        if (row.id == 0L) insert(row) else update(row)
    }
    val planIds = plans().associate { it.uid to it.id }
    for (p in changed(local.plans, merged.plans)) {
        val row = Plan(planIds[p.uid] ?: 0, p.day, p.text, p.done, p.createdAt, p.uid, p.syncAt)
        if (row.id == 0L) insert(row) else update(row)
    }

    // Last: this replaces the now-stamped tombstones the delete triggers just wrote, and clears those of revived rows.
    clearTombstones()
    insert(merged.tombstones.map { SyncTombstone(it.tbl, it.uid, it.deletedAt) })
}
