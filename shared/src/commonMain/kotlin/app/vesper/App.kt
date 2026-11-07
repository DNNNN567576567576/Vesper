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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.vesper.ui.BrainCamera
import app.vesper.ui.BrainScreen
import app.vesper.ui.IdeaPageScreen
import app.vesper.ui.MoonsScreen
import app.vesper.ui.PixelNavBar
import app.vesper.ui.Px
import app.vesper.ui.SharePicker
import app.vesper.ui.SharedText
import app.vesper.ui.ShrineScreen
import app.vesper.ui.WallBackdrop
import app.vesper.ui.WallKind
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
    val brainCamera = remember { BrainCamera() }
    // The open item page: its id (0 = a new item), where a new one goes, and a counter that gives each opening its own editor.
    var pageId by rememberSaveable { mutableLongStateOf(NO_PAGE) }
    var pageLobe by rememberSaveable { mutableLongStateOf(-1L) }
    var pageBranch by rememberSaveable { mutableLongStateOf(-1L) }
    var pageSession by rememberSaveable { mutableIntStateOf(0) }

    val brainVm = viewModel { BrainViewModel(host) }
    val shrineVm = viewModel { ShrineViewModel(host) }
    val moonsVm = viewModel { MoonsViewModel(host) }
    val plans by moonsVm.plans.collectAsStateWithLifecycle()
    val focus by moonsVm.focus.collectAsStateWithLifecycle()
    val moonsReset = remember { mutableIntStateOf(0) }
    val posts by shrineVm.posts.collectAsStateWithLifecycle()
    val lobes by brainVm.lobes.collectAsStateWithLifecycle()
    val ideas by brainVm.ideas.collectAsStateWithLifecycle()
    val branches by brainVm.branches.collectAsStateWithLifecycle()

    // Hoisted so the Grimoire keeps its scroll position while another tab (or an item page) is shown.
    val shrineListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Automatic sync runs only while the app is on screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) host.sync.setForeground(true)
            if (event == Lifecycle.Event.ON_STOP) host.sync.setForeground(false)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Wide (desktop window): left rail. Narrower than 700dp, and always on phones, the bottom-bar layout.
    val wide = maxWidth >= 700.dp
    val fullPage = pageId != NO_PAGE

    val onNav: (Int) -> Unit = { index ->
        if (index == tab) {
            // Re-tapping the active tab jumps back to the top.
            scope.launch {
                when (index) {
                    1 -> shrineListState.animateScrollToItem(0) // reversed list: item 0 = newest
                    2 -> moonsReset.intValue++ // jump back to today
                    else -> { brainCamera.x = 0f; brainCamera.y = 0f; brainCamera.scale = 0.85f }
                }
            }
        } else {
            tab = index
        }
    }

    val page: @Composable (Long) -> Unit = { target ->
        // The session is in the key so the outgoing page keeps its own editor while it slides away.
        val vm = viewModel(key = "idea-$pageSession") {
            IdeaEditorViewModel(host, target.takeIf { it != 0L }, pageLobe, pageBranch.takeIf { it >= 0 })
        }
        IdeaPageScreen(vm, onClose = { pageId = NO_PAGE })
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
                    onDeleteLobe = brainVm::deleteLobe,
                    onOpenIdea = { id, lobeId, branchId -> pageSession++; pageLobe = lobeId; pageBranch = branchId ?: -1L; pageId = id },
                    onAddBranch = brainVm::addBranch,
                    onRenameBranch = brainVm::renameBranch,
                    onDeleteBranch = brainVm::deleteBranch,
                    vault = host.vault,
                    sync = host.sync,
                    appScope = host.appScope,
                )
            }
        }
    }

    CompositionLocalProvider(LocalWide provides wide) {
    Box(Modifier.fillMaxSize().background(if (fullPage) Px.Bg else if (tab == 1) Color(0xFF08040C) else if (tab == 2) Color(0xFF06030C) else Px.Bg)) {
        // An item's page shares the rotting wall with the old scroll editor; Brain, Grimoire and Moons draw their own opaque backdrops.
        WallBackdrop(if (fullPage) WallKind.Notes else WallKind.None)

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                PixelNavRail(tab, onNav)
                Box(Modifier.weight(1f).fillMaxHeight()) { if (fullPage) page(pageId) else tabContent() }
            }
        } else {
            // The page slides in over the map, like turning to a fresh page.
            AnimatedContent(
                targetState = pageId,
                transitionSpec = {
                    if (targetState != NO_PAGE) {
                        (fadeIn(tween(190)) + slideInHorizontally(tween(300)) { it / 9 }) togetherWith
                            fadeOut(tween(130))
                    } else {
                        fadeIn(tween(200)) togetherWith
                            (fadeOut(tween(150)) + slideOutHorizontally(tween(260)) { it / 9 })
                    }
                },
                label = "page",
            ) { target ->
                if (target != NO_PAGE) {
                    page(target)
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
