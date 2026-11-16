package app.vesper.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.Executors

private var audio: AudioManager? = null
private val worker = Executors.newSingleThreadExecutor { Thread(it, "vesper-sfx").apply { isDaemon = true } }
private val tracks = HashMap<Sfx, AudioTrack>() // touched only on [worker]

/** Call once from the Application: keeps the AudioManager for the ringer check (without it nothing plays). */
fun initSounds(context: Context) {
    audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
}

internal actual fun preloadSfx() = worker.execute { Sfx.entries.forEach { runCatching { track(it) } } }

private fun track(s: Sfx): AudioTrack = tracks.getOrPut(s) {
    val pcm = synth(s)
    AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SFX_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(pcm.size * 2)
        .build()
        .also { it.write(pcm, 0, pcm.size) }
}

internal actual fun playSfx(s: Sfx) {
    // silent and vibrate mean quiet: no sounds at all
    if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
    worker.execute {
        try {
            val t = track(s)
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
        } catch (_: Throwable) {}
    }
}
