package app.vesper.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** The Brain's little sounds, all made in code (no audio files). */
enum class Sfx { Open, Close, Tick }

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
    Sfx.Open -> 0.24f
    Sfx.Close -> 0.17f
    Sfx.Tick -> 0.035f
}

/** Peak of each sound as a part of full scale: all quiet, the closing ones quieter. */
internal fun sfxPeak(s: Sfx) = when (s) {
    Sfx.Open -> 0.065f
    Sfx.Close -> 0.045f
    Sfx.Tick -> 0.035f
}

/** 16-bit mono PCM at [SFX_RATE] for [s]: two decaying sine partials for a chime or tick. */
internal fun synth(s: Sfx): ShortArray {
    val n = (sfxSeconds(s) * SFX_RATE).roundToInt()
    val y = FloatArray(n)
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
    // a short fade at both ends (no clicks), then scaled to the sound's quiet peak
    val edge = min(n / 4, SFX_RATE / 200)
    for (i in 0 until edge) { val g = i / edge.toFloat(); y[i] *= g; y[n - 1 - i] *= g }
    var peak = 0f
    for (v in y) peak = max(peak, abs(v))
    val g = if (peak > 0f) sfxPeak(s) * 32767f / peak else 0f
    return ShortArray(n) { (y[it] * g).roundToInt().coerceIn(-32767, 32767).toShort() }
}
