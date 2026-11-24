package app.vesper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import app.vesper.data.Idea
import app.vesper.data.Note
import app.vesper.data.Post
import app.vesper.sync.copyTitle
import app.vesper.sync.copyUid
import app.vesper.ui.SharedText
import app.vesper.ui.parseShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext



/** Lobes and ideas for the Brain tab. `null` = first database emission has not arrived yet. */
class BrainViewModel(private val notebook: VesperHost) : ViewModel() {
    private val dao get() = notebook.db.brainDao()

    val lobes: StateFlow<List<app.vesper.data.Lobe>?> = dao.observeLobes()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val ideas: StateFlow<List<app.vesper.data.Idea>?> = dao.observeIdeas()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val branches: StateFlow<List<app.vesper.data.Branch>?> = dao.observeBranches()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    /** The daily log of the stats window (from the day this opened, so it only grows past it, which the stats ignore). */
    val dailyLog: StateFlow<List<app.vesper.data.DailyLog>> = dao.observeLogSince(java.time.LocalDate.now().minusDays(STATS_DAYS - 1L).toString())
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** A new sub brain called [name] in the first free slot. */
    private suspend fun insertLobe(name: String): Long {
        val existing = dao.observeLobes().first()
        val slot = generateSequence(0) { it + 1 }.first { s -> existing.none { it.slot == s } }
        return dao.insertLobe(app.vesper.data.Lobe(name = name, colorIndex = slot, slot = slot))
    }


    /**
     * Calls back with the first sub brain called [name] (trimmed, any case; "" = the nameless one that holds the loose notes), made
     * when there is none. Two devices may each make one before they sync; the Brain shows same-named ones as one, so nothing hides.
     */
    fun lobeNamed(name: String, onReady: (Long) -> Unit) {
        val clean = name.trim()
        notebook.appScope.launch {
            try {
                val id = dao.observeLobes().first().firstOrNull { it.name.trim().equals(clean, ignoreCase = true) }?.id ?: insertLobe(clean)
                launch(Dispatchers.Main) { onReady(id) }
            } catch (e: Exception) { notebook.reportError("find lobe", e) }
        }
    }

    // Where the player put the brains on the map, per device.
    fun loadLayout() = app.vesper.ui.NoteMoves.load(notebook.settings)
    fun saveLayout() = app.vesper.ui.NoteMoves.save(notebook.settings)
    fun resetLayout() = app.vesper.ui.NoteMoves.reset(notebook.settings)


    /** Same rules as [writeIdea]; [base] is the idea as the caller last saw it, id 0 for a new one. */
    fun saveIdea(
        base: app.vesper.data.Idea,
        title: String,
        body: String = base.body,
        link: String? = base.link,
        branchId: Long? = base.branchId,
    ): Job = notebook.appScope.launch {
        try { writeIdea(notebook, base, title, body, link, branchId) } catch (e: Exception) { notebook.reportError("save idea", e) }
    }

    /** An item made from shared text, kept in [lobeId]. */
    fun addShared(share: SharedText, lobeId: Long) {
        val parsed = parseShare(share.text, share.subject)
        notebook.appScope.launch {
            try {
                if (dao.lobeExists(lobeId)) dao.insertIdea(app.vesper.data.Idea(lobeId = lobeId, title = parsed.title, body = parsed.body, link = parsed.link))
            } catch (e: Exception) { notebook.reportError("save shared text", e) }
        }
    }

    /** A new Daily item called [name], in the Daily sub brain (made when missing). */
    fun addDaily(name: String) {
        val clean = name.trim().ifEmpty { return }
        lobeNamed(DAILY_LOBE) { lobeId ->
            notebook.appScope.launch {
                try { dao.insertIdea(app.vesper.data.Idea(lobeId = lobeId, title = clean, body = "")) } catch (e: Exception) { notebook.reportError("add daily", e) }
            }
        }
    }

    /** Lights or snuffs a daily candle, and logs it for today. */
    fun tickDaily(item: app.vesper.data.Idea, done: Boolean) {
        notebook.appScope.launch {
            try { app.vesper.tickDaily(notebook.db, item, done, java.time.LocalDate.now()) } catch (e: Exception) { notebook.reportError("tick daily", e) }
        }
    }

    fun deleteIdea(id: Long) {
        notebook.appScope.launch {
            try { dao.deleteIdea(id) } catch (e: Exception) { notebook.reportError("delete idea", e) }
        }
    }
}

/**
 * [base] is the idea as the caller last saw it, id 0 for a new one. Only fields the caller changed are written. If sync
 * changed one of those too the other version stays as a copy; if the idea or even its lobe is gone, the words still get
 * a row. A group deleted meanwhile just leaves the idea directly on the lobe. Returns the idea's row id, null when nothing
 * was stored as an idea (blank, or its lobe is gone and the words went to a note).
 */
internal suspend fun writeIdea(notebook: VesperHost, base: Idea, title: String, body: String, link: String?, branchId: Long?): Long? {
    val dao = notebook.db.brainDao()
    val clean = title.trim()
    val note = body.trim()
    val url = link?.trim()?.ifEmpty { null }
    if (clean.isEmpty() && note.isEmpty()) return null
    val now = System.currentTimeMillis()
    return notebook.db.useWriterConnection { w ->
        w.immediateTransaction {
            val row = if (base.id == 0L) null else dao.getIdea(base.id)
            val branch = branchId?.takeIf { dao.branchExists(it) }
            when {
                row != null -> {
                    val t = if (clean == base.title.trim()) row.title else clean
                    val b = if (note == base.body.trim()) row.body else note
                    val l = if (url == base.link) row.link else url
                    val g = if (branchId == base.branchId) row.branchId else branch
                    if (row.title != t || row.body != b || row.link != l || row.branchId != g) {
                        if ((row.title != t && row.title != base.title) || (row.body != b && row.body != base.body) || (row.link != l && row.link != base.link)) {
                            dao.insertIdeaCopy(row.copy(id = 0, title = copyTitle(row.title), uid = copyUid(row.uid, row.syncAt)))
                        }
                        dao.setIdeaFields(row.id, t, b, l, g, now)
                    }
                    row.id
                }
                dao.lobeExists(base.lobeId) -> dao.insertIdea(base.copy(id = 0, title = clean, body = note, link = url, branchId = branch, updatedAt = now, syncAt = 0))
                else -> { notebook.db.noteDao().insert(Note(title = clean, content = note)); null }
            }
        }
    }
}

/**
 * Editing session for one Brain item on its own page. [ideaId] null = a new item in [lobeId],
 * which only gets a row once something is written. The page holds [base], the item as it was last saved or taken in; sync
 * changes are taken in while nothing is typed, and typing on top of an older text is saved by [writeIdea], which keeps both.
 */
class IdeaEditorViewModel(private val notebook: VesperHost, ideaId: Long?, lobeId: Long, branchId: Long?) : ViewModel() {
    private val dao get() = notebook.db.brainDao()
    private val mutex = Mutex()

    var title by mutableStateOf("")
        private set
    var body by mutableStateOf("")
        private set
    var link by mutableStateOf("")
        private set
    var loaded by mutableStateOf(ideaId == null)
        private set

    private var base = Idea(lobeId = lobeId, title = "", body = "", branchId = branchId)
    private var id by mutableLongStateOf(ideaId ?: 0L)
    private var deleted = false
    private var saveJob: Job? = null

    // A sync round must carry what is on screen, so a conflict shows up as one instead of old text being merged around it.
    private val stopFlushing = notebook.sync.beforeRound { flush().join() }

    val exists: Boolean get() = id != 0L
    val lobeId: Long get() = base.lobeId
    val branchId: Long? get() = base.branchId

    @OptIn(ExperimentalCoroutinesApi::class)
    private val rowChanges = snapshotFlow { id }.flatMapLatest { if (it == 0L) emptyFlow() else dao.observeIdeas() }

    init {
        if (ideaId != null) {
            viewModelScope.launch {
                try {
                    val row = dao.getIdea(ideaId)
                    if (row != null) {
                        base = row; title = row.title; body = row.body; link = row.link.orEmpty()
                    } else {
                        id = 0L // vanished before it opened; treat as new
                    }
                } catch (e: Exception) {
                    notebook.reportError("open item", e)
                }
                loaded = true
            }
        }
        viewModelScope.launch { rowChanges.collect { refresh() } }
    }

    private fun dirty() = title.trim() != base.title.trim() || body.trim() != base.body.trim() || link.trim().ifEmpty { null } != base.link

    /** Takes in what sync wrote, unless there is typing on top of the old text: then [persist] keeps both. */
    private suspend fun refresh() {
        mutex.withLock {
            val row = id.takeIf { it != 0L }?.let { dao.getIdea(it) } ?: return // gone on another device: the text stays here, and typing brings it back
            if (!loaded || deleted || dirty()) return
            if (row.title != base.title || row.body != base.body || row.link != base.link) {
                title = row.title; body = row.body; link = row.link.orEmpty()
            }
            base = row
        }
    }

    /**
     * Moves this item to [toLobe] (and its group [toBranch], null = loose on the lobe). Typed text is saved first; the move is
     * one transaction. Returns the new home's names ("Coding" or "Coding › Vesper"), null when it could not move
     * (nothing typed yet, the sub brain or group is gone, or a group of another sub brain).
     */
    suspend fun moveTo(toLobe: Long, toBranch: Long?): String? {
        saveJob?.cancel()
        persist()
        return mutex.withLock {
            if (id == 0L || deleted) return null
            try {
                val dest = notebook.db.useWriterConnection { w ->
                    w.immediateTransaction {
                        val lobe = dao.getLobe(toLobe) ?: return@immediateTransaction null
                        val branch = toBranch?.let { dao.getBranch(it) ?: return@immediateTransaction null }
                        if (branch != null && branch.lobeId != toLobe) return@immediateTransaction null
                        if (dao.getIdea(id) == null) return@immediateTransaction null
                        dao.moveIdea(id, toLobe, toBranch, System.currentTimeMillis())
                        listOfNotNull(lobe.name, branch?.name).joinToString(" › ")
                    }
                }
                if (dest != null) dao.getIdea(id)?.let { base = it }
                dest
            } catch (e: Exception) {
                notebook.reportError("move item", e); null
            }
        }
    }

    fun onTitle(v: String) { title = v; scheduleSave() }
    fun onBody(v: String) { body = v; scheduleSave() }
    fun onLink(v: String) { link = v; scheduleSave() }

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

    private suspend fun persist() {
        mutex.withLock {
            if (!loaded || deleted || !dirty()) return
            val t = title; val b = body; val l = link
            try {
                val saved = writeIdea(notebook, base, t, b, l, base.branchId) ?: return
                val row = dao.getIdea(saved) ?: return
                // What this page now stands for: its own text, not a field sync changed meanwhile (refresh takes that in).
                base = row.copy(title = t.trim(), body = b.trim(), link = l.trim().ifEmpty { null })
                id = row.id
            } catch (e: Exception) {
                notebook.reportError("save item", e)
            }
        }
    }

    fun delete() {
        deleted = true
        saveJob?.cancel()
        notebook.appScope.launch {
            mutex.withLock {
                try { id.takeIf { it != 0L }?.let { dao.deleteIdea(it) } } catch (e: Exception) { notebook.reportError("delete item", e) }
            }
        }
    }

    override fun onCleared() {
        stopFlushing()
        // Last chance: anything typed but not yet written gets saved.
        if (!deleted) notebook.appScope.launch { persist() }
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

/** The Alarms tab (phone only; see AlarmScheduler). The DB row is the truth, the scheduler is told after every change. */
class AlarmsViewModel(private val notebook: VesperHost) : ViewModel() {
    private val alarmDao get() = notebook.db.alarmDao()

    val alarms: StateFlow<List<app.vesper.data.Alarm>> = alarmDao.observeActive()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val alarmHistory: StateFlow<List<app.vesper.data.Alarm>> = alarmDao.observeHistory()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Inserts (id 0) or updates [a], then arms or disarms it. */
    fun saveAlarm(a: app.vesper.data.Alarm) {
        notebook.appScope.launch {
            try {
                val id = if (a.id == 0L) alarmDao.insert(a) else { alarmDao.update(a); a.id }
                notebook.alarms.schedule(a.copy(id = id))
            } catch (e: Exception) { notebook.reportError("save alarm", e) }
        }
    }

    fun setAlarmEnabled(a: app.vesper.data.Alarm, on: Boolean) = saveAlarm(a.copy(enabled = on))

    /** Permanent. The caller confirms first. */
    fun deleteAlarm(id: Long) {
        notebook.appScope.launch {
            try { notebook.alarms.cancel(id); alarmDao.delete(id) } catch (e: Exception) { notebook.reportError("delete alarm", e) }
        }
    }

    /** Permanent: removes every rung alarm from the history. The caller confirms first. */
    fun clearAlarmHistory() {
        notebook.appScope.launch {
            try { for (a in alarmHistory.value) alarmDao.delete(a.id) } catch (e: Exception) { notebook.reportError("clear alarm history", e) }
        }
    }
}

/** The learn brain's goals (just names; their steps stay in the database untouched but are not shown). `null` = first emission pending. */
class LearnViewModel(private val notebook: VesperHost) : ViewModel() {
    private val dao get() = notebook.db.learnDao()

    val goals: StateFlow<List<app.vesper.data.Goal>?> = dao.observeGoals()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun write(what: String, block: suspend () -> Unit): Job = notebook.appScope.launch {
        try { block() } catch (e: Exception) { notebook.reportError(what, e) }
    }

    fun addGoal(name: String) {
        val clean = name.trim().ifEmpty { return }
        write("add goal") { dao.insertGoal(app.vesper.data.Goal(name = clean, position = dao.nextGoalPosition())) }
    }

    fun renameGoal(id: Long, name: String) {
        val clean = name.trim().ifEmpty { return }
        write("rename goal") { dao.renameGoal(id, clean, System.currentTimeMillis()) }
    }

    /** Permanent (a goal's steps in the database go with it). The caller confirms first. */
    fun deleteGoal(id: Long) = write("delete goal") { dao.deleteGoal(id) }
}

/** The Bible canvas: cards (people, things) and the labelled links between them. Every write is one statement or one transaction. */
class BibleViewModel(private val notebook: VesperHost) : ViewModel() {
    private val dao get() = notebook.db.bibleDao()

    val cards: StateFlow<List<app.vesper.data.BibleCard>?> = dao.observeCards().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val links: StateFlow<List<app.vesper.data.BibleLink>?> = dao.observeLinks().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun write(what: String, block: suspend () -> Unit): Job = notebook.appScope.launch {
        try { block() } catch (e: Exception) { notebook.reportError(what, e) }
    }

    fun addCard(name: String, x: Float, y: Float) {
        val clean = name.trim().ifEmpty { return }
        write("add card") { dao.insertCard(app.vesper.data.BibleCard(name = clean, x = x, y = y)) }
    }

    fun renameCard(id: Long, name: String) {
        val clean = name.trim().ifEmpty { return }
        write("rename card") { dao.renameCard(id, clean) }
    }

    fun setNote(id: Long, note: String) = write("save note") { dao.setNote(id, note.trim()) }
    fun setKind(id: Long, kind: String) = write("change kind") { dao.setKind(id, kind) }
    fun moveCard(id: Long, x: Float, y: Float) = write("move card") { dao.moveCard(id, x, y) }
    fun setLocked(id: Long, locked: Boolean) = write("lock card") { dao.setLocked(id, locked) }

    /** The card and every link to or from it, together. The caller confirms first. */
    fun deleteCard(card: app.vesper.data.BibleCard) = write("delete card") {
        notebook.db.useWriterConnection { w -> w.immediateTransaction { dao.deleteLinksOf(card.uid); dao.deleteCardRow(card.id) } }
    }

    fun addLink(fromUid: String, toUid: String, label: String) {
        val clean = label.trim().ifEmpty { return }
        if (fromUid == toUid) return
        write("add link") { dao.insertLink(app.vesper.data.BibleLink(fromUid = fromUid, toUid = toUid, label = clean)) }
    }

    fun renameLink(id: Long, label: String) {
        val clean = label.trim().ifEmpty { return }
        write("rename link") { dao.renameLink(id, clean) }
    }

    fun deleteLink(id: Long) = write("delete link") { dao.deleteLink(id) }
}
