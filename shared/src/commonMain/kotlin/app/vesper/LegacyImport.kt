package app.vesper

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import app.vesper.data.AppDatabase
import app.vesper.data.Idea
import app.vesper.data.Lobe

/** Settings: set once the legacy import has run to the end on this device. */
const val KEY_LEGACY_IMPORT = "legacyImportDone"

internal fun legacyNoteUid(uid: String) = "legacy-note-$uid"
internal fun legacyTaskUid(uid: String) = "legacy-task-$uid"

/**
 * One-time move of the pre-3.0 data (Scrolls = `notes`, Flames = `tasks`) into the Brain. Every note becomes an idea in the main
 * brain (the nameless sub brain), every still-lit task an idea in "Tasks"; snuffed tasks stay history and are not imported. Ideas
 * get uids derived from the source row's uid, so two devices importing the same data build the same rows and sync merges them as
 * one. A row is skipped when that uid already exists as an idea or has a tombstone (burned on any device earlier), which is why
 * those checks stay even though [KEY_LEGACY_IMPORT] stops later starts from rescanning. The old rows are never touched. Notes
 * include those [writeIdea] parks when its sub brain was gone. Idempotent; returns how many ideas it made.
 */
suspend fun importLegacy(db: AppDatabase, settings: Settings): Int {
    if (settings.getBoolean(KEY_LEGACY_IMPORT, false)) return 0
    val sync = db.syncDao()
    val dao = db.brainDao()
    val made = db.useWriterConnection { w ->
        w.immediateTransaction {
            val notes = sync.notes()
            val tasks = sync.tasks().filter { it.snuffedAt == null }
            if (notes.isEmpty() && tasks.isEmpty()) return@immediateTransaction 0
            val have = sync.ideas().mapTo(HashSet()) { it.uid }
            val burned = sync.tombstones().filter { it.tbl == "ideas" }.mapTo(HashSet()) { it.uid }
            val lobes = sync.lobes().toMutableList()
            suspend fun lobeNamed(name: String): Long {
                lobes.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }?.let { return it.id }
                val slot = generateSequence(0) { it + 1 }.first { s -> lobes.none { it.slot == s } }
                val lobe = Lobe(name = name, colorIndex = slot, slot = slot)
                return dao.insertLobe(lobe).also { lobes += lobe.copy(id = it) }
            }
            var n = 0
            suspend fun add(uid: String, lobe: String, title: String, body: String, at: Long) {
                if (uid in have || uid in burned || (title.isBlank() && body.isBlank())) return
                dao.insertIdea(Idea(lobeId = lobeNamed(lobe), title = title.trim(), body = body.trim(), updatedAt = at, uid = uid))
                n++
            }
            notes.forEach { add(legacyNoteUid(it.uid), "", it.title, it.content, it.updatedAt) }
            val now = System.currentTimeMillis()
            tasks.forEach { add(legacyTaskUid(it.uid), "Tasks", it.text, "", now) }
            n
        }
    }
    settings.putBoolean(KEY_LEGACY_IMPORT, true)
    return made
}
