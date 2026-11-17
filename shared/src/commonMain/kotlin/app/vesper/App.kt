package app.vesper

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import app.vesper.ui.LocalWide
import app.vesper.ui.PixelNavRail
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.navigationBarsPadding
import app.vesper.ui.PixelFont
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.vesper.ui.BrainCamera
import app.vesper.ui.BrainScreen
import app.vesper.ui.NoteCard
import app.vesper.ui.MoonsScreen
import app.vesper.ui.AlarmUi
import app.vesper.ui.CalculatorScreen
import app.vesper.ui.PixelNavBar
import app.vesper.ui.Px
import app.vesper.ui.SharePicker
import app.vesper.ui.SharedText
import app.vesper.ui.ShrineScreen
import kotlinx.coroutines.launch

private const val NO_PAGE = -2L

/**
 * The whole app below the platform shell: three tabs (Brain, Grimoire, Moons) and an item's page over them.
 * [shared] is text just sent to Vesper from another app; the picker asks where to keep it.
 */
@Composable
fun App(host: VesperHost, shared: SharedText?, onSharedHandled: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 = Brain (default), 1 = Shrine, 2 = Moons
    var selectedLobe by rememberSaveable { mutableLongStateOf(-1L) }
    var learnAt by rememberSaveable { mutableLongStateOf(-2L) } // the learn brain: -2 closed, -1 its view, else a goal's page
    val brainCamera = remember { BrainCamera() }
    // The open item page: its id (0 = a new item), where a new one goes, and a counter that gives each opening its own editor.
    var pageId by rememberSaveable { mutableLongStateOf(NO_PAGE) }
    var pageLobe by rememberSaveable { mutableLongStateOf(-1L) }
    var pageBranch by rememberSaveable { mutableLongStateOf(-1L) }
    var pageSession by rememberSaveable { mutableIntStateOf(0) }
    var pageFrom by remember { mutableStateOf(Offset.Unspecified) } // where its card grows from (root px)
    val cardP = remember { Animatable(0f) } // how open the card is; the world behind dims and shrinks with it
    var homeSignal by remember { mutableIntStateOf(0) } // the Brain tab tapped again inside a world: close the card, fly back to the map
    var movedMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(movedMsg) { if (movedMsg != null) { kotlinx.coroutines.delay(2200); movedMsg = null } }

    val brainVm = viewModel { BrainViewModel(host) }
    val shrineVm = viewModel { ShrineViewModel(host) }
    val moonsVm = viewModel { MoonsViewModel(host) }
    val learnVm = viewModel { LearnViewModel(host) }
    val goals by learnVm.goals.collectAsStateWithLifecycle()
    val steps by learnVm.steps.collectAsStateWithLifecycle()
    val plans by moonsVm.plans.collectAsStateWithLifecycle()
    val focus by moonsVm.focus.collectAsStateWithLifecycle()
    val moonsReset = remember { mutableIntStateOf(0) }
    val alarms by moonsVm.alarms.collectAsStateWithLifecycle()
    val alarmHistory by moonsVm.alarmHistory.collectAsStateWithLifecycle()
    var alarmProblem by remember { mutableStateOf(host.alarms.problem()) }
    LaunchedEffect(alarms) { alarmProblem = host.alarms.problem() }
    val posts by shrineVm.posts.collectAsStateWithLifecycle()
    val lobes by brainVm.lobes.collectAsStateWithLifecycle()
    val ideas by brainVm.ideas.collectAsStateWithLifecycle()
    val branches by brainVm.branches.collectAsStateWithLifecycle()
    val brainShowAll by brainVm.showAll.collectAsStateWithLifecycle()
    val brainSounds by brainVm.sounds.collectAsStateWithLifecycle()

    // Hoisted so the Grimoire keeps its scroll position while another tab (or an item page) is shown.
    val shrineListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Automatic sync runs only while the app is on screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) host.sync.setForeground(true)
            if (event == Lifecycle.Event.ON_RESUME) alarmProblem = host.alarms.problem()
            if (event == Lifecycle.Event.ON_STOP) host.sync.setForeground(false)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Wide (desktop window): left rail. Narrower than 700dp, and always on phones, the bottom-bar layout.
    val wide = maxWidth >= 700.dp
    val cardOpen = pageId != NO_PAGE

    val onNav: (Int) -> Unit = { index ->
        if (index == tab) {
            // Re-tapping the active tab jumps back to the top.
            scope.launch {
                when (index) {
                    1 -> shrineListState.animateScrollToItem(0) // reversed list: item 0 = newest
                    2 -> moonsReset.intValue++ // jump back to today
                    3 -> {}
                    else -> if (selectedLobe >= 0 || learnAt >= -1) homeSignal++ else { brainCamera.x = 0f; brainCamera.y = 0f; brainCamera.scale = 0.85f }
                }
            }
        } else {
            tab = index
        }
    }

    val card: @Composable (Long) -> Unit = { target ->
        // The session is in the key so each opening gets its own editor.
        val vm = viewModel(key = "idea-$pageSession") {
            IdeaEditorViewModel(host, target.takeIf { it != 0L }, pageLobe, pageBranch.takeIf { it >= 0 })
        }
        val footer = listOfNotNull(branches?.firstOrNull { it.id == pageBranch }?.name, lobes?.firstOrNull { it.id == pageLobe }?.name)
            .joinToString(" \u00B7 ").uppercase()
        NoteCard(vm, pageFrom, cardP, footer, lobes.orEmpty(), branches.orEmpty(), homeSignal, onMoved = { movedMsg = "moved to $it" }, onClosed = { pageId = NO_PAGE })
    }

    val tabContent: @Composable () -> Unit = {
        Crossfade(targetState = tab, animationSpec = tween(170), label = "tab") { current ->
            if (current == 1) {
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
            } else if (current == 3) {
                CalculatorScreen()
            } else if (current == 2) {
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
                    goals = goals,
                    steps = steps,
                    onSetStepDone = { st, done -> learnVm.setStepDone(st, done) },
                    alarmUi = if (host.alarms.supported) AlarmUi(
                        alarms = alarms,
                        history = alarmHistory,
                        problem = alarmProblem,
                        onFix = { alarmProblem?.let(host.alarms::openSettings) },
                        onSave = { a -> if (a.id == 0L) host.alarms.requestPermissions(); moonsVm.saveAlarm(a) },
                        onDelete = moonsVm::deleteAlarm,
                        onEnabled = moonsVm::setAlarmEnabled,
                        onClearHistory = moonsVm::clearAlarmHistory,
                    ) else null,
                )
            } else {
                BrainScreen(
                    lobes = lobes,
                    ideas = ideas,
                    branches = branches,
                    camera = brainCamera,
                    selectedLobe = selectedLobe,
                    onSelectLobe = { selectedLobe = it },
                    onAddLobe = { name -> brainVm.addLobe(name) { selectedLobe = it } },
                    onRenameLobe = brainVm::renameLobe,
                    onMoveLobe = brainVm::moveLobe,
                    onDeleteLobe = brainVm::deleteLobe,
                    onOpenIdea = { id, lobeId, branchId, from -> pageSession++; pageLobe = lobeId; pageBranch = branchId ?: -1L; pageFrom = from; pageId = id },
                    onAddBranch = brainVm::addBranch,
                    onRenameBranch = brainVm::renameBranch,
                    onDeleteBranch = brainVm::deleteBranch,
                    showAll = brainShowAll,
                    onToggleShowAll = brainVm::toggleShowAll,
                    soundsOn = brainSounds,
                    onToggleSounds = brainVm::toggleSounds,
                    vault = host.vault,
                    sync = host.sync,
                    appScope = host.appScope,
                    goals = goals,
                    steps = steps,
                    learnAt = learnAt,
                    onLearnAt = { learnAt = it },
                    homeSignal = homeSignal,
                    learn = learnVm,
                )
            }
        }
    }

    CompositionLocalProvider(LocalWide provides wide) {
    Box(Modifier.fillMaxSize().background(if (tab == 1) Color(0xFF08040C) else if (tab == 2) Color(0xFF06030C) else Px.Bg)) {
        // An open note is a card over the world, which dims and shrinks a little behind it.
        Box(Modifier.fillMaxSize().graphicsLayer { val k = 1f - 0.04f * cardP.value.coerceIn(0f, 1f); scaleX = k; scaleY = k }) {
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    PixelNavRail(tab, onNav)
                    Box(Modifier.weight(1f).fillMaxHeight()) { tabContent() }
                }
            } else {
                Scaffold(
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = { PixelNavBar(tab, onNav) },
                ) { inner ->
                    Box(Modifier.fillMaxSize().padding(inner).statusBarsPadding()) { tabContent() }
                }
            }
        }
        if (cardOpen) card(pageId)
        movedMsg?.let {
            Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 90.dp), contentAlignment = Alignment.BottomCenter) {
                Text(it, Modifier.background(Color(0xFF140B26)).padding(horizontal = 14.dp, vertical = 10.dp), maxLines = 2, style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, color = Px.Lavender))
            }
        }
    }
    }
    }

    val lobeList = lobes
    if (shared != null && lobeList != null) {
        SharePicker(
            shared, lobeList, branches.orEmpty(),
            onPick = { lobeId, branchId -> brainVm.addShared(shared, lobeId, branchId); onSharedHandled() },
            onNewLobe = { name -> brainVm.addLobe(name) { brainVm.addShared(shared, it, null) }; onSharedHandled() },
            onCancel = onSharedHandled,
        )
    }
}
