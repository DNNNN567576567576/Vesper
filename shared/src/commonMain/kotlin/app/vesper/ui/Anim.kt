package app.vesper.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

// Small, cheap animations (2.8.2). Everything here is pure maths on an age in seconds, read while drawing: no layout is rebuilt per frame.

/** Ease-out cubic of [p] (clamped to 0..1). */
internal fun easeOut(p: Float): Float { val q = 1f - p.coerceIn(0f, 1f); return 1f - q * q * q }

/** 0 at both ends, 1 in the middle of [p] 0..1. */
internal fun hump(p: Float): Float = if (p <= 0f || p >= 1f) 0f else sin(p * PI.toFloat())

internal const val POP_S = 0.28f
internal const val DRIFT_S = 0.3f
internal const val TAP_S = 0.3f

/** A thing's size 0.6 -> 1 over [POP_S] after it appeared [age] seconds ago; 1 once settled. */
internal fun popScale(age: Float): Float = if (age >= POP_S) 1f else 0.6f + 0.4f * easeOut(age / POP_S)

/** A tapped brain's squish (about 5 %) [age] seconds after the tap; 1 at rest. */
internal fun tapSquish(age: Float): Float = 1f - 0.05f * hump(age / TAP_S)

/** The glow pulse (0..1) of a tapped brain [age] seconds after the tap. */
internal fun tapGlow(age: Float): Float = hump(age / TAP_S)

/**
 * When each key first showed, so what appears later pops in ([pop]) and what was there when the view opened drifts into place
 * ([drift]). Call [update] once a frame with the keys now shown; it only touches a map, nothing is allocated for known keys.
 */
internal class Births {
    private val born = HashMap<String, Float>()
    private var t0 = Float.NaN
    private var seeded = false

    fun update(keys: Iterable<String>, t: Float) {
        if (t0.isNaN()) t0 = t
        for (k in keys) if (k !in born) born[k] = if (seeded) t else t0
        seeded = true
    }

    private fun age(k: String, t: Float) = t - (born[k] ?: t0)
    private fun initial(k: String) = born[k] == t0

    /** 0.6..1: how big a key is that appeared after the view opened. */
    fun pop(k: String, t: Float): Float = if (initial(k)) 1f else popScale(age(k, t))

    /** 0..1: how far a key that was there when the view opened has drifted into place. */
    fun drift(k: String, t: Float): Float = if (initial(k)) easeOut(age(k, t) / DRIFT_S) else 1f
}

private val ASH = intArrayOf(0xB69AFF, 0xE6DCFF, 0x7F66E6, 0x4A3478)

/** [n] ash pixels crumbling off the box at [c] ([w] x [h]) [p] (0..1) of the way: they fall, drift sideways and fade. [px] is a pixel's size. */
internal fun DrawScope.ashFall(c: Offset, w: Float, h: Float, p: Float, px: Float, n: Int = 36) {
    if (p <= 0f || p >= 1f) return
    for (i in 0 until n) {
        val x = c.x - w / 2f + hf(i, 5) * w + (hf(i, 6) - 0.5f) * 20f * p
        val y = c.y - h / 2f + hf(i, 7) * h + p * p * (30f + hf(i, 8) * 90f)
        val a = (1f - p) * (0.6f + 0.4f * hf(i, 9))
        drawRect(Color(0xFF000000.toInt() or ASH[i % ASH.size]).copy(alpha = a), Offset(floor(x / px) * px, floor(y / px) * px), Size(px, px))
    }
}
