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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.draw.drawBehind
import kotlin.math.ceil
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.sin
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.PI
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.vesper.NoteEditorViewModel

private val ListGapStyle = ParagraphStyle(
    lineHeight = 38.sp, // 26sp body line + ~12dp; Bottom puts the extra above the text
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Bottom, LineHeightStyle.Trim.None),
)

/** Visual only: half a line of air before each "- item" line (not the first line). Offsets are untouched. */
private val ListGap = VisualTransformation { text ->
    val s = text.text
    val out = buildAnnotatedString {
        append(text)
        var i = 0
        while (i < s.length) {
            val e = s.indexOf('\n', i).let { if (it < 0) s.length else it + 1 }
            if (i > 0 && s.substring(i, e).trimStart(' ').startsWith("-")) addStyle(ListGapStyle, i, e)
            i = e
        }
    }
    TransformedText(out, OffsetMapping.Identity)
}

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
                    visualTransformation = ListGap,
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
        TornNoteDialog(
            title = "Burn \"${(pages.firstOrNull { it.id == pid }?.title ?: "").trim().ifEmpty { "Untitled page" }}\"?",
            body = "No undo.",
            onKeep = { burnPage = null },
            onBurn = { burnPage = null; vm.deletePage(pid) },
        )
    }
}

/** Torn-paper confirmation used for every burn: rotting violet paper, hard dark shadow, plain underlined words. */
@Composable
fun TornNoteDialog(title: String, body: String, onKeep: () -> Unit, onBurn: () -> Unit) {
    val px = rememberRotPxSize().toInt()
    val inset = with(LocalDensity.current) { (PAPER_SHADOW_PX * px).toDp() }
    val ink = Color(0xFF140A1C)
    val seed = remember(title) { title.hashCode().toLong() }
    @Composable
    fun Word(text: String, color: Color, onClick: () -> Unit) = Box(
        Modifier.defaultMinSize(48.dp, 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(fontFamily = PageFont, fontSize = 22.sp, color = color, textDecoration = TextDecoration.Underline))
    }
    Dialog(onDismissRequest = onKeep, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 28.dp)
                .fillMaxWidth()
                .drawWithCache {
                    val paper = Pix(ceil(size.width / px).toInt(), ceil(size.height / px).toInt())
                    renderPaper(paper, seed, tone = 0, shadowRgb = 0x07030B)
                    onDrawBehind { drawIntoCanvas { paper.blit(it.nativeCanvas, 0, 0, px) } }
                }
                .padding(start = 20.dp, end = 12.dp + inset, top = 22.dp, bottom = 6.dp + inset),
        ) {
            Text(title, style = TextStyle(fontFamily = PageFont, fontSize = 22.sp, lineHeight = 24.sp, color = ink))
            Spacer(Modifier.height(6.dp))
            Text(body, style = TextStyle(fontFamily = PageFont, fontSize = 17.sp, lineHeight = 20.sp, color = ink))
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Word("keep", ink, onKeep)
                Word("burn", Color(0xFF5A1E48), onBurn)
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

/** "Smoke words": a big flickering flame over a bare text field on a dark scrim. Done lights it; tapping outside or back cancels. */
@Composable
fun AddTaskDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    fun submit() {
        if (text.isNotBlank()) onAdd(text)
        onDismiss()
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var clock by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) clock = withFrameNanos { it } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF05020A).copy(alpha = 0.88f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.fillMaxWidth(0.7f), horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontFamily = PageFont, fontSize = 26.sp, color = Color(0xFFE6DCFF), textAlign = TextAlign.Center,
                            shadow = Shadow(Color(0xFFB69AFF).copy(alpha = 0.8f), Offset.Zero, 14f),
                        ),
                        cursorBrush = SolidColor(Color.Transparent),
                        onTextLayout = { layout = it },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "New flame" },
                        decorationBox = { inner ->
                            // end padding = flame width so text + flame sit centred together
                            Box(Modifier.fillMaxWidth().padding(end = if (text.isEmpty()) 0.dp else (CW * CELL_DP).dp), contentAlignment = Alignment.Center) {
                                if (text.isEmpty()) Text("\u2026", style = TextStyle(fontFamily = PageFont, fontSize = 26.sp, color = Color(0xFFE6DCFF).copy(alpha = 0.3f)))
                                Box(Modifier.drawWithContent {
                                    drawContent()
                                    // caret is always at the end (plain String value)
                                    val r = layout?.getCursorRect(text.length) ?: return@drawWithContent
                                    val left = if (text.isEmpty()) r.left - CW * CELL_DP / 2 * density else r.right + 1.dp.toPx()
                                    drawSpiritFlame(left, r.bottom, clock / 1e9f)
                                }) { inner() }
                            }
                        },
                    )
                    Box(
                        Modifier.fillMaxWidth().height(2.dp)
                            .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFF9A6BE0), Color.Transparent)))
                    )
                }
            }
        }
    }
}

private val FLAME_PAL = intArrayOf(
    0, 0xFF140628.toInt(), 0xFF280A4B.toInt(), 0xFF3E126E.toInt(), 0xFF551E96.toInt(), 0xFF6E32B9.toInt(),
    0xFF8C50D2.toInt(), 0xFFAA78EB.toInt(), 0xFFC8A5FA.toInt(), 0xFFE6D7FF.toInt(), 0xFFFAF5FF.toInt(),
).map { Color(it) }

private const val CW = 8
private const val CH = 12
private const val CELL_DP = 2.5f

/** Pixel spirit flame (CW x CH cells, cell = CELL_DP) with its bottom-left at (left, bottom); t in seconds. */
private fun DrawScope.drawSpiritFlame(left: Float, bottom: Float, t: Float) {
    val cell = CELL_DP * density
    val top = bottom - CH * cell
    for (y in 0 until CH) {
        val b = (y + 0.5f) / CH
        val cx = CW / 2f + sin(t * 2 + y * 0.3f) * 1.5f * (1 - b)
        val hw = sin(b * PI.toFloat() * 0.95f) * CW * 0.32f * b.pow(0.4f) * (0.9f + 0.1f * sin(t * 5 + y))
        if (hw <= 0f) continue
        for (x in 0 until CW) {
            var v = (1 - abs(x + 0.5f - cx) / hw + sin(x + t * 6) * 0.12f * (1 - b)) * 10 * minOf(1f, b * 1.5f)
            if (b > 0.75f) v += 2
            val i = minOf(10, v.toInt())
            if (v > 0 && i > 0) drawRect(FLAME_PAL[i], Offset(left + x * cell, top + y * cell), Size(cell, cell))
        }
    }
}
