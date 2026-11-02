package app.vesper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.app.Application
import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.snapshotFlow
import app.vesper.data.Note
import app.vesper.data.Post
import app.vesper.data.Task
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val AndroidViewModel.notebook get() = getApplication<NotebookApp>()

enum class NoteSort { EDITED, ADDED, MANUAL }

private const val KEY_NOTE_SORT = "noteSort"
private const val KEY_TASK_NEWEST = "taskNewestFirst"

private val AndroidViewModel.prefs get() = notebook.getSharedPreferences(NotebookApp.PREFS, Context.MODE_PRIVATE)

/** `null` = the first database emission has not arrived yet. */
class NotesViewModel(app: Application) : AndroidViewModel(app) {
    val sort = MutableStateFlow(NoteSort.entries.firstOrNull { it.name == prefs.getString(KEY_NOTE_SORT, null) } ?: NoteSort.EDITED)

    // The DAO delivers "recently edited" order; the other modes re-sort it here.
    private val all = notebook.db.noteDao().observeAll()

    // Only top-level scrolls are listed; pages live inside their main scroll.
    val notes: StateFlow<List<Note>?> = combine(all, sort) { all, mode ->
        val list = all.filter { it.parentId == null }
        when (mode) {
            NoteSort.EDITED -> list
            NoteSort.ADDED -> list.sortedByDescending { it.id }
            NoteSort.MANUAL -> list.sortedWith(compareBy<Note> { it.position }.thenByDescending { it.id })
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Main scroll id -> number of pages. */
    val pageCounts: StateFlow<Map<Long, Int>> = all.map { l -> l.mapNotNull { it.parentId }.groupingBy { it }.eachCount() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun cycleSort() {
        val next = NoteSort.entries[(sort.value.ordinal + 1) % NoteSort.entries.size]
        sort.value = next
        prefs.edit().putString(KEY_NOTE_SORT, next.name).apply()
    }

    /** Saves a hand-made order (top to bottom). */
    fun move(ids: List<Long>) {
        notebook.appScope.launch {
            try {
                notebook.db.withTransaction { ids.forEachIndexed { i, id -> notebook.db.noteDao().setPosition(id, i) } }
            } catch (e: Exception) { notebook.reportError("reorder notes", e) }
        }
    }

    /** Permanent, with no trash and no history — the caller confirms first. */
    fun delete(id: Long) {
        notebook.appScope.launch {
            try { notebook.db.noteDao().delete(id) } catch (e: Exception) { notebook.reportError("delete note", e) }
        }
    }
}

class TasksViewModel(app: Application) : AndroidViewModel(app) {
    private val dao get() = notebook.db.taskDao()

    val newestFirst = MutableStateFlow(prefs.getBoolean(KEY_TASK_NEWEST, false))

    val tasks: StateFlow<List<Task>?> = combine(dao.observeLit(), newestFirst) { list, newest -> if (newest) list.asReversed() else list }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun toggleOrder() {
        newestFirst.value = !newestFirst.value
        prefs.edit().putBoolean(KEY_TASK_NEWEST, newestFirst.value).apply()
    }

    val snuffed: StateFlow<List<Task>> = dao.observeSnuffed()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun add(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        notebook.appScope.launch {
            try { dao.insert(Task(text = clean)) } catch (e: Exception) { notebook.reportError("add task", e) }
        }
    }

    /** Checking a task snuffs it: it moves to the ash ledge and is kept forever. */
    fun complete(id: Long) {
        notebook.appScope.launch {
            try { dao.snuff(id, System.currentTimeMillis()) } catch (e: Exception) { notebook.reportError("snuff task", e) }
        }
    }

    fun relight(id: Long) {
        notebook.appScope.launch {
            try { dao.relight(id) } catch (e: Exception) { notebook.reportError("relight task", e) }
        }
    }
}

/**
 * Editing session for one note. [noteId] == null means a brand-new note, which is only
 * inserted once it actually has content. [newParentId] makes a new note a page of that main scroll.
 */
class NoteEditorViewModel(app: Application, noteId: Long?, newParentId: Long? = null) : AndroidViewModel(app) {
    private val dao get() = notebook.db.noteDao()
    private val mutex = Mutex()

    var title by mutableStateOf("")
        private set
    var content by mutableStateOf("")
        private set
    var loaded by mutableStateOf(noteId == null)
        private set

    private var id: Long? by mutableStateOf(noteId)
    private var deleted = false
    private var savedTitle = ""
    private var savedContent = ""
    private var saveJob: Job? = null

    /** Non-null = this note is a page of that main scroll. */
    var parentId: Long? by mutableStateOf(newParentId)
        private set
    var parentTitle by mutableStateOf("")
        private set

    val exists: Boolean get() = id != null
    val noteId: Long? get() = id

    /** Pages of this scroll (creation order); empty until the scroll has been saved once. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pages: StateFlow<List<Note>> = snapshotFlow { id }
        .flatMapLatest { if (it == null) emptyFlow() else dao.observePages(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        if (newParentId != null) loadParentTitle(newParentId)
        if (noteId != null) {
            viewModelScope.launch {
                try {
                    val n = dao.get(noteId)
                    if (n != null) {
                        title = n.title; content = n.content
                        savedTitle = n.title; savedContent = n.content
                        parentId = n.parentId
                        n.parentId?.let { loadParentTitle(it) }
                    } else {
                        id = null // note vanished; treat as new
                    }
                } catch (e: Exception) {
                    notebook.reportError("open note", e)
                }
                loaded = true
            }
        }
    }

    fun onTitle(v: String) { title = v; scheduleSave() }
    fun onContent(v: String) { content = v; scheduleSave() }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500)
            flush()
        }
    }

    /** Persist the latest text now. Safe to call repeatedly; runs outside the UI lifecycle. */
    fun flush(): Job {
        saveJob?.cancel()
        return notebook.appScope.launch { persist() }
    }

    /** Like [flush], but also stores a still-blank new scroll so a page has a parent to hang from. */
    fun flushForPage(): Job {
        saveJob?.cancel()
        return notebook.appScope.launch { persist(force = true) }
    }

    private fun loadParentTitle(pid: Long) {
        viewModelScope.launch {
            try { parentTitle = dao.get(pid)?.title.orEmpty() } catch (e: Exception) { notebook.reportError("open note", e) }
        }
    }

    /** Burns one page of this scroll (and nothing else). */
    fun deletePage(pageId: Long) {
        notebook.appScope.launch {
            try { dao.delete(pageId) } catch (e: Exception) { notebook.reportError("delete note", e) }
        }
    }

    private suspend fun persist(force: Boolean = false) {
        mutex.withLock {
            if (!loaded || deleted) return
            val t = title
            val c = content
            if (t == savedTitle && c == savedContent && !(force && id == null)) return
            try {
                val current = id
                if (current == null) {
                    if (t.isBlank() && c.isBlank() && !force) return // never store a note that was never written
                    id = dao.insert(Note(title = t, content = c, position = (dao.minPosition() ?: 1) - 1, parentId = parentId))
                } else {
                    dao.updateText(current, t, c, System.currentTimeMillis())
                }
                savedTitle = t; savedContent = c
            } catch (e: Exception) {
                notebook.reportError("save note", e)
            }
        }
    }

    fun delete() {
        deleted = true
        saveJob?.cancel()
        notebook.appScope.launch {
            mutex.withLock {
                try { id?.let { dao.delete(it) } } catch (e: Exception) { notebook.reportError("delete note", e) }
            }
        }
    }

    override fun onCleared() {
        // Last chance: anything typed but not yet written gets saved.
        if (!deleted) notebook.appScope.launch { persist() }
    }
}

/** Lobes and ideas for the Brain tab. `null` = first database emission has not arrived yet. */
class BrainViewModel(app: Application) : AndroidViewModel(app) {
    private val dao get() = notebook.db.brainDao()

    val lobes: StateFlow<List<app.vesper.data.Lobe>?> = dao.observeLobes()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val ideas: StateFlow<List<app.vesper.data.Idea>?> = dao.observeIdeas()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun addLobe(name: String, onAdded: (Long) -> Unit) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        notebook.appScope.launch {
            try {
                val existing = lobes.value.orEmpty()
                val slot = generateSequence(0) { it + 1 }.first { s -> existing.none { it.slot == s } }
                val id = dao.insertLobe(app.vesper.data.Lobe(name = clean, colorIndex = slot, slot = slot))
                launch(kotlinx.coroutines.Dispatchers.Main) { onAdded(id) }
            } catch (e: Exception) { notebook.reportError("add lobe", e) }
        }
    }

    /** Permanent, and takes the lobe's ideas with it. The caller confirms first. */
    fun deleteLobe(id: Long) {
        notebook.appScope.launch {
            try { dao.deleteLobe(id) } catch (e: Exception) { notebook.reportError("delete lobe", e) }
        }
    }

    /** [idea].id == 0 inserts a new idea, otherwise updates it. */
    fun saveIdea(idea: app.vesper.data.Idea) {
        val clean = idea.copy(title = idea.title.trim(), body = idea.body.trim(), updatedAt = System.currentTimeMillis())
        if (clean.title.isEmpty() && clean.body.isEmpty()) return
        notebook.appScope.launch {
            try {
                if (clean.id == 0L) dao.insertIdea(clean) else dao.updateIdea(clean)
            } catch (e: Exception) { notebook.reportError("save idea", e) }
        }
    }

    fun deleteIdea(id: Long) {
        notebook.appScope.launch {
            try { dao.deleteIdea(id) } catch (e: Exception) { notebook.reportError("delete idea", e) }
        }
    }
}

/** Posts for the Shrine tab. `null` = first database emission has not arrived yet. */
class ShrineViewModel(app: Application) : AndroidViewModel(app) {
    private val dao get() = notebook.db.postDao()

    val posts: StateFlow<List<Post>?> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // The composer draft lives here so it survives rotation.
    var draft by mutableStateOf("")
    var draftPhoto by mutableStateOf<Uri?>(null)
    var sending by mutableStateOf(false)
        private set

    /**
     * Keeps the draft as a new post. The photo (if any) is copied into `filesDir/shrine` first; the row is only
     * written once that succeeded, and the draft is only cleared after that, so a failure loses nothing.
     */
    fun send() {
        if (sending) return
        val clean = draft.trim().ifEmpty { null }
        val photo = draftPhoto
        if (clean == null && photo == null) return
        sending = true
        notebook.appScope.launch {
            var name: String? = null
            try {
                val video = photo != null && notebook.contentResolver.getType(photo)?.startsWith("video/") == true
                if (photo != null) name = if (video) ShrineFiles.importVideo(notebook, photo) else ShrineFiles.importPhoto(notebook, photo)
                dao.insert(Post(text = clean, imagePath = name.takeUnless { video }, videoPath = name.takeIf { video }))
                withContext(Dispatchers.Main) {
                    if (draft.trim().ifEmpty { null } == clean) draft = ""
                    if (draftPhoto == photo) draftPhoto = null
                }
            } catch (e: Exception) {
                name?.let { ShrineFiles.file(notebook, it).delete() }
                notebook.reportError("keep post", e)
            }
            withContext(Dispatchers.Main) { sending = false }
        }
    }

    /** Text only; the photo stays. A post with neither text nor photo is not allowed, so such an edit is ignored. */
    fun edit(id: Long, text: String) {
        notebook.appScope.launch {
            try {
                val p = dao.get(id) ?: return@launch
                val clean = text.trim().ifEmpty { null }
                if (clean == null && p.imagePath == null && p.videoPath == null) return@launch
                if (clean == p.text) return@launch
                dao.update(p.copy(text = clean, editedAt = System.currentTimeMillis()))
            } catch (e: Exception) { notebook.reportError("edit post", e) }
        }
    }

    /** Permanent. Removes the row first, then the photo file. The caller confirms first. */
    fun delete(id: Long) {
        notebook.appScope.launch {
            try {
                val p = dao.get(id)
                dao.delete(id)
                (p?.imagePath ?: p?.videoPath)?.let { ShrineFiles.file(notebook, it).delete() }
            } catch (e: Exception) { notebook.reportError("delete post", e) }
        }
    }
}

/** Plans and per-day Brain focus for the Moons tab. Days are `LocalDate.toEpochDay()`. `null` = first emission pending. */
class MoonsViewModel(app: Application) : AndroidViewModel(app) {
    private val dao get() = notebook.db.moonsDao()

    val plans: StateFlow<List<app.vesper.data.Plan>?> = dao.observeAllPlans()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val focus: StateFlow<List<app.vesper.data.DayFocus>?> = dao.observeFocus()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // The composer draft lives here so it survives rotation.
    var draft by mutableStateOf("")

    fun addPlan(day: Long) {
        val clean = draft.trim()
        if (clean.isEmpty()) return
        draft = ""
        notebook.appScope.launch {
            try { dao.insertPlan(app.vesper.data.Plan(day = day, text = clean)) } catch (e: Exception) {
                notebook.reportError("add plan", e)
            }
        }
    }

    fun setDone(plan: app.vesper.data.Plan, done: Boolean) {
        notebook.appScope.launch {
            try { dao.updatePlan(plan.copy(done = done)) } catch (e: Exception) { notebook.reportError("update plan", e) }
        }
    }

    fun editPlan(plan: app.vesper.data.Plan, text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || clean == plan.text) return
        notebook.appScope.launch {
            try { dao.updatePlan(plan.copy(text = clean)) } catch (e: Exception) { notebook.reportError("edit plan", e) }
        }
    }

    /** Permanent. The caller confirms first. */
    fun deletePlan(id: Long) {
        notebook.appScope.launch {
            try { dao.deletePlan(id) } catch (e: Exception) { notebook.reportError("delete plan", e) }
        }
    }

    fun toggleFocus(day: Long, lobeId: Long, on: Boolean) {
        notebook.appScope.launch {
            try {
                if (on) dao.insertFocus(app.vesper.data.DayFocus(day, lobeId)) else dao.deleteFocus(day, lobeId)
            } catch (e: Exception) { notebook.reportError("update focus", e) }
        }
    }
}

/** Shrine photo storage: `filesDir/shrine/<uuid>.jpg`. */
object ShrineFiles {
    private const val MAX_EDGE = 2048

    fun file(app: Application, name: String) = File(File(app.filesDir, "shrine"), name)

    /** Blocking: call off the main thread. Copies the video as is (no re-encoding); returns the stored file name. */
    fun importVideo(app: Application, uri: Uri): String {
        val dir = File(app.filesDir, "shrine").apply { mkdirs() }
        val name = UUID.randomUUID().toString() + ".mp4"
        val out = File(dir, name)
        try {
            val stream = app.contentResolver.openInputStream(uri) ?: throw java.io.IOException("cannot open video")
            stream.use { i -> out.outputStream().use { i.copyTo(it) } }
        } catch (e: Exception) {
            out.delete(); throw e
        }
        return name
    }

    /** Blocking: call off the main thread. Returns the stored file name. */
    fun importPhoto(app: Application, uri: Uri): String {
        val resolver = app.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Bounds-only decoding always returns null, so check that the stream opened, not the decode result.
        val stream = resolver.openInputStream(uri) ?: throw java.io.IOException("cannot open photo")
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw java.io.IOException("not an image")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        var bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw java.io.IOException("cannot decode photo")

        val orientation = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val longEdge = maxOf(bmp.width, bmp.height)
        if (longEdge > MAX_EDGE) { val k = MAX_EDGE.toFloat() / longEdge; m.postScale(k, k) }
        if (!m.isIdentity) {
            val t = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (t !== bmp) bmp.recycle()
            bmp = t
        }

        val dir = File(app.filesDir, "shrine").apply { mkdirs() }
        val name = UUID.randomUUID().toString() + ".jpg"
        val out = File(dir, name)
        try {
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } catch (e: Exception) {
            out.delete(); throw e
        } finally {
            bmp.recycle()
        }
        return name
    }
}
