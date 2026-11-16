package app.vesper.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** The Brain's little sounds, all made in code (no audio files). */
enum class Sfx { WhooshIn, WhooshOut, Open, Close, Tick }

/** Plays [Sfx] sounds while [on] (the main brain menu's "Sounds" chip). Never throws, never blocks the caller. */
object Sounds {
    @Volatile var on = true
    init { try { preloadSfx() } catch (_: Throwable) {} }
    fun play(s: Sfx) {
        if (on) try { playSfx(s) } catch (_: Throwable) {}
    }
}

/** Queues [s] on the platform player (built once from [synth], then cached). */
internal expect fun playSfx(s: Sfx)

/** Builds every sound's buffer on the player's own thread, so the first play has no lag. */
internal expect fun preloadSfx()

internal const val SFX_RATE = 22050

/** Seconds each sound lasts. */
internal fun sfxSeconds(s: Sfx) = when (s) {
    Sfx.WhooshIn -> 0.45f
    Sfx.WhooshOut -> 0.40f
    Sfx.Open -> 0.24f
    Sfx.Close -> 0.17f
    Sfx.Tick -> 0.035f
}

/** Peak of each sound as a part of full scale: all quiet, the closing ones quieter. */
internal fun sfxPeak(s: Sfx) = when (s) {
    Sfx.WhooshIn -> 0.015f // noise sounds far louder than a chime at the same peak
    Sfx.WhooshOut -> 0.015f
    Sfx.Open -> 0.065f
    Sfx.Close -> 0.045f
    Sfx.Tick -> 0.035f
}

/** 16-bit mono PCM at [SFX_RATE] for [s]: a band-passed noise sweep for a whoosh, two decaying sine partials for a chime or tick. */
internal fun synth(s: Sfx): ShortArray {
    val n = (sfxSeconds(s) * SFX_RATE).roundToInt()
    val y = FloatArray(n)
    when (s) {
        Sfx.WhooshIn, Sfx.WhooshOut -> {
            val rnd = Random(7)
            var lo = 0f; var hi = 0f
            for (i in 0 until n) {
                val u = i / n.toFloat()
                // the band sweeps up then settles; WhooshOut is the same sweep played backwards
                val f = 250f + 2200f * sin(PI.toFloat() * min(1f, u * 1.2f))
                val k = 1f - exp(-2f * PI.toFloat() * f / SFX_RATE)
                val x = rnd.nextFloat() * 2f - 1f
                lo += (x - lo) * k
                hi += (x - hi) * (k * 0.25f)
                val env = sin(PI.toFloat() * u).let { it * it } * (0.6f + 0.4f * u)
                y[i] = (lo - hi) * env
            }
            if (s == Sfx.WhooshOut) y.reverse()
        }
        else -> {
            val (f1, f2, decay) = when (s) {
                Sfx.Open -> Triple(1318.5f, 1975.5f, 18f)
                Sfx.Close -> Triple(987.8f, 1480f, 24f)
                else -> Triple(2400f, 3600f, 140f)
            }
            for (i in 0 until n) {
                val t = i / SFX_RATE.toFloat()
                val attack = min(1f, t / 0.004f)
                y[i] = attack * (sin(2f * PI.toFloat() * f1 * t) * exp(-decay * t) + 0.45f * sin(2f * PI.toFloat() * f2 * t) * exp(-decay * 1.6f * t))
            }
        }
    }
    // a short fade at both ends (no clicks), then scaled to the sound's quiet peak
    val edge = min(n / 4, SFX_RATE / 200)
    for (i in 0 until edge) { val g = i / edge.toFloat(); y[i] *= g; y[n - 1 - i] *= g }
    var peak = 0f
    for (v in y) peak = max(peak, abs(v))
    val g = if (peak > 0f) sfxPeak(s) * 32767f / peak else 0f
    return ShortArray(n) { (y[it] * g).roundToInt().coerceIn(-32767, 32767).toShort() }
}
