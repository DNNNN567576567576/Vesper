package app.vesper.ui

import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

private val worker = Executors.newSingleThreadExecutor { Thread(it, "vesper-sfx").apply { isDaemon = true } }
private val clips = HashMap<Sfx, Clip>() // touched only on [worker]

private fun clip(s: Sfx): Clip = clips.getOrPut(s) {
    val pcm = synth(s)
    val bytes = ByteArray(pcm.size * 2)
    for (i in pcm.indices) { bytes[i * 2] = pcm[i].toInt().toByte(); bytes[i * 2 + 1] = (pcm[i].toInt() shr 8).toByte() }
    AudioSystem.getClip().also { it.open(AudioFormat(SFX_RATE.toFloat(), 16, 1, true, false), bytes, 0, bytes.size) }
}

internal actual fun preloadSfx() = worker.execute { Sfx.entries.forEach { runCatching { clip(it) } } }

internal actual fun playSfx(s: Sfx) {
    worker.execute {
        try {
            val c = clip(s)
            c.stop(); c.framePosition = 0; c.start()
        } catch (_: Throwable) {} // no sound device: stay quiet
    }
}
