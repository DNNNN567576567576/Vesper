package app.vesper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.vesper.NoteEditorViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteEditorScreen(vm: NoteEditorViewModel, onClose: () -> Unit, onOpenPage: (parent: Long, page: Long?) -> Unit) {
    val focusManager = LocalFocusManager.current
    val titleFocus = remember { FocusRequester() }
    val closeNow by rememberUpdatedState(onClose)
    val scope = rememberCoroutineScope()
    val pages by vm.pages.collectAsState()
    var burnPage by remember { mutableStateOf<Long?>(null) }
    val canAddPage = vm.loaded && vm.parentId == null

    /** Page = null opens a fresh page. The parent's text is saved first so its editor reloads it intact. */
    fun openPage(page: Long?) {
        scope.launch {
            vm.flushForPage().join()
            val parent = vm.noteId ?: return@launch
            focusManager.clearFocus(); onOpenPage(parent, page)
        }
    }

    fun leave() {
        vm.flush()
        focusManager.clearFocus()
        closeNow()
    }

    BackHandler { leave() }

    // Persist whenever the app leaves the foreground (home button, app switch, screen off).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.flush()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // A brand-new note starts with the cursor in the title and the keyboard up.
    LaunchedEffect(Unit) { if (!vm.exists) titleFocus.requestFocus() }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(58.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SigilFab("Back", ::leave, back = true)
            if (vm.parentId != null) {
                Text(
                    "${vm.parentTitle.trim().ifEmpty { "untitled" }} / ${vm.title.trim().ifEmpty { "untitled" }}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp, end = 14.dp),
                    style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, color = Px.TextDim.copy(alpha = 0.7f)),
                )
            }
            if (canAddPage) {
                Spacer(Modifier.weight(1f))
                SigilFab("New page", { openPage(null) })
            }
        }

        BasicTextField(
            value = vm.title,
            onValueChange = vm::onTitle,
            enabled = vm.loaded,
            singleLine = true,
            textStyle = TextStyle(fontFamily = PageFont, fontSize = 26.sp, lineHeight = 28.sp, color = Px.Text),
            cursorBrush = SolidColor(Px.Lavender),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).focusRequester(titleFocus),
            decorationBox = { inner ->
                Box {
                    if (vm.title.isEmpty()) {
                        Text("Title", style = TextStyle(fontFamily = PageFont, fontSize = 26.sp, lineHeight = 28.sp, color = Px.TextDim.copy(alpha = 0.5f)))
                    }
                    inner()
                }
            },
        )
        // Pixel divider: a dashed line of 4dp blocks.
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(40) { Box(Modifier.weight(1f).height(2.dp).background(Px.Border)) }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val minH = maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                BasicTextField(
                    value = vm.content,
                    onValueChange = vm::onContent,
                    enabled = vm.loaded,
                    textStyle = TextStyle(fontFamily = PageFont, fontSize = 22.sp, lineHeight = 26.sp, color = Px.Text),
                    cursorBrush = SolidColor(Px.Lavender),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().heightIn(min = if (pages.isNotEmpty()) 52.dp else minH).padding(horizontal = 20.dp, vertical = 14.dp),
                    decorationBox = { inner ->
                        Box {
                            if (vm.content.isEmpty()) {
                                Text("Write something…", style = TextStyle(fontFamily = PageFont, fontSize = 22.sp, lineHeight = 26.sp, color = Px.TextDim.copy(alpha = 0.5f)))
                            }
                            inner()
                        }
                    },
                )
                if (pages.isNotEmpty()) {
                    Text(
                        "pages",
                        modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
                        style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, color = Px.TextDim.copy(alpha = 0.7f)),
                    )
                    val rowStyle = TextStyle(fontFamily = PageFont, fontSize = 22.sp, lineHeight = 26.sp, color = Px.Lavender)
                    pages.forEach { p ->
                        Text(
                            p.title.trim().ifEmpty { "untitled" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                                .combinedClickable(onClick = { openPage(p.id) }, onLongClick = { burnPage = p.id })
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            style = rowStyle,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    burnPage?.let { pid ->
        PixelDialog(onDismiss = { burnPage = null }) {
            Text(
                "Burn this page?",
                style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, letterSpacing = 0.5.sp, color = Px.Lavender),
            )
            Spacer(Modifier.height(10.dp))
            Text("Gone for good. No undo.", style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, color = Px.TextDim))
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                PixelButton("keep", { burnPage = null })
                PixelButton("burn", { burnPage = null; vm.deletePage(pid) }, primary = true)
            }
        }
    }
}

@Composable
fun PixelDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 28.dp)
                .fillMaxWidth()
                .pixelCard(fill = Px.Elevated, border = Px.Primary, notch = 4.dp)
                .padding(20.dp),
            content = { content() },
        )
    }
}

@Composable
fun AddTaskDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    fun submit() {
        if (text.isNotBlank()) onAdd(text)
        onDismiss()
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    PixelDialog(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelIcon(Icons.Star, Px.Magenta, pixel = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("NEW TASK", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, letterSpacing = 1.sp, color = Px.Lavender))
        }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(3.dp)) {
            val s = 5.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawRect(Px.Border, Offset(x, 0f), Size(s / 2f, size.height))
                x += s
            }
        }
        Spacer(Modifier.height(16.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, lineHeight = 20.sp, color = Px.Text),
            cursorBrush = SolidColor(Px.Magenta),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().pixelCard(fill = Px.Bg, shadow = false, notch = 2.dp)
                        .padding(horizontal = 14.dp, vertical = 17.dp)
                ) {
                    if (text.isEmpty()) Text(
                        "What needs doing?",
                        style = TextStyle(fontFamily = PixelFont, fontSize = 10.sp, lineHeight = 18.sp, color = Px.TextDim.copy(alpha = 0.45f)),
                    )
                    inner()
                }
            },
        )
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            PixelButton("CANCEL", onDismiss)
            PixelButton("ADD", ::submit, primary = true)
        }
    }
}
