package app.vesper

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Plays the alarm sound, vibrates and shows the ringing notification until the alarm is stopped, snoozed or times out. */
class AlarmService : Service() {
    private class Ring(val id: Long, val hour: Int, val minute: Int, val label: String, val once: Boolean)

    private var ring: Ring? = null
    private var player: MediaPlayer? = null
    private var wake: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { end(snooze = false); stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AlarmKeys.RING -> start(intent)
            AlarmKeys.STOP -> { end(snooze = false); stopSelf() }
            AlarmKeys.SNOOZE -> { end(snooze = true); stopSelf() }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        end(snooze = false) // killed or stopped from outside: a ringing one-time alarm still counts as rung
        super.onDestroy()
    }

    private fun start(i: Intent) {
        end(snooze = false) // a second alarm while one rings replaces it
        val r = Ring(
            i.getLongExtra(AlarmKeys.ID, -1), i.getIntExtra(AlarmKeys.HOUR, 0), i.getIntExtra(AlarmKeys.MINUTE, 0),
            i.getStringExtra(AlarmKeys.LABEL).orEmpty(), i.getBooleanExtra(AlarmKeys.ONCE, true),
        )
        ring = r
        ringing.value = r.id
        AlarmKeys.ensureChannel(this)
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(r),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
        } catch (e: Exception) {
            Log.e("Vesper", "alarm foreground start", e) // still try to make noise below
        }
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vesper:alarm").apply { acquire(AlarmKeys.RING_MS + 60_000) }
        play()
        vibrate()
        handler.postDelayed(timeout, AlarmKeys.RING_MS)
    }

    private fun notification(r: Ring): Notification {
        val screen = PendingIntent.getActivity(
            this, 0, ringScreen(this, r.hour, r.minute, r.label),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(name: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, AlarmService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, AlarmKeys.CHANNEL)
            .setSmallIcon(R.drawable.ic_alarm)
            .setColor(0xFF7540B8.toInt())
            .setContentTitle(r.label.ifBlank { "Alarm" })
            .setContentText(AlarmLogic.timeLabel(r.hour, r.minute))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .addAction(0, "Stop", action(AlarmKeys.STOP, 1))
            .addAction(0, "Snooze 10 min", action(AlarmKeys.SNOOZE, 2))
            .build()
    }

    private fun play() {
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
                ?: return
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@AlarmService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e("Vesper", "alarm sound", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
        else getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    @Suppress("DEPRECATION")
    private fun vibrate() {
        try {
            vibrator()?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 800, 700), 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        } catch (e: Exception) {
            Log.e("Vesper", "alarm vibration", e)
        }
    }

    /** Silences everything. [snooze] re-arms the alarm 10 minutes on; otherwise a one-time alarm moves to the history. */
    private fun end(snooze: Boolean) {
        val r = ring ?: return
        ring = null
        handler.removeCallbacks(timeout)
        try { player?.stop() } catch (_: Exception) {}
        player?.release()
        player = null
        vibrator()?.cancel()
        wake?.takeIf { it.isHeld }?.release()
        wake = null
        ringing.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val app = application as NotebookApp
        if (snooze) app.alarms.snooze(r.id)
        else if (r.once) app.appScope.launch {
            try {
                val dao = app.db.alarmDao()
                dao.get(r.id)?.takeIf { it.firedAt == null }?.let { dao.update(it.copy(firedAt = System.currentTimeMillis())) }
            } catch (e: Exception) { app.reportError("save alarm history", e) }
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 7001

        /** Id of the alarm ringing right now, else null; the ringing screen closes itself when this goes null. */
        val ringing = MutableStateFlow<Long?>(null)
    }
}
