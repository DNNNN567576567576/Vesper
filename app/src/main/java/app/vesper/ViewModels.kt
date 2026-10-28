package app.vesper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.app.Application
import app.vesper.data.Note
import app.vesper.data.Task
import kotlinx.coroutines.Job
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
