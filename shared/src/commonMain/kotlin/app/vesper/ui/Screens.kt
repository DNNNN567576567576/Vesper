package app.vesper.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import app.vesper.res.Res
import app.vesper.res.tab_brain
import app.vesper.res.tab_grimoire
import app.vesper.res.tab_calc
import app.vesper.res.tab_moons
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.imageResource
import kotlin.math.PI
import kotlin.math.sin


/** True in the wide desktop layout (window >= 700dp): rail, two panes. Never true on phones. */
val LocalWide = compositionLocalOf { false }

@Composable
fun ScreenTitle(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 10.dp),
        style = TextStyle(
            fontFamily = TitleFont,
            fontSize = 40.sp,
            color = Px.Lavender,
            shadow = Shadow(Color(0xFF441F6E), Offset(3f, 3f), 0f),
        ),
    )
}

/** Pixel-art round add button: a glowing sigil with a slowly turning dotted ring. Drawn on a 41x41 grid of 2dp pixels (the 58dp button plus room for the glow and the press ring). */
@Composable
fun SigilFab(description: String, onClick: () -> Unit, modifier: Modifier = Modifier, back: Boolean = false, quill: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 900f),
        label = "sigilScale",
    )
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(140),
        label = "sigilPress",
    )
    val time = rememberRotTime()
    val ringStart = remember { mutableFloatStateOf(-10f) }
    LaunchedEffect(pressed) { if (pressed) ringStart.floatValue = time.floatValue }
    val pix = remember { Pix(SIGIL_GRID, SIGIL_GRID) }
    Box(
        modifier
            .size(58.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = description,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.requiredSize((SIGIL_GRID * 2).dp)) {
            drawSigil(pix, time.floatValue, press, ringStart.floatValue, back, quill)
            drawIntoCanvas { pix.blitTo(it, 0, 0, size.width.toInt(), size.height.toInt()) }
        }
    }
}

private const val SIGIL_GRID = 41

internal fun drawSigil(pix: Pix, t: Float, press: Float, ringStart: Float, back: Boolean, quill: Boolean) {
    val c = SIGIL_GRID / 2 + 0.5f
    pix.clear()
    pix.glow(c, c, 17f, 0x7540B8, 0.35f + 0.15f * sin(t * 1.6f) + 0.4f * press)

    // Disc with a 1px rim.
    val r = 9.5f
    val lo = (c - r - 1f).toInt()
    val hi = (c + r + 1f).toInt()
    for (y in lo..hi) {
        for (x in lo..hi) {
            val d = hypot(x + 0.5f - c, y + 0.5f - c)
            if (d <= r) pix.dot(x, y, 1, 1, if (d > r - 1f) 0x5A3E7A else 0x120A1A)
        }
    }

    // Dotted ring. The back button is purple only (no pink); the add and quill buttons use the violet accent.
    val accent = if (back) 0xB69AFF else 0x935AD4
    val shift = floor(t * 3f).toInt()
    for (i in 0 until 40) {
        if ((i + shift) % 4 == 0) continue
        val a = i / 40f * 2f * PI.toFloat() + t * 0.6f
        pix.dot(floor(c + cos(a) * 12f).toInt(), floor(c + sin(a) * 12f).toInt(), 1, 1, if (i % 5 == 0) accent else 0x8A6AD0)
    }

    // Plus (or back arrow).
    val pressedLook = press > 0.5f
    val arm = if (pressedLook) (if (back) 0xD6C8FF else 0xE6D0FF) else 0xC9B0FF
    val ci = c.toInt()
    if (quill) {
        // Diagonal feather: shaft from the nib (bottom left) to the tip, with a vane on both sides and an ink dot.
        for (i in 0..10) {
            val x = ci - 5 + i
            val y = ci + 5 - i
            pix.dot(x, y, 1, 1, arm)
            if (i in 4..9) { pix.dot(x - 1, y, 1, 1, arm); pix.dot(x, y - 1, 1, 1, arm) }
            if (i in 3..7) { pix.dot(x + 1, y, 1, 1, arm); pix.dot(x, y + 1, 1, 1, arm) }
        }
        pix.dot(ci - 6, ci + 6, 1, 1, 0x935AD4)
    } else {
    pix.dot(ci - 5, ci, 11, 1, arm)
    if (back) {
        // Arrowhead pointing left.
        for (i in 1..4) { pix.dot(ci - 5 + i, ci - i, 1, 1, arm); pix.dot(ci - 5 + i, ci + i, 1, 1, arm) }
    } else {
        pix.dot(ci, ci - 5, 1, 11, arm)
        pix.dot(ci, ci, 1, 1, if (pressedLook) 0xE6D0FF else 0xE6DCFF)
    }
    }

    // Ring released by a press.
    val age = t - ringStart
    if (age in 0f..0.6f) {
        val k = age / 0.6f
        val rr = 10f + 8f * k
        for (i in 0 until 16) {
            val a = i / 16f * 2f * PI.toFloat()
            pix.dot(floor(c + cos(a) * rr).toInt(), floor(c + sin(a) * rr).toInt(), 1, 1, if (back) 0x8F7BFF else 0x935AD4, 1f - k)
        }
    }
}

@Composable
fun PixelButton(text: String, onClick: () -> Unit, primary: Boolean = false, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .pixelCard(
                fill = if (primary) Px.Primary else Px.Elevated,
                border = if (primary) Px.Lavender else Px.Border,
                shadow = false,
                notch = 3.dp,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(fontFamily = PixelFont, fontSize = 10.sp, color = Px.Text))
    }
}

// ---------------------------------------------------------------------------------------------
// Bottom navigation
// ---------------------------------------------------------------------------------------------

private class NavItem(val icon: DrawableResource, val label: String, val zoom: Float = 1f, val small: Boolean = false)

private val NAV_ITEMS = listOf(
    NavItem(Res.drawable.tab_brain, "Brain"),
    NavItem(Res.drawable.tab_grimoire, "Grimoire"),
    NavItem(Res.drawable.tab_moons, "Moons", 1.35f), // the moon art sits on a coarser grid, so it reads smaller than the others
    NavItem(Res.drawable.tab_calc, "Calc", small = true), // a small extra tab: narrower slot, smaller icon
)

@Composable
fun PixelNavBar(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Px.Surface)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Px.Border))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(72.dp)) {
            NAV_ITEMS.forEachIndexed { i, n ->
                NavTab(n.icon, n.label, selected == i, { onSelect(i) }, Modifier.weight(if (n.small) 0.55f else 1f), n.zoom, n.small)
            }
        }
    }
}

/** The wide layout's left rail: the same three tabs, stacked from the top. */
@Composable
fun PixelNavRail(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxHeight().width(88.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().background(Px.Surface)) {
            NAV_ITEMS.forEachIndexed { i, n ->
                NavTab(n.icon, n.label, selected == i, { onSelect(i) }, Modifier.fillMaxWidth().height(if (n.small) 52.dp else 72.dp), n.zoom, n.small)
            }
        }
        Box(Modifier.width(2.dp).fillMaxHeight().background(Px.Border))
    }
}

@Composable
private fun NavTab(
    icon: DrawableResource,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    zoom: Float = 1f,
    small: Boolean = false,
) {
    val navScale by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 640f),
        label = "navScale",
    )
    Column(
        modifier
            .fillMaxSize()
            .clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            bitmap = imageResource(icon),
            contentDescription = null,
            modifier = Modifier
                .size(if (small) 22.dp else 34.dp)
                .graphicsLayer {
                    val k = (0.84f + 0.16f * navScale) * zoom
                    scaleX = k
                    scaleY = k
                    alpha = if (active) 1f else 0.38f
                },
            filterQuality = FilterQuality.None,
        )
        Text(
            label,
            modifier = Modifier.padding(top = 5.dp),
            style = TextStyle(
                fontFamily = PixelFont,
                fontSize = 8.sp,
                letterSpacing = 0.5.sp,
                color = if (active) Px.Lavender else Px.TextDim.copy(alpha = 0.8f),
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            ),
        )
    }
}

