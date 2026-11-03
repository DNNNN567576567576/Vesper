package app.vesper

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.vesper.ui.AddTaskDialog
import app.vesper.ui.BrainCamera
import app.vesper.ui.BrainScreen
import app.vesper.ui.MoonsScreen
import app.vesper.ui.NoteEditorScreen
import app.vesper.ui.NotesScreen
import app.vesper.ui.PixelNavBar
import app.vesper.ui.Px
import app.vesper.ui.ShrineScreen
import app.vesper.ui.TasksScreen
import app.vesper.ui.WallBackdrop
import app.vesper.ui.WallKind
import kotlinx.coroutines.launch

/** Launcher shortcut actions (see res/xml/shortcuts.xml in :app). */
const val ACTION_NEW_NOTE = "app.vesper.NEW_NOTE"
const val ACTION_NEW_TASK = "app.vesper.NEW_TASK"

private const val NO_EDITOR = -2L
private const val NEW_NOTE = -1L

/**
 * The whole app below the platform shell: five tabs and the editor. [request] is a launcher-shortcut action
 * ([ACTION_NEW_NOTE], [ACTION_NEW_TASK]); [sharedNote] is a note just created from a share, opened in the editor.
 */
@Composable
fun App(host: VesperHost, request: String?, onRequestHandled: () -> Unit, sharedNote: Long?, onSharedHandled: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 = Notes (default), 1 = Tasks, 2 = Brain, 3 = Shrine, 4 = Moons
    var selectedLobe by rememberSaveable { mutableLongStateOf(-1L) }
    val brainCamera = remember { BrainCamera() }
    var editorTarget by rememberSaveable { mutableLongStateOf(NO_EDITOR) }
    var editorSession by rememberSaveable { mutableIntStateOf(0) }
    // Main scroll to return to when closing a page (back-stack of one); NO_EDITOR = none.
    var editorParent by rememberSaveable { mutableLongStateOf(NO_EDITOR) }
    var addingTask by rememberSaveable { mutableStateOf(false) }

    val notesVm = viewModel { NotesViewModel(host) }
    val tasksVm = viewModel { TasksViewModel(host) }
    val brainVm = viewModel { BrainViewModel(host) }
    val shrineVm = viewModel { ShrineViewModel(host) }
    val moonsVm = viewModel { MoonsViewModel(host) }
    val plans by moonsVm.plans.collectAsStateWithLifecycle()
    val focus by moonsVm.focus.collectAsStateWithLifecycle()
    val moonsReset = remember { mutableIntStateOf(0) }
    val posts by shrineVm.posts.collectAsStateWithLifecycle()
    val lobes by brainVm.lobes.collectAsStateWithLifecycle()
    val ideas by brainVm.ideas.collectAsStateWithLifecycle()
    val notes by notesVm.notes.collectAsStateWithLifecycle()
    val tasks by tasksVm.tasks.collectAsStateWithLifecycle()
    val snuffedTasks by tasksVm.snuffed.collectAsStateWithLifecycle()

    // Hoisted so each tab keeps its scroll position while the other tab (or the editor) is shown.
    val notesListState = rememberLazyListState()
    val tasksListState = rememberLazyListState()
    val shrineListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(sharedNote) {
        if (sharedNote != null) {
            tab = 0
            editorSession++
            editorParent = NO_EDITOR
            editorTarget = sharedNote
            onSharedHandled()
        }
    }

    LaunchedEffect(request) {
        when (request) {
            ACTION_NEW_NOTE -> {
                tab = 0
                editorSession++
                editorParent = NO_EDITOR
                editorTarget = NEW_NOTE
                onRequestHandled()
            }
            ACTION_NEW_TASK -> {
                tab = 1
                addingTask = true
                onRequestHandled()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(if (editorTarget != NO_EDITOR) Px.Bg else if (tab == 3) Color(0xFF08040C) else if (tab == 4) Color(0xFF06030C) else Px.Bg)) {
        // Notes and the editor share the rotting wall, Tasks gets the candle crypt, Brain, Shrine and Moons draw their own opaque backdrops.
        WallBackdrop(
            when {
                editorTarget != NO_EDITOR -> WallKind.Notes
                tab == 0 -> WallKind.Notes
                tab == 1 -> WallKind.Candle
                else -> WallKind.None // Brain, Shrine and Moons draw their own backdrops
            }
        )

        // The editor slides in over the list, like turning to a fresh page.
        AnimatedContent(
            targetState = editorTarget,
            transitionSpec = {
                if (targetState != NO_EDITOR) {
                    (fadeIn(tween(190)) + slideInHorizontally(tween(300)) { it / 9 }) togetherWith
                        fadeOut(tween(130))
                } else {
                    fadeIn(tween(200)) togetherWith
                        (fadeOut(tween(150)) + slideOutHorizontally(tween(260)) { it / 9 })
                }
            },
            label = "editor",
        ) { target ->
            if (target != NO_EDITOR) {
                // Key has target too: the outgoing editor must not grab the incoming one's VM.
                val editorVm = viewModel(key = "editor-$editorSession-$target") {
                    NoteEditorViewModel(
                        host,
                        if (target == NEW_NOTE) null else target,
                        if (target == NEW_NOTE && editorParent != NO_EDITOR) editorParent else null,
                    )
                }
                NoteEditorScreen(
                    editorVm,
                    onClose = {
                        // Closing a page returns to its main scroll's editor, not the list.
                        val parent = editorParent
                        editorParent = NO_EDITOR
                        if (parent != NO_EDITOR) editorSession++
                        editorTarget = parent
                    },
                    onOpenPage = { parent, page -> editorParent = parent; editorSession++; editorTarget = page ?: NEW_NOTE },
                )
            } else {
                Scaffold(
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        PixelNavBar(tab) { index ->
                            if (index == tab) {
                                // Re-tapping the active tab jumps back to the top.
                                scope.launch {
                                    when (index) {
                                        0 -> notesListState.animateScrollToItem(0)
                                        1 -> tasksListState.animateScrollToItem(0)
                                        3 -> shrineListState.animateScrollToItem(0) // reversed list: item 0 = newest
                                        4 -> moonsReset.intValue++ // jump back to today
                                        else -> { brainCamera.x = 0f; brainCamera.y = 0f; brainCamera.scale = 0.85f }
                                    }
                                }
                            } else {
                                tab = index
                            }
                        }
                    },
                ) { inner ->
                    Box(Modifier.fillMaxSize().padding(inner).statusBarsPadding()) {
                        Crossfade(targetState = tab, animationSpec = tween(170), label = "tab") { current ->
                            if (current == 0) {
                                NotesScreen(
                                    notes = notes,
                                    listState = notesListState,
                                    onOpen = { id -> editorSession++; editorTarget = id },
                                    onNew = { editorSession++; editorTarget = NEW_NOTE },
                                    onDelete = notesVm::delete,
                                    vm = notesVm,
                                )
                            } else if (current == 1) {
                                TasksScreen(
                                    tasks = tasks,
                                    snuffed = snuffedTasks,
                                    listState = tasksListState,
                                    onComplete = tasksVm::complete,
                                    onRelight = tasksVm::relight,
                                    onAdd = { addingTask = true },
                                    vm = tasksVm,
                                )
                            } else if (current == 3) {
                                ShrineScreen(
                                    posts = posts,
                                    listState = shrineListState,
                                    draft = shrineVm.draft,
                                    draftPhoto = shrineVm.draftPhoto,
                                    sending = shrineVm.sending,
                                    onDraft = { shrineVm.draft = it },
                                    onDraftPhoto = { shrineVm.draftPhoto = it },
                                    onSend = shrineVm::send,
                                    onEdit = shrineVm::edit,
                                    onDelete = shrineVm::delete,
                                    media = host.shrineMedia,
                                )
                            } else if (current == 4) {
                                MoonsScreen(
                                    plans = plans,
                                    focus = focus,
                                    lobes = lobes,
                                    resetSignal = moonsReset.intValue,
                                    draft = moonsVm.draft,
                                    onDraft = { moonsVm.draft = it },
                                    onAddPlan = moonsVm::addPlan,
                                    onSetDone = moonsVm::setDone,
                                    onEditPlan = moonsVm::editPlan,
                                    onDeletePlan = moonsVm::deletePlan,
                                    onToggleFocus = moonsVm::toggleFocus,
                                )
                            } else {
                                BrainScreen(
                                    lobes = lobes,
                                    ideas = ideas,
                                    notes = notes,
                                    camera = brainCamera,
                                    selectedLobe = selectedLobe,
                                    onSelectLobe = { selectedLobe = it },
                                    onAddLobe = { name -> brainVm.addLobe(name) { selectedLobe = it } },
                                    onDeleteLobe = brainVm::deleteLobe,
                                    onSaveIdea = brainVm::saveIdea,
                                    onDeleteIdea = brainVm::deleteIdea,
                                    onOpenNote = { id -> editorSession++; editorTarget = id },
                                    vault = host.vault,
                                    appScope = host.appScope,
                                )
                            }
                        }
                    }
                }
                if (addingTask) {
                    AddTaskDialog(onDismiss = { addingTask = false }, onAdd = tasksVm::add)
                }
            }
        }
    }
}
