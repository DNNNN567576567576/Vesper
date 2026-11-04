package app.vesper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import app.vesper.data.Note
import app.vesper.data.Post
import app.vesper.data.Task
import app.vesper.sync.copyTitle
import app.vesper.sync.copyUid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class NoteSort { EDITED, ADDED, MANUAL }

private const val KEY_NOTE_SORT = "noteSort"
private const val KEY_TASK_NEWEST = "taskNewestFirst"


/** `null` = the first database emission has not arrived yet. */
class NotesViewModel(private val notebook: VesperHost) : ViewModel() {
    val sort = MutableStateFlow(NoteSort.entries.firstOrNull { it.name == notebook.settings.getString(KEY_NOTE_SORT) } ?: NoteSort.EDITED)

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
        notebook.settings.putString(KEY_NOTE_SORT, next.name)
    }

    /** Saves a hand-made order (top to bottom). */
    fun move(ids: List<Long>) {
        notebook.appScope.launch {
            try {
                // One write transaction (BEGIN IMMEDIATE): all positions land together or none do.
                notebook.db.useWriterConnection { c -> c.immediateTransaction { ids.forEachIndexed { i, id -> notebook.db.noteDao().setPosition(id, i) } } }
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

class TasksViewModel(private val notebook: VesperHost) : ViewModel() {
    private val dao get() = notebook.db.taskDao()

    val newestFirst = MutableStateFlow(notebook.settings.getBoolean(KEY_TASK_NEWEST, false))

    val tasks: StateFlow<List<Task>?> = combine(dao.observeLit(), newestFirst) { list, newest -> if (newest) list.asReversed() else list }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun toggleOrder() {
        newestFirst.value = !newestFirst.value
        notebook.settings.putBoolean(KEY_TASK_NEWEST, newestFirst.value)
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
class NoteEditorViewModel(private val notebook: VesperHost, noteId: Long?, newParentId: Long? = null) : ViewModel() {
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

    // A sync round must carry what is on screen, so a conflict shows up as one instead of old text being merged around it.
    private val stopFlushing = notebook.sync.beforeRound { flush().join() }

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

    @OptIn(ExperimentalCoroutinesApi::class)
    private val rowChanges = snapshotFlow { id }.flatMapLatest { if (it == null) emptyFlow() else dao.observe(it) }

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
        viewModelScope.launch { rowChanges.collect { refresh() } }
    }

    /** Takes in what sync wrote, unless there is typing on top of the old text: then [persist] keeps both. */
    private suspend fun refresh() {
        mutex.withLock {
            val n = id?.let { dao.get(it) } ?: return // gone on another device: the text stays here, and typing brings it back
            if (!loaded || deleted || title != savedTitle || content != savedContent) return
            if (n.title == savedTitle && n.content == savedContent) return
            title = n.title; content = n.content
            savedTitle = n.title; savedContent = n.content
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
                if (current == null && t.isBlank() && c.isBlank() && !force) return // never store a note that was never written
                // In one transaction, so sync can't slip in between the look and the write.
                id = notebook.db.useWriterConnection { w ->
                    w.immediateTransaction {
                        val row = current?.let { dao.get(it) }
                        if (row == null) {
                            // New, or deleted on another device while it was open: the text gets a row either way.
                            parentId = parentId?.takeIf { dao.get(it) != null }
                            dao.insert(Note(title = t, content = c, position = (dao.minPosition() ?: 1) - 1, parentId = parentId))
                        } else {
                            val theirs = row.title to row.content
                            if (theirs != (savedTitle to savedContent) && theirs != (t to c)) {
                                dao.insertCopy(row.copy(id = 0, title = copyTitle(row.title), uid = copyUid(row.uid, row.syncAt)))
                            }
                            dao.updateText(row.id, t, c, System.currentTimeMillis())
                            row.id
                        }
                    }
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
        stopFlushing()
        // Last chance: anything typed but not yet written gets saved.
        if (!deleted) notebook.appScope.launch { persist() }
    }
}

/** Lobes and ideas for the Brain tab. `null` = first database emission has not arrived yet. */
class BrainViewModel(private val notebook: VesperHost) : ViewModel() {
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
                launch(Dispatchers.Main) { onAdded(id) }
            } catch (e: Exception) { notebook.reportError("add lobe", e) }
        }
    }

    /** Permanent, and takes the lobe's ideas with it. The caller confirms first. */
    fun deleteLobe(id: Long) {
        notebook.appScope.launch {
            try { dao.deleteLobe(id) } catch (e: Exception) { notebook.reportError("delete lobe", e) }
        }
    }

    /**
     * [base] is the idea as the dialog opened it, id 0 for a new one. Only the title is written. If sync changed the title
     * meanwhile the other one stays as a copy; if the idea or even its lobe is gone, the words still get a row.
     */
    fun saveIdea(base: app.vesper.data.Idea, title: String): Job = notebook.appScope.launch {
        val clean = title.trim()
        if (clean.isEmpty() && base.body.isBlank()) return@launch
        val now = System.currentTimeMillis()
        try {
            notebook.db.useWriterConnection { w ->
                w.immediateTransaction {
                    val row = if (base.id == 0L) null else dao.getIdea(base.id)
                    when {
                        row != null -> if (row.title != clean) {
                            if (row.title != base.title) dao.insertIdeaCopy(row.copy(id = 0, title = copyTitle(row.title), uid = copyUid(row.uid, row.syncAt)))
                            dao.setIdeaTitle(row.id, clean, now)
                        }
                        dao.lobeExists(base.lobeId) -> dao.insertIdea(base.copy(id = 0, title = clean, body = base.body.trim(), updatedAt = now, syncAt = 0))
                        else -> notebook.db.noteDao().insert(Note(title = clean, content = base.body.trim()))
                    }
                }
            }
        } catch (e: Exception) { notebook.reportError("save idea", e) }
    }

    fun deleteIdea(id: Long) {
        notebook.appScope.launch {
            try { dao.deleteIdea(id) } catch (e: Exception) { notebook.reportError("delete idea", e) }
        }
    }
}

/** Posts for the Shrine tab. `null` = first database emission has not arrived yet. */
class ShrineViewModel(private val notebook: VesperHost) : ViewModel() {
    private val dao get() = notebook.db.postDao()

    val posts: StateFlow<List<Post>?> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // The composer draft lives here so it survives rotation.
    var draft by mutableStateOf("")
    /** Picked, not yet kept photo or video ([ShrineMedia] ref). */
    var draftPhoto by mutableStateOf<String?>(null)
    var sending by mutableStateOf(false)
        private set

    /**
     * Keeps the draft as a new post. The photo (if any) is copied into the Grimoire's storage first; the row is only
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
                val video = photo != null && notebook.shrineMedia.isVideo(photo)
                if (photo != null) name = notebook.shrineMedia.import(photo, video)
                dao.insert(Post(text = clean, imagePath = name.takeUnless { video }, videoPath = name.takeIf { video }))
                withContext(Dispatchers.Main) {
                    if (draft.trim().ifEmpty { null } == clean) draft = ""
                    if (draftPhoto == photo) draftPhoto = null
                }
            } catch (e: Exception) {
                name?.let { notebook.shrineMedia.delete(it) }
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
                (p?.imagePath ?: p?.videoPath)?.let { notebook.shrineMedia.delete(it) }
            } catch (e: Exception) { notebook.reportError("delete post", e) }
        }
    }
}

/** Plans and per-day Brain focus for the Moons tab. Days are `LocalDate.toEpochDay()`. `null` = first emission pending. */
class MoonsViewModel(private val notebook: VesperHost) : ViewModel() {
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

    fun setDone(plan: app.vesper.data.Plan, done: Boolean): Job = notebook.appScope.launch {
        try { dao.setPlanDone(plan.id, done) } catch (e: Exception) { notebook.reportError("update plan", e) }
    }

    /** [plan] is the plan as the dialog opened it. Same rules as [BrainViewModel.saveIdea]. */
    fun editPlan(plan: app.vesper.data.Plan, text: String): Job = notebook.appScope.launch {
        val clean = text.trim()
        if (clean.isEmpty() || clean == plan.text) return@launch
        try {
            notebook.db.useWriterConnection { w ->
                w.immediateTransaction {
                    val row = dao.getPlan(plan.id)
                    when {
                        row == null -> dao.insertPlan(plan.copy(id = 0, text = clean, syncAt = 0))
                        row.text != clean -> {
                            if (row.text != plan.text) dao.insertPlanCopy(row.copy(id = 0, text = copyTitle(row.text), uid = copyUid(row.uid, row.syncAt)))
                            dao.setPlanText(row.id, clean)
                        }
                    }
                }
            }
        } catch (e: Exception) { notebook.reportError("edit plan", e) }
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
