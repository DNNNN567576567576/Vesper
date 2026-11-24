package app.vesper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.math.floor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind

/** What the calculator holds: the typed expression, and (after "=") its result. */
data class CalcState(val expr: String = "", val result: String? = null)

/** Pure calculator logic: no UI. Operators in expressions are `+ − × ÷`, and `%` after a number. */
object CalcEngine {
    const val DIV0 = "Can't divide by 0"
    private const val OPS = "+−×÷"
    private fun isOp(c: Char) = c in OPS

    /** Applies one key: a digit, `.`, an operator, `%`, `=`, `C` or `⌫`. */
    fun press(s: CalcState, k: Char): CalcState {
        val done = s.result != null
        val failed = s.result == DIV0
        return when {
            k == 'C' || (k == '⌫' && done) -> CalcState()
            k == '⌫' -> CalcState(s.expr.dropLast(1))
            k == '=' -> {
                val e = s.expr.trimEnd { isOp(it) }
                if (e.isEmpty() || done) s else CalcState(e, evaluate(e))
            }
            k.isDigit() || k == '.' -> {
                val base = if (done) "" else s.expr
                val tok = base.takeLastWhile { it.isDigit() || it == '.' }
                when {
                    base.endsWith('%') -> CalcState(base) // a digit right after % is ignored
                    k == '.' && '.' in tok -> CalcState(base)
                    k == '.' && tok.isEmpty() -> CalcState(base + "0.")
                    k != '.' && tok == "0" -> CalcState(base.dropLast(1) + k) // no leading zeros
                    else -> CalcState(base + k)
                }
            }
            failed -> s
            k == '%' -> if (!done && s.expr.lastOrNull()?.let { it.isDigit() || it == '%' } == true) CalcState(s.expr + k) else s
            isOp(k) -> {
                val base = if (done) s.result else s.expr
                when {
                    base.isEmpty() -> CalcState(if (k == '−') "−" else "0$k")
                    base == "−" -> s // a lone leading minus has nothing to attach to yet
                    isOp(base.last()) -> CalcState(base.dropLast(1) + k) // repeated operators replace the last
                    else -> CalcState(base + k)
                }
            }
            else -> s
        }
    }

    /** The big line: the result, else the number being typed (or the running value after an operator). */
    fun shown(s: CalcState): String {
        s.result?.let { return it }
        if (s.expr.isEmpty()) return "0"
        val tok = s.expr.takeLastWhile { it.isDigit() || it == '.' || it == '%' }
        return if (tok.isNotEmpty()) tok else evaluate(s.expr.trimEnd { isOp(it) }).ifEmpty { "0" }
    }

    /** Evaluates an expression and formats it; returns [DIV0] on division by zero, "" if incomplete. */
    fun evaluate(expr: String): String = try {
        val v = Parser(expr).expr().first
        if (v.isNaN() || v.isInfinite()) DIV0 else format(v)
    } catch (e: ArithmeticException) {
        DIV0
    } catch (e: IllegalStateException) {
        ""
    }

    // Each rule returns the value plus "was a bare percentage" (so a+b% can mean a + a*b/100).
    private class Parser(val s: String) {
        var i = 0
        fun expr(): Pair<Double, Boolean> {
            var (v, pct) = term()
            while (i < s.length && (s[i] == '+' || s[i] == '−')) {
                val plus = s[i++] == '+'
                val (r, rPct) = term()
                val d = if (rPct) v * r else r
                v = if (plus) v + d else v - d
                pct = false
            }
            return v to pct
        }
        fun term(): Pair<Double, Boolean> {
            var (v, pct) = unary()
            while (i < s.length && (s[i] == '×' || s[i] == '÷')) {
                val mul = s[i++] == '×'
                val r = unary().first
                if (!mul && r == 0.0) throw ArithmeticException()
                v = if (mul) v * r else v / r
                pct = false
            }
            return v to pct
        }
        fun unary(): Pair<Double, Boolean> {
            if (i < s.length && s[i] == '−') { i++; val (v, p) = unary(); return -v to p }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            var v = s.substring(st, i).toDoubleOrNull() ?: error("number")
            var pct = false
            while (i < s.length && s[i] == '%') { i++; v /= 100; pct = true }
            return v to pct
        }
    }

    /** 12 significant digits, trailing zeros trimmed, no exponent for normal sizes; negatives use `−`. */
    fun format(v: Double): String {
        if (v == 0.0) return "0"
        val parts = kotlin.math.abs(v).toString().split('E')
        val mant = parts[0]
        var point = mant.indexOf('.').let { if (it < 0) mant.length else it } + (parts.getOrNull(1)?.toInt() ?: 0)
        var digits = mant.replace(".", "")
        val lead = digits.takeWhile { it == '0' }.length
        digits = digits.drop(lead); point -= lead
        if (digits.length > 12) {
            var n = digits.take(12).toLong() + if (digits[12] >= '5') 1 else 0
            if (n >= 1_000_000_000_000L) { n /= 10; point++ }
            digits = n.toString()
        }
        digits = digits.trimEnd('0').ifEmpty { "0" }
        val body = when {
            point > 15 || point < -8 -> digits.take(1) + (if (digits.length > 1) "." + digits.drop(1) else "") + "e" + (point - 1)
            point <= 0 -> "0." + "0".repeat(-point) + digits
            point >= digits.length -> digits + "0".repeat(point - digits.length)
            else -> digits.take(point) + "." + digits.drop(point)
        }
        return if (v < 0) "−$body" else body
    }
}

// Carved keys: each has a face and a light and a dark edge (light top-left, dark bottom-right, like chiselled stone).
private class KeyLook(val face: Color, val light: Color, val dark: Color, val ink: Color)

private val STONE = KeyLook(Color(0xFF3E3358), Color(0xFF6A5C92), Color(0xFF1C1430), Px.Text)
private val OPKEY = KeyLook(Color(0xFF2A1450), Color(0xFF51308C), Color(0xFF120828), Px.Lavender)
private val EQKEY = KeyLook(Color(0xFFB69AFF), Color(0xFFE6DAFF), Color(0xFF6E52B4), Px.Bg)

private val KEYPAD = listOf(
    listOf("C", "⌫", "%", "÷"),
    listOf("7", "8", "9", "×"),
    listOf("4", "5", "6", "−"),
    listOf("1", "2", "3", "+"),
    listOf("0", ".", "="),
)


/** Operator glyphs as pixel art, so + − × ÷ all have the same size (the pixel font draws them at different sizes). */
private val OP_GLYPHS = mapOf(
    "+" to listOf("...X...", "...X...", "...X...", "XXXXXXX", "...X...", "...X...", "...X..."),
    "−" to listOf(".......", ".......", ".......", "XXXXXXX", ".......", ".......", "......."),
    "×" to listOf("X.....X", ".X...X.", "..X.X..", "...X...", "..X.X..", ".X...X.", "X.....X"),
    "÷" to listOf("...X...", ".......", ".......", "XXXXXXX", ".......", ".......", "...X..."),
)
private val CalcSaver = listSaver<CalcState, String>(
    save = { listOf(it.expr, it.result ?: "\u0000") },
    restore = { CalcState(it[0], it[1].takeIf { r -> r != "\u0000" }) },
)

/** Display only: thousands commas in every whole-number part ("1280 + 4.5" -> "1,280 + 4.5"); the engine never sees them. */
internal fun groupDigits(s: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
        if (!s[i].isDigit()) { out.append(s[i]); i++; continue }
        var j = i
        while (j < s.length && s[j].isDigit()) j++
        val run = s.substring(i, j)
        val whole = (i == 0 || (s[i - 1] != '.' && s[i - 1] != 'e'))
        if (whole) run.forEachIndexed { k, c -> if (k > 0 && (run.length - k) % 3 == 0) out.append(','); out.append(c) } else out.append(run)
        i = j
    }
    return out.toString()
}

private const val CALC_MIN_SP = 28f
private const val CALC_MAX_SP = 72f
private const val CALC_EM = 0.45f // VT323 digits and commas are ~0.4em wide; a little room over

/**
 * The big line's text and font size (sp) for [text] in [widthDp]: shrink down to [CALC_MIN_SP], and if it still does not fit, write the
 * number in scientific notation with as many digits as fit, so nothing is ever clipped. Text that is not a plain number is only shrunk.
 */
internal fun fitDisplay(text: String, widthDp: Float): Pair<String, Float> {
    fun size(t: String) = widthDp / (t.length.coerceAtLeast(1) * CALC_EM)
    if (size(text) >= CALC_MIN_SP) return text to size(text).coerceAtMost(CALC_MAX_SP)
    val plain = text.replace(",", "")
    val neg = plain.startsWith("−")
    val num = plain.removePrefix("−")
    if (num.isEmpty() || num.any { !it.isDigit() && it != '.' }) return text to CALC_MIN_SP
    val whole = num.substringBefore('.').trimStart('0'); val frac = num.substringAfter('.', "")
    val digits = (whole + frac).trimStart('0').trimEnd('0')
    if (digits.isEmpty()) return "0" to CALC_MAX_SP
    val exp = if (whole.isNotEmpty()) whole.length - 1 else -(frac.length - frac.trimStart('0').length) - 1
    for (n in (digits.length - 1).coerceAtMost(11) downTo 0) {
        val t = (if (neg) "−" else "") + digits.take(1) + (if (n > 0) "." + digits.drop(1).take(n) else "") + "e" + exp
        if (size(t) >= CALC_MIN_SP || n == 0) return t to size(t).coerceIn(CALC_MIN_SP, CALC_MAX_SP)
    }
    return text to CALC_MIN_SP
}

private val CalcBg = Color(0xFF0A0510)
private val AshA = Color(0xFF1B1028)
private val AshB = Color(0xFF26163A)
private val AshC = Color(0xFF331F4D)
private val Ember = Color(0xFF935AD4)

/**
 * The calculator's backdrop: near-black violet, crumbling ash at the four corners (square flakes thinning out from each corner, a
 * few embers glowing in them) and a handful of ash specks drifting slowly up and across. Pixel squares only.
 */
private fun DrawScope.drawAsh(t: Float) {
    val cell = 4.dp.toPx().roundToInt().coerceAtLeast(2)
    val cols = (size.width / cell).toInt() + 1
    val rows = (size.height / cell).toInt() + 1
    val reachX = (cols * 0.34f).coerceAtMost(26f)
    val reachY = (rows * 0.17f).coerceAtMost(30f)
    for (cy in 0 until rows) for (cx in 0 until cols) {
        // distance from the nearest corner, as a fraction of the reach, so each corner burns away along a ragged edge
        val ux = minOf(cx, cols - 1 - cx) / reachX
        val uy = minOf(cy, rows - 1 - cy) / reachY
        val d = ux + uy
        if (d >= 1f) continue
        val h = hf(cx, cy * 7 + 3)
        val keep = (1f - d) * 1.25f - 0.1f
        if (h > keep) continue
        val deep = keep - h
        val c = when {
            h < 0.012f && d < 0.7f -> Ember // a few embers deep in the corner
            deep > 0.55f -> AshC
            deep > 0.25f -> AshB
            else -> AshA
        }
        drawRect(c, Offset((cx * cell).toFloat(), (cy * cell).toFloat()), Size(cell.toFloat(), cell.toFloat()))
    }
    // drifting specks
    for (k in 0 until 16) {
        val speed = 0.012f + 0.02f * hf(k, 1)
        val fy = (hf(k, 2) - t * speed).let { it - floor(it) }
        val fx = (hf(k, 3) + t * 0.004f * (1 + hf(k, 4)) + 0.03f * sin(t * 0.6f + k)).let { it - floor(it) }
        val s = if (k % 3 == 0) 3.dp.toPx() else 2.dp.toPx()
        val a = 0.12f + 0.25f * hf(k, 5)
        drawRect((if (k % 4 == 0) Ember else Px.Lavender).copy(alpha = a), Offset((fx * size.width).roundToInt().toFloat(), (fy * size.height).roundToInt().toFloat()), Size(s, s))
    }
}

@Composable
fun CalculatorScreen() {
    var state by rememberSaveable(stateSaver = CalcSaver) { mutableStateOf(CalcState()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val press = { k: Char -> state = CalcEngine.press(state, k) }
    val time = rememberRotTime()

    Column(
        Modifier
            .fillMaxSize()
            .background(CalcBg)
            .drawBehind { drawAsh(time.floatValue) }
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val k = when (e.key) {
                    Key.Enter, Key.NumPadEnter -> '='
                    Key.Backspace -> '⌫'
                    Key.Escape -> 'C'
                    else -> when (val c = e.utf16CodePoint.toChar()) {
                        in '0'..'9', '.', '+', '%', '=' -> c
                        '-' -> '−'
                        '*' -> '×'
                        '/' -> '÷'
                        else -> return@onPreviewKeyEvent false
                    }
                }
                press(k); true
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f).widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            // the framed display: a lavender edge, a thin dark line inside it, then the black glass
            val frame = PixelShape(4.dp)
            Box(Modifier.fillMaxWidth().clip(frame).background(Px.Lavender).padding(2.dp)) {
                Box(Modifier.fillMaxWidth().clip(frame).background(Px.Violet.copy(alpha = 0.55f)).padding(2.dp)) {
                    Column(Modifier.fillMaxWidth().clip(frame).background(Color(0xFF07030B)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            groupDigits(state.expr.replace(Regex("([+−×÷])"), " $1 ").trim() + if (state.result != null) " =" else ""),
                            modifier = Modifier.fillMaxWidth().height(26.dp),
                            style = TextStyle(fontFamily = PageFont, fontSize = 22.sp, color = Px.Lavender.copy(alpha = 0.75f), textAlign = TextAlign.End),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        val big = groupDigits(CalcEngine.shown(state))
                        BoxWithConstraints(Modifier.fillMaxWidth().height(76.dp), contentAlignment = Alignment.CenterEnd) {
                            val (fitted, size) = fitDisplay(big, maxWidth.value)
                            Text(
                                fitted,
                                style = TextStyle(fontFamily = PageFont, fontSize = size.sp, color = Px.Text, textAlign = TextAlign.End),
                                maxLines = 1, softWrap = false,
                            )
                        }
                    }
                }
            }
        }
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            KEYPAD.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { k -> CalcKey(k, press) }
                }
            }
        }
    }
}

@Composable
private fun RowScope.CalcKey(label: String, press: (Char) -> Unit) {
    val src = remember { MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val look = when {
        label == "=" -> EQKEY
        label[0].isDigit() || label == "." -> STONE
        else -> OPKEY
    }
    val seed = label[0].code
    val shape = PixelShape(3.dp)
    Box(
        Modifier
            .weight(if (label == "0") 2f else 1f)
            .height(64.dp)
            .offset(y = if (down) 2.dp else 0.dp)
            .clip(shape)
            .background(look.face)
            .drawBehind {
                if (down) drawRect(Color.Black.copy(alpha = 0.22f)) // a pressed key goes darker as well as 2 dp down
                val e = 4.dp.toPx().roundToInt().toFloat()
                val (tl, br) = if (down) look.dark to look.light else look.light to look.dark
                drawRect(tl, Offset.Zero, Size(size.width, e)) // top edge
                drawRect(tl, Offset.Zero, Size(e, size.height)) // left edge
                drawRect(br, Offset(0f, size.height - e), Size(size.width, e)) // bottom edge
                drawRect(br, Offset(size.width - e, 0f), Size(e, size.height)) // right edge
                // the corners where light meets dark, chipped to the middle tone
                drawRect(look.face, Offset(size.width - e, 0f), Size(e, e))
                drawRect(look.face, Offset(0f, size.height - e), Size(e, e))
                // stone grain: a few darker and lighter flecks, fixed per key
                val f = 3.dp.toPx().roundToInt().toFloat()
                for (g in 0 until 7) {
                    val gx = e + (size.width - 2 * e - f) * hf(seed, g * 2)
                    val gy = e + (size.height - 2 * e - f) * hf(seed, g * 2 + 1)
                    drawRect((if (g % 2 == 0) look.dark else look.light).copy(alpha = 0.35f), Offset(gx.roundToInt().toFloat(), gy.roundToInt().toFloat()), Size(f, f))
                }
            }
            .clickable(interactionSource = src, indication = null, role = Role.Button) { press(label[0]) },
        contentAlignment = Alignment.Center,
    ) {
        val glyph = OP_GLYPHS[label]
        if (glyph != null) PixelIcon(glyph, look.ink, 3.dp)
        else Text(label, style = TextStyle(fontFamily = PixelFont, fontSize = 18.sp, color = look.ink))
    }
}
