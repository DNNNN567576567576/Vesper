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

@Composable
fun NoteEditorScreen(vm: NoteEditorViewModel, onClose: () -> Unit) {
    val focusManager = LocalFocusManager.current
    val titleFocus = remember { FocusRequester() }
    var confirmDelete by remember { mutableStateOf(false) }
    val closeNow by rememberUpdatedState(onClose)

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
            Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Back", onClick = ::leave)
                    .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) { PixelIcon(Icons.Back, Px.Lavender, pixel = 3.dp) }
            Spacer(Modifier.weight(1f))
            if (vm.exists) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clickable(role = Role.Button, onClickLabel = "Delete note") { confirmDelete = true }
                        .semantics { contentDescription = "Delete note" },
                    contentAlignment = Alignment.Center,
                ) { PixelIcon(Icons.Trash, Px.TextDim, pixel = 3.dp) }
            }
        }

        BasicTextField(
            value = vm.title,
            onValueChange = vm::onTitle,
            enabled = vm.loaded,
            singleLine = true,
            textStyle = TextStyle(fontFamily = PixelFont, fontSize = 15.sp, lineHeight = 22.sp, color = Px.Text),
            cursorBrush = SolidColor(Px.Lavender),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).focusRequester(titleFocus),
            decorationBox = { inner ->
                Box {
                    if (vm.title.isEmpty()) {
                        Text("Title", style = TextStyle(fontFamily = PixelFont, fontSize = 15.sp, lineHeight = 22.sp, color = Px.TextDim.copy(alpha = 0.5f)))
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
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                BasicTextField(
                    value = vm.content,
                    onValueChange = vm::onContent,
                    enabled = vm.loaded,
                    textStyle = TextStyle(fontSize = 17.sp, lineHeight = 26.sp, color = Px.Text),
                    cursorBrush = SolidColor(Px.Lavender),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().heightIn(min = minH).padding(horizontal = 20.dp, vertical = 14.dp),
                    decorationBox = { inner ->
                        Box {
                            if (vm.content.isEmpty()) {
                                Text("Write somethingâ€¦", style = TextStyle(fontSize = 17.sp, lineHeight = 26.sp, color = Px.TextDim.copy(alpha = 0.5f)))
                            }
                            inner()
                        }
                    },
                )
            }
        }
    }

    if (confirmDelete) {
        PixelDialog(onDismiss = { confirmDelete = false }) {
            Text("Delete this page?", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, lineHeight = 18.sp, color = Px.Text))
            Spacer(Modifier.height(8.dp))
            Text("It will be gone for good.", style = TextStyle(fontSize = 14.sp, color = Px.TextDim))
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                PixelButton("KEEP", { confirmDelete = false })
                PixelButton("DELETE", {
                    confirmDelete = false
                    vm.delete()
                    focusManager.clearFocus()
                    closeNow()
                }, primary = true)
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
