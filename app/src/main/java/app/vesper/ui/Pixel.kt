package app.vesper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.vesper.R
import kotlin.math.roundToInt

/** Palette (starting from the brief, nudged for contrast). */
object Px {
    val Bg = Color(0xFF100817)
    val Surface = Color(0xFF1A1026)
    val Elevated = Color(0xFF241432)
    val Primary = Color(0xFF7540B8)
    val Lavender = Color(0xFFB69AFF)
    val Magenta = Color(0xFFD45AAB)
    val Text = Color(0xFFF0E8FA)
    val TextDim = Color(0xFFA99AB9)
    val Border = Color(0xFF39234C)
    val Shadow = Color(0xFF07030B)
}

val PixelFont = FontFamily(Font(R.font.pressstart2p))

@Composable
fun PixelTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Px.Primary,
        onPrimary = Px.Text,
        secondary = Px.Lavender,
        tertiary = Px.Magenta,
        background = Px.Bg,
        onBackground = Px.Text,
        surface = Px.Surface,
        onSurface = Px.Text,
        surfaceVariant = Px.Elevated,
        onSurfaceVariant = Px.TextDim,
        outline = Px.Border,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = Px.Lavender,
                backgroundColor = Px.Primary.copy(alpha = 0.45f),
            ),
            content = content,
        )
    }
}

/** Rectangle with the corners stepped off by [notch], like a chunky pixel panel. */
class PixelShape(private val notch: Dp = 3.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val n = with(density) { notch.toPx() }
        val w = size.width
        val h = size.height
        return Outline.Generic(
            Path().apply {
                moveTo(n, 0f); lineTo(w - n, 0f); lineTo(w - n, n); lineTo(w, n)
                lineTo(w, h - n); lineTo(w - n, h - n); lineTo(w - n, h); lineTo(n, h)
                lineTo(n, h - n); lineTo(0f, h - n); lineTo(0f, n); lineTo(n, n)
                close()
            }
        )
    }
}

/** Panel with a stepped outline, 2dp border and a hard, un-blurred drop shadow. */
fun Modifier.pixelCard(
    fill: Color = Px.Surface,
    border: Color = Px.Border,
    shadow: Boolean = true,
    notch: Dp = 3.dp,
): Modifier {
    val shape = PixelShape(notch)
    return this
        .drawBehind {
            if (shadow) {
                translate(4.dp.toPx(), 4.dp.toPx()) {
                    drawOutline(shape.createOutline(size, layoutDirection, this), Px.Shadow)
                }
            }
        }
        .clip(shape)
        .background(fill)
        .border(2.dp, border, shape)
}

/** Draws a character grid as crisp, whole-pixel squares. Characters missing from [palette] stay empty. */
@Composable
fun PixelArt(
    grid: List<String>,
    palette: Map<Char, Color>,
    pixel: Dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val px = with(density) { pixel.toPx().roundToInt().coerceAtLeast(1) }
    val cols = grid.maxOf { it.length }
    val wDp = with(density) { (px * cols).toDp() }
    val hDp = with(density) { (px * grid.size).toDp() }
    Canvas(modifier.size(wDp, hDp)) {
        grid.forEachIndexed { y, row ->
            row.forEachIndexed { x, ch ->
                palette[ch]?.let {
                    drawRect(it, Offset((x * px).toFloat(), (y * px).toFloat()), Size(px.toFloat(), px.toFloat()))
                }
            }
        }
    }
}

@Composable
fun PixelIcon(grid: List<String>, tint: Color, pixel: Dp = 3.dp, modifier: Modifier = Modifier) =
    PixelArt(grid, mapOf('X' to tint), pixel, modifier)

object Icons {
    val Grimoire = listOf(
        "..BBBBBBBBBB..",
        ".BHHHHHHHHHHB.",
        "BCHHHHHHHHHHCB",
        "BCCCHHMMHHCCCB",
        "BCCCMMMMMMCCCB",
        "BCCCHHMMHHCCCB",
        "BCCCCCCCCCCCCB",
        "BCCCCCCCCCCCCB",
        "BLLCCCCCCCCLLB",
        "BCCCCCCCCCCCCB",
        ".BPPPPPPPPPPB.",
        ".BPPPPPPPPPPB.",
        "..BBBBBMMBB...",
        "......MM......",
    )

    val TasksScroll = listOf(
        ".BBBBBBBBBBB..",
        "BHHHHHHHHHHHB.",
        "BCCCCCCCCCCCB.",
        "BCKKBCVVBCCCB.",
        "BCKKBCVVBCCCB.",
        "BCCCCCCCCCCCB.",
        "BCKKBCMMBCCCB.",
        "BCKKBCMMBCCCB.",
        "BCCCCCCCCCCCB.",
        "BCKKBCVVBCCCB.",
        "BCKKBCVVBCCCB.",
        "BCCCCCCCCCCCB.",
        ".BPPPPPPPPPPB.",
        "..BBBBBBBBBB..",
    )

    val Page = listOf(
        ".XXXXX..",
        ".X...XX.",
        ".X....X.",
        ".X.XXXX.",
        ".X....X.",
        ".X.XX.X.",
        ".X....X.",
        ".XXXXXX.",
    )
    val List = listOf(
        "XX.XXXXX",
        "XX......",
        "........",
        "XX.XXXXX",
        "XX......",
        "........",
        "XX.XXXXX",
        "XX......",
    )
    val Plus = listOf(
        "...XX...",
        "...XX...",
        "...XX...",
        "XXXXXXXX",
        "XXXXXXXX",
        "...XX...",
        "...XX...",
        "...XX...",
    )
    val Back = listOf(
        ".....XX",
        "....XX.",
        "...XX..",
        "..XX...",
        ".XX....",
        "..XX...",
        "...XX..",
        "....XX.",
        ".....XX",
    )
    val Trash = listOf(
        "..XXXX..",
        "XXXXXXXX",
        ".X.XX.X.",
        ".X.XX.X.",
        ".X.XX.X.",
        ".X.XX.X.",
        ".XXXXXX.",
        "........",
    )
    val Star = listOf(
        "...X...",
        "...X...",
        "..XXX..",
        "XXXXXXX",
        "..XXX..",
        "...X...",
        "...X...",
    )
    val Gem = listOf(
        "..X..",
        ".XXX.",
        "XXXXX",
        ".XXX.",
        "..X..",
    )
    val Rune = listOf(
        ".X...",
        ".XXX.",
        ".X..X",
        ".XXX.",
        ".X...",
    )
    val Box = listOf(
        "XXXXXXXX",
        "XooooooX",
        "XooooooX",
        "XooooooX",
        "XooooooX",
        "XooooooX",
        "XooooooX",
        "XXXXXXXX",
    )

    /** Round rune ring â€” the task "checkbox". */
    val Ring = listOf(
        "..XXXX..",
        ".XooooX.",
        "XooooooX",
        "XooooooX",
        "XooooooX",
        "XooooooX",
        ".XooooX.",
        "..XXXX..",
    )

    val GrimoireActive = mapOf(
        'B' to Color(0xFF100817),
        'C' to Color(0xFF47296D),
        'H' to Color(0xFF7540B8),
        'P' to Color(0xFFB69AFF),
        'M' to Color(0xFFD45AAB),
        'L' to Color(0xFFF0E8FA),
    )
    val GrimoireInactive = mapOf(
        'B' to Color(0xFF100817),
        'C' to Color(0xFF241432),
        'H' to Color(0xFF39234C),
        'P' to Color(0xFF563C78),
        'M' to Color(0xFF7A3668),
        'L' to Color(0xFF8A7A9A),
    )

    val TasksScrollActive = mapOf(
        'B' to Color(0xFF100817),
        'H' to Color(0xFFB69AFF),
        'C' to Color(0xFF241432),
        'K' to Color(0xFF7540B8),
        'V' to Color(0xFFF0E8FA),
        'M' to Color(0xFFD45AAB),
        'P' to Color(0xFF47296D),
    )
    val TasksScrollInactive = mapOf(
        'B' to Color(0xFF100817),
        'H' to Color(0xFF563C78),
        'C' to Color(0xFF1A1026),
        'K' to Color(0xFF39234C),
        'V' to Color(0xFF8A7A9A),
        'M' to Color(0xFF7A3668),
        'P' to Color(0xFF2A1640),
    )
}
