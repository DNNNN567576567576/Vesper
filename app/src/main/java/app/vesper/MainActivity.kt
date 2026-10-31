package app.vesper

import android.app.Application
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.vesper.ui.AddTaskDialog
import app.vesper.ui.BrainCamera
import app.vesper.ui.BrainScreen
import app.vesper.ui.NoteEditorScreen
import app.vesper.ui.NotesScreen
import app.vesper.ui.PixelNavBar
import app.vesper.ui.PixelTheme
import app.vesper.ui.Px
import app.vesper.ui.TasksScreen
import app.vesper.ui.WallBackdrop
import app.vesper.ui.WallKind
import kotlinx.coroutines.launch

/** Launcher shortcut actions (see res/xml/shortcuts.xml). */
const val ACTION_NEW_NOTE = "app.vesper.NEW_NOTE"
const val ACTION_NEW_TASK = "app.vesper.NEW_TASK"

class MainActivity : ComponentActivity() {
    /** Pending launcher-shortcut action, consumed by the composition. */
    private var request by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        // Only a fresh launch acts on the intent; a rotation must not reopen the editor.
        if (savedInstanceState == null) request = intent?.action
        setContent {
            PixelTheme {
                App(request = request, onRequestHandled = { request = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        request = intent.action
    }
}

private const val NO_EDITOR = -2L
private const val NEW_NOTE = -1L

@Composable
private fun App(request: String?, onRequestHandled: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 = Notes (default), 1 = Tasks, 2 = Brain
    var selectedLobe by rememberSaveable { mutableLongStateOf(-1L) }
    val brainCamera = remember { BrainCamera() }
    var editorTarget by rememberSaveable { mutableLongStateOf(NO_EDITOR) }
    var editorSession by rememberSaveable { mutableIntStateOf(0) }
    var addingTask by rememberSaveable { mutableStateOf(false) }

    val notesVm: NotesViewModel = viewModel()
    val tasksVm: TasksViewModel = viewModel()
    val brainVm: BrainViewModel = viewModel()
    val lobes by brainVm.lobes.collectAsStateWithLifecycle()
    val ideas by brainVm.ideas.collectAsStateWithLifecycle()
    val notes by notesVm.notes.collectAsStateWithLifecycle()
    val tasks by tasksVm.tasks.collectAsStateWithLifecycle()

    // Hoisted so each tab keeps its scroll position while the other tab (or the editor) is shown.
    val notesListState = rememberLazyListState()
    val tasksListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(request) {
        when (request) {
            ACTION_NEW_NOTE -> {
                tab = 0
                editorSession++
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

    Box(Modifier.fillMaxSize().background(Px.Bg)) {
        // Notes and the editor share the rotting wall, Tasks gets the candle crypt, Brain draws its own opaque map.
        WallBackdrop(
            when {
                editorTarget != NO_EDITOR -> WallKind.Notes
                tab == 0 -> WallKind.Notes
                tab == 1 -> WallKind.Candle
                else -> WallKind.None
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
                val editorVm: NoteEditorViewModel = viewModel(
                    key = "editor-$editorSession",
                    factory = viewModelFactory {
                        initializer {
                            NoteEditorViewModel(
                                this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application,
                                if (target == NEW_NOTE) null else target,
                            )
                        }
                    },
                )
                NoteEditorScreen(editorVm, onClose = { editorTarget = NO_EDITOR })
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
                                )
                            } else if (current == 1) {
                                TasksScreen(
                                    tasks = tasks,
                                    listState = tasksListState,
                                    onComplete = tasksVm::complete,
                                    onAdd = { addingTask = true },
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
