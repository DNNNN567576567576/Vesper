package app.vesper.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.vesper.LearnViewModel
import app.vesper.data.Goal
import app.vesper.data.Step
import app.vesper.res.Res
import app.vesper.res.brain_main
import org.jetbrains.compose.resources.imageResource
import java.time.LocalDate
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// The learn brain: goals (just names) as calm full moons round a small second brain.

// ---------------------------------------------------------------------------------------------
// Rules (pure, tested in LearnTest)
// ---------------------------------------------------------------------------------------------

internal const val DAY_MS = 86_400_000L

/** A goal's name is a short sentence, so it may be this long. */
internal const val GOAL_NAME_MAX = 60

/** The learn brain's core sprite scale on the main map (the main brain's is 0.64). */
internal const val LEARN_SCALE = 0.24f

/** 1 while touched within 7 days, then fading evenly to 0.45 over the next three weeks, never dimmer. */
internal fun goalBrightness(touchedAt: Long, now: Long): Float =
    1f - 0.55f * (((now - touchedAt) / DAY_MS.toFloat() - 7f) / 21f).coerceIn(0f, 1f)

private const val LEARN_BRAIN_R = 17.5f // the dotted ring, the moons' orbit (16) and a moon's dot
internal const val LEARN_LABEL_W = 48f // dp: "learn" at 18sp with its glow
internal const val LEARN_LABEL_H = 24f // dp
internal const val NAME_CHAR_DP = 11f // dp per letter of a 20sp name, a sub brain's or the vault's

/** The learn brain with its orbit (l, t, r, b, world) at [p]. */
internal fun learnBrainBox(p: Offset) = floatArrayOf(p.x - LEARN_BRAIN_R, p.y - LEARN_BRAIN_R, p.x + LEARN_BRAIN_R, p.y + LEARN_BRAIN_R)
/** The "learn" label under the brain at [p] (l, t, r, b, world) when a world unit is [k] dp. */
internal fun learnLabelBox(p: Offset, k: Float) = floatArrayOf(p.x - LEARN_LABEL_W / 2f / k, p.y + 17f, p.x + LEARN_LABEL_W / 2f / k, p.y + 17f + LEARN_LABEL_H / k)

/** The ring round the main brain the small brains sit on (world radius; the middle rot ring), and how far (radians) below or above the horizontal each spot is. */
internal const val RING_R = 105f
private const val RING_A = 1.0472f // 60 degrees: the bottom spots stay clear of the heart, the top ones of the main brain's sparks
private val RING_X = RING_R * cos(RING_A)
private val RING_Y = RING_R * sin(RING_A)
/** The four spots on the ring, symmetric about x = 0: the tasks brain bottom left, the learn brain its mirror at bottom right. */
internal val TASKS_AT = Offset(-RING_X, RING_Y)
internal val LEARN_AT = Offset(RING_X, RING_Y)
/** Top left holds the Daily brain, top right the Bible brain. No task or goal flower is placed on either. */
internal val RESERVED_AT = listOf(Offset(-RING_X, -RING_Y), Offset(RING_X, -RING_Y))
internal val DAILY_AT = RESERVED_AT[0]
internal val BIBLE_AT = RESERVED_AT[1]

// ---------------------------------------------------------------------------------------------
// The learn view: the learn brain with its goals circling it
// ---------------------------------------------------------------------------------------------

/**
 * The goal moon, drawn in code: a chunky 11x11 purple pixel disc lit from the upper left (W white, L light, M mid, D the dark side),
 * with a few darker craters (K on the lit side, C on the dark one). Purple shades and white only.
 */
internal val MOON_ROWS = listOf(
    "...WLLLM...",
    "..WLLLLMM..",
    ".WLLKLLLMD.",
    "WLLLLLLLMDD",
    "LLLLLLKLMDD",
    "LLLKKLLMMDD",
    "LLLKKLLMDDD",
    "LLLLLLMDCDD",
    ".LLLLMMDDD.",
    "..MMMMDDD..",
    "...MDDDD...",
)
internal fun moonRgb(ch: Char): Int = when (ch) {
    'W' -> 0xFFFFFF; 'L' -> 0xC9B6FF; 'M' -> 0x9C84FF; 'D' -> 0x5B3FB0; 'K' -> 0x8A6CF0; 'C' -> 0x3A2475; else -> -1
}
/** Low-res pixels per moon pixel on the main map at zoom [z]: it grows with the nodes, never shrinking below one. */
internal fun moonScale(z: Float) = max(1, rnd(z * nodeScale(z) * 0.75f))
/** The moon centred on pixel ([cx], [cy]), [s] pixels per moon pixel. */
internal fun Pix.goalMoon(cx: Int, cy: Int, s: Int, a: Float) {
    val x0 = cx - 11 * s / 2; val y0 = cy - 11 * s / 2
    MOON_ROWS.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> moonRgb(ch).let { if (it >= 0) dot(x0 + x * s, y0 + y * s, s, s, it, a) } } }
}
