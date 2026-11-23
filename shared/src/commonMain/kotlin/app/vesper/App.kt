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
import app.vesper.ui.placeName
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
    val brainCamera = remember { BrainCamera() }
    // The open item page: its id (0 = a new item), where a new one goes, and a counter that gives each opening its own editor.
    var pageId by rememberSaveable { mutableLongStateOf(NO_PAGE) }
    var pageLobe by rememberSaveable { mutableLongStateOf(-1L) }
    var pageBranch by rememberSaveable { mutableLongStateOf(-1L) }
    var pageSession by rememberSaveable { mutableIntStateOf(0) }
    var pageFrom by remember { mutableStateOf(Offset.Unspecified) } // where its card grows from (root px)
    val cardP = remember { Animatable(0f) } // how open the card is; the world behind dims and shrinks with it
    var homeSignal by remember { mutableIntStateOf(0) } // the Brain tab tapped again: close the cards, fly back to the map
    var movedMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(movedMsg) { if (movedMsg != null) { kotlinx.coroutines.delay(2200); movedMsg = null } }

    val brainVm = viewModel { BrainViewModel(host) }
    remember(brainVm) { brainVm.loadLayout() } // where the notes were dragged, before the first frame
    val shrineVm = viewModel { ShrineViewModel(host) }
    val moonsVm = viewModel { MoonsViewModel(host) }
    val learnVm = viewModel { LearnViewModel(host) }
    val bibleVm = viewModel { BibleViewModel(host) }
    val goals by learnVm.goals.collectAsStateWithLifecycle()
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
    val brainSounds by brainVm.sounds.collectAsStateWithLifecycle()

    // Hoisted so the Grimoire keeps its scroll position while another tab (or an item page) is shown.
    val shrineListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // The Daily ticks are only good for the day they were made: cleared on start, on resume, when the date turns while open, and
    // whenever rows change (a stale tick arriving by sync); see [resetDaily].
    var resumes by remember { mutableIntStateOf(0) }
    var today by remember { mutableStateOf(java.time.LocalDate.now()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); java.time.LocalDate.now().let { if (it != today) today = it } } }
    LaunchedEffect(today, resumes, ideas) {
        val zone = java.time.ZoneId.systemDefault()
        val day = java.time.LocalDate.now(zone).also { if (it != today) today = it }
        try { resetDaily(host.db, host.settings, day, dayStart(day, zone)) } catch (e: Exception) { host.reportError("reset daily", e) }
    }
    var remindAt by remember { mutableStateOf(dailyRemindHour(host.settings)) }

    // Automatic sync runs only while the app is on screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) host.sync.setForeground(true)
            if (event == Lifecycle.Event.ON_RESUME) { alarmProblem = host.alarms.problem(); resumes++ }
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
                    else -> homeSignal++
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
        val footer = lobes?.firstOrNull { it.id == pageLobe }?.let(::placeName).orEmpty().uppercase()
        NoteCard(vm, pageFrom, cardP, footer, lobes.orEmpty(), homeSignal, brainVm::lobeNamed, onMoved = { movedMsg = "moved to $it" }, onClosed = { pageId = NO_PAGE })
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
                    onOpenIdea = { id, lobeId, branchId, from -> pageSession++; pageLobe = lobeId; pageBranch = branchId ?: -1L; pageFrom = from; pageId = id },
                    onNewIn = { name, from -> brainVm.lobeNamed(name) { lobeId -> pageSession++; pageLobe = lobeId; pageBranch = -1L; pageFrom = from; pageId = 0L } },
                    soundsOn = brainSounds,
                    onToggleSounds = brainVm::toggleSounds,
                    onSaveLayout = brainVm::saveLayout,
                    onResetLayout = brainVm::resetLayout,
                    vault = host.vault,
                    sync = host.sync,
                    appScope = host.appScope,
                    goals = goals,
                    homeSignal = homeSignal,
                    learn = learnVm,
                    bible = bibleVm,
                    onDailyAdd = brainVm::addDaily,
                    onDailyDone = { i, done -> brainVm.setDone(i.id, done) },
                    onRenameIdea = { i, name -> brainVm.saveIdea(i, name) },
                    onDeleteIdea = { brainVm.deleteIdea(it.id) },
                    remindAt = remindAt,
                    onRemind = if (host.alarms.supported) ({
                        val next = nextRemindHour(remindAt)
                        remindAt = next
                        setDailyRemindHour(host.settings, next)
                        if (next != null) host.alarms.requestPermissions()
                        host.alarms.scheduleDaily(next)
                    }) else null,
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
            shared,
            onNamed = { name -> brainVm.lobeNamed(name) { brainVm.addShared(shared, it) }; onSharedHandled() },
            onCancel = onSharedHandled,
        )
    }
}
