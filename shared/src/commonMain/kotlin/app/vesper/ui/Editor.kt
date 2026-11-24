package app.vesper.ui

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import kotlin.math.ceil
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import app.vesper.IdeaEditorViewModel

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

/** The title line of a page: PageFont 26sp, one line, Next moves on. */
@Composable
internal fun PageTitleField(value: String, onChange: (String) -> Unit, enabled: Boolean, modifier: Modifier = Modifier, onNext: () -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(fontFamily = PageFont, fontSize = 26.sp, lineHeight = 28.sp, color = Px.Text),
        cursorBrush = SolidColor(Px.Lavender),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { onNext() }),
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text("Title", style = TextStyle(fontFamily = PageFont, fontSize = 26.sp, lineHeight = 28.sp, color = Px.TextDim.copy(alpha = 0.5f)))
                }
                inner()
            }
        },
    )
}

/** Pixel divider: a dashed line of 4dp blocks. */
@Composable
internal fun PageDivider() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(40) { Box(Modifier.weight(1f).height(2.dp).background(Px.Border)) }
    }
}

/** The writing area of a page: PageFont 22sp on 26sp lines, Lavender cursor. [modifier] sets the height. */
@Composable
internal fun PageBodyField(value: String, onChange: (String) -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value,
        visualTransformation = ListGap,
        onValueChange = onChange,
        enabled = enabled,
        textStyle = TextStyle(fontFamily = PageFont, fontSize = 22.sp, lineHeight = 26.sp, color = Px.Text),
        cursorBrush = SolidColor(Px.Lavender),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text("Write something\u2026", style = TextStyle(fontFamily = PageFont, fontSize = 22.sp, lineHeight = 26.sp, color = Px.TextDim.copy(alpha = 0.5f)))
                }
                inner()
            }
        },
    )
}

/**
 * One Brain item on a full page (wall, header, title, divider and body) with an
 * optional link line under the body and a burn word top right. Saves 500 ms after typing stops, when
 * the app stops, when the page leaves composition (desktop) and on back.
 */
@Composable
fun IdeaPageScreen(vm: IdeaEditorViewModel, onClose: () -> Unit, onMove: (() -> Unit)? = null) {
    val focusManager = LocalFocusManager.current
    val titleFocus = remember { FocusRequester() }
    val closeNow by rememberUpdatedState(onClose)
    var burning by remember { mutableStateOf(false) }
    var ash by remember { mutableFloatStateOf(-1f) } // 0..1 while the burned page crumbles away (350 ms), then it goes
    val scope = rememberCoroutineScope()

    fun leave() {
        vm.flush()
        focusManager.clearFocus()
        closeNow()
    }

    BackHandler(enabled = true) { leave() }
    DisposableEffect(vm) { onDispose { if (saveOnDispose) vm.flush() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.flush() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // A brand-new item starts with the cursor in the title and the keyboard up.
    LaunchedEffect(Unit) { if (!vm.exists) titleFocus.requestFocus() }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().graphicsLayer { if (ash >= 0f) alpha = (1f - ash * 1.6f).coerceIn(0f, 1f) }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(58.dp), verticalAlignment = Alignment.CenterVertically) {
            SigilFab("Back", ::leave, back = true)
            Spacer(Modifier.weight(1f))
            if (vm.exists && onMove != null) {
                Box(
                    Modifier.defaultMinSize(48.dp, 48.dp).clickable(role = Role.Button, onClickLabel = "Move to", onClick = { focusManager.clearFocus(); onMove() }).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("move", style = TextStyle(fontFamily = PageFont, fontSize = 22.sp, color = Px.Lavender, textDecoration = TextDecoration.Underline))
                }
            }
            if (vm.exists) {
                Box(
                    Modifier.defaultMinSize(48.dp, 48.dp).clickable(role = Role.Button, onClickLabel = "Burn", onClick = { burning = true }).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("burn", style = TextStyle(fontFamily = PageFont, fontSize = 22.sp, color = Px.Lavender, textDecoration = TextDecoration.Underline))
                }
            }
        }
        PageTitleField(vm.title, vm::onTitle, vm.loaded, Modifier.focusRequester(titleFocus)) { focusManager.moveFocus(FocusDirection.Down) }
        PageDivider()
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val minH = maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                PageBodyField(vm.body, vm::onBody, vm.loaded, Modifier.heightIn(min = (minH - 56.dp).coerceAtLeast(52.dp)))
                IdeaLinkLine(vm.link, vm::onLink, vm.loaded)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (ash >= 0f) Canvas(Modifier.fillMaxSize()) { // the page crumbles: ash pixels fall off it and fade
        val px = 3.dp.toPx()
        ashFall(Offset(size.width / 2f, size.height * 0.4f), size.width * 0.8f, size.height * 0.5f, ash, px, 48)
    }
    }

    if (burning) {
        TornNoteDialog(
            title = "Burn \"${vm.title.trim().ifEmpty { "Untitled" }}\"?",
            body = "No undo.",
            onKeep = { burning = false },
            onBurn = {
                burning = false; focusManager.clearFocus(); vm.delete()
                scope.launch { Animatable(0f).animateTo(1f, tween(350, easing = LinearEasing)) { ash = value }; closeNow() }
            },
        )
    }
}

/** Under the body: "+ add a link" when empty; a link opens in the browser, "edit" changes it. */
@Composable
private fun IdeaLinkLine(link: String, onChange: (String) -> Unit, enabled: Boolean) {
    var editing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val focus = remember { FocusRequester() }
    var wasFocused by remember { mutableStateOf(false) }
    val style = TextStyle(fontFamily = PageFont, fontSize = 20.sp, lineHeight = 24.sp, color = Px.Lavender)
    LaunchedEffect(editing) { if (editing) focus.requestFocus() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        when {
            editing -> BasicTextField(
                value = link,
                onValueChange = { onChange(it); failed = false },
                enabled = enabled,
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(Px.Lavender),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { editing = false }),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).focusRequester(focus)
                    .onFocusChanged { if (it.isFocused) wasFocused = true else if (wasFocused) { wasFocused = false; editing = false } },
            )
            link.isBlank() -> Text(
                "+ add a link",
                Modifier.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).clickable(enabled = enabled, role = Role.Button) { editing = true },
                style = style.copy(color = Px.TextDim.copy(alpha = 0.6f)),
            )
            else -> Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    link.trim(), Modifier.weight(1f, fill = false).clickable(role = Role.Button, onClickLabel = "Open link") {
                        val l = link.trim()
                        failed = runCatching { uri.openUri(if (':' in l) l else "https://$l") }.isFailure
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = style.copy(textDecoration = TextDecoration.Underline),
                )
                Text("edit", Modifier.clickable(role = Role.Button) { editing = true }.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), style = style.copy(color = Px.TextDim))
            }
        }
        if (failed) Text("This link can't be opened.", style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Px.Lavender))
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
                    onDrawBehind { drawIntoCanvas { paper.blit(it, 0, 0, px) } }
                }
                .padding(start = 20.dp, end = 12.dp + inset, top = 22.dp, bottom = 6.dp + inset),
        ) {
            Text(title, style = TextStyle(fontFamily = PageFont, fontSize = 22.sp, lineHeight = 24.sp, color = ink))
            Spacer(Modifier.height(6.dp))
            Text(body, style = TextStyle(fontFamily = PageFont, fontSize = 17.sp, lineHeight = 20.sp, color = ink))
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Word("keep", ink, onKeep)
                Word("burn", Color(0xFF3A1E5A), onBurn)
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
