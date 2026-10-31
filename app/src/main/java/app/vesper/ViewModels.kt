package app.vesper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.app.Application
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

/** `null` = the first database emission has not arrived yet. */
class NotesViewModel(app: Application) : AndroidViewModel(app) {
    val notes: StateFlow<List<Note>?> = notebook.db.noteDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Permanent, with no trash and no history â€” the caller confirms first. */
    fun delete(id: Long) {
        notebook.appScope.launch {
            try { notebook.db.noteDao().delete(id) } catch (e: Exception) { notebook.reportError("delete note", e) }
        }
    }
}

class TasksViewModel(app: Application) : AndroidViewModel(app) {
    private val dao get() = notebook.db.taskDao()

    val tasks: StateFlow<List<Task>?> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun add(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        notebook.appScope.launch {
            try { dao.insert(Task(text = clean)) } catch (e: Exception) { notebook.reportError("add task", e) }
        }
    }

    /** Checking a task deletes it permanently. There is no undo and no history. */
    fun complete(id: Long) {
        notebook.appScope.launch {
            try { dao.delete(id) } catch (e: Exception) { notebook.reportError("remove task", e) }
        }
    }
}

/**
 * Editing session for one note. [noteId] == null means a brand-new note, which is only
 * inserted once it actually has content.
 */
class NoteEditorViewModel(app: Application, noteId: Long?) : AndroidViewModel(app) {
    private val dao get() = notebook.db.noteDao()
    private val mutex = Mutex()

    var title by mutableStateOf("")
        private set
    var content by mutableStateOf("")
        private set
    var loaded by mutableStateOf(noteId == null)
        private set

    private var id: Long? = noteId
    private var deleted = false
    private var savedTitle = ""
    private var savedContent = ""
    private var saveJob: Job? = null

    val exists: Boolean get() = id != null

    init {
        if (noteId != null) {
            viewModelScope.launch {
                try {
                    val n = dao.get(noteId)
                    if (n != null) {
                        title = n.title; content = n.content
                        savedTitle = n.title; savedContent = n.content
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
    fun flush() {
        saveJob?.cancel()
        notebook.appScope.launch { persist() }
    }

    private suspend fun persist() {
        mutex.withLock {
            if (!loaded || deleted) return
            val t = title
            val c = content
            if (t == savedTitle && c == savedContent) return
            try {
                val current = id
                if (current == null) {
                    if (t.isBlank() && c.isBlank()) return // never store a note that was never written
                    id = dao.insert(Note(title = t, content = c))
                } else {
                    dao.update(Note(id = current, title = t, content = c))
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
                if (photo != null) name = ShrineFiles.importPhoto(notebook, photo)
                dao.insert(Post(text = clean, imagePath = name))
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
                if (clean == null && p.imagePath == null) return@launch
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
                p?.imagePath?.let { ShrineFiles.file(notebook, it).delete() }
            } catch (e: Exception) { notebook.reportError("delete post", e) }
        }
    }
}

/** Shrine photo storage: `filesDir/shrine/<uuid>.jpg`. */
object ShrineFiles {
    private const val MAX_EDGE = 2048

    fun file(app: Application, name: String) = File(File(app.filesDir, "shrine"), name)

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
