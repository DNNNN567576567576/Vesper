package app.vesper.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vesper.IdeaEditorViewModel
import app.vesper.data.Lobe
import kotlinx.coroutines.launch
import kotlin.math.max

private val CardPurple = Color(0xFF9C84FF)

/**
 * A note as a card over the world: it grows from [from] (px in the root; unspecified = the middle) with a gentle overshoot, and
 * shrinks back there on close. [p] is its open amount (0..1), shared so the world behind can dim and shrink with it. Inside is
 * the note page itself ([IdeaPageScreen]), so typing, saving, burning and links behave exactly as on the page; closing from
 * outside the page (a tap outside, a drag down) saves first just like the page's own back does.
 */
@Composable
fun NoteCard(
    vm: IdeaEditorViewModel, from: Offset, p: Animatable<Float, AnimationVector1D>, footer: String,
    lobes: List<Lobe>, closeSignal: Int, lobeNamed: (String, (Long) -> Unit) -> Unit, onMoved: (String) -> Unit, onClosed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var closing by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(vm) { p.snapTo(0f); p.animateTo(1f, tween(320, easing = CubicBezierEasing(0.34f, 1.35f, 0.64f, 1f))) }
    fun close() {
        if (closing) return
        closing = true
        scope.launch { p.animateTo(0f, tween(240, easing = FastOutSlowInEasing)); onClosed() }
    }
    // the same as the page's own back: save what is typed, drop the keyboard, go
    fun leave() { vm.flush(); focusManager.clearFocus(); close() }
    // The Brain tab tapped again (App bumps the signal): same as leaving by hand, so what is typed is saved first.
    var seenSignal by remember { mutableIntStateOf(closeSignal) }
    LaunchedEffect(closeSignal) { if (closeSignal != seenSignal) { seenSignal = closeSignal; leave() } }
    var moving by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = p.value.coerceIn(0f, 1f) }.background(Color.Black.copy(alpha = 0.65f))
                .pointerInput(Unit) { detectTapGestures { leave() } },
        )
        Box(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(top = 60.dp, start = 12.dp, end = 12.dp, bottom = 12.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxSize()
                    .growFrom(from, { p.value }, { drag })
                    .shadow(20.dp, shape, ambientColor = CardPurple, spotColor = CardPurple)
                    .clip(shape).background(Color(0xFF140B26)).border(1.5.dp, CardPurple, shape)
                    .pointerInput(Unit) { detectTapGestures { } }, // taps on the card itself never reach the dim world behind
            ) {
                // the grab handle: drag the card down past ~80 dp to close it
                Box(
                    Modifier.fillMaxWidth().height(20.dp).pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = { if (drag > 80.dp.toPx()) leave() else scope.launch { Animatable(drag).animateTo(0f, tween(180)) { drag = value } } },
                            onDragCancel = { drag = 0f },
                        ) { ch, dy -> ch.consume(); drag = max(0f, drag + dy) }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(CardPurple.copy(alpha = 0.55f)))
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { IdeaPageScreen(vm, onClose = ::close, onMove = { moving = true }) }
                Text(
                    footer, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, color = Px.TextDim.copy(alpha = 0.7f)),
                )
            }
        }
    }
    if (moving) {
        val here = placeIndex(lobes.firstOrNull { it.id == vm.lobeId })
        MovePicker(
            here,
            onPick = { name ->
                moving = false
                if (PLACE_NAMES.indexOf(name) == here) return@MovePicker // already there (an old group counts as the main brain)
                lobeNamed(name) { l ->
                    scope.launch {
                        val dest = vm.moveTo(l, null) ?: return@launch
                        onMoved(dest.ifBlank { "main brain" })
                        close()
                    }
                }
            },
            onCancel = { moving = false },
        )
    }
}

/**
 * Grows a card from [from] (px in the root; unspecified = its own middle) with a fade as [p] goes 0 to 1, pulled [dragY] px down.
 * The note card and the main brain's card both open this way.
 */
internal fun Modifier.growFrom(from: Offset, p: () -> Float, dragY: () -> Float = { 0f }): Modifier = composed {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    onGloballyPositioned { bounds = it.boundsInRoot() }.graphicsLayer {
        val e = p()
        val c = bounds.center
        val at = if (from.isSpecified) from else c
        val k = 0.06f + 0.94f * e
        scaleX = k; scaleY = k
        translationX = (at.x - c.x) * (1f - e)
        translationY = (at.y - c.y) * (1f - e) + dragY() * e.coerceIn(0f, 1f)
        alpha = (e * 4f).coerceIn(0f, 1f)
    }
}
