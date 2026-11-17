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

private val KEY_DIGIT = Color(0xFF2A1745)
private val KEY_OP = Px.Primary
private val KEY_EQ = Px.Text

private val KEYPAD = listOf(
    listOf("C", "⌫", "%", "÷"),
    listOf("7", "8", "9", "×"),
    listOf("4", "5", "6", "−"),
    listOf("1", "2", "3", "+"),
    listOf("0", ".", "="),
)

private val CalcSaver = listSaver<CalcState, String>(
    save = { listOf(it.expr, it.result ?: "\u0000") },
    restore = { CalcState(it[0], it[1].takeIf { r -> r != "\u0000" }) },
)

@Composable
fun CalculatorScreen() {
    var state by rememberSaveable(stateSaver = CalcSaver) { mutableStateOf(CalcState()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val press = { k: Char -> state = CalcEngine.press(state, k) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Px.Bg)
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
            Modifier.weight(1f).widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text(
                state.expr.replace(Regex("([+−×÷])"), " $1 ").trim() + if (state.result != null) " =" else "",
                modifier = Modifier.fillMaxWidth().height(24.dp),
                style = TextStyle(fontFamily = PageFont, fontSize = 20.sp, color = Px.Lavender.copy(alpha = 0.7f), textAlign = TextAlign.End),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val big = CalcEngine.shown(state)
            BoxWithConstraints(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.CenterEnd) {
                // VT323 glyphs are ~0.4em wide: shrink until the number fits.
                val size = (maxWidth.value / (big.length.coerceAtLeast(1) * 0.42f)).coerceIn(28f, 72f)
                Text(
                    big,
                    style = TextStyle(fontFamily = PageFont, fontSize = size.sp, color = Px.Text, textAlign = TextAlign.End),
                    maxLines = 1, softWrap = false,
                )
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
    val eq = label == "="
    val fill = when {
        eq -> KEY_EQ
        label[0].isDigit() || label == "." -> KEY_DIGIT
        else -> KEY_OP
    }
    val shape = PixelShape(3.dp)
    Box(
        Modifier
            .weight(if (label == "0") 2f else 1f)
            .height(64.dp)
            .offset(y = if (down) 2.dp else 0.dp)
            .clip(shape)
            .background(if (down) fill.copy(alpha = 0.75f) else fill)
            .border(2.dp, if (eq) Px.Lavender else Px.Border, shape)
            .clickable(interactionSource = src, indication = null, role = Role.Button) { press(label[0]) },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TextStyle(fontFamily = PixelFont, fontSize = 18.sp, color = if (eq) Px.Bg else Px.Text))
    }
}
