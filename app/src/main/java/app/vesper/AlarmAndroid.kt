package app.vesper

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.vesper.data.Alarm
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZoneId

/** Intent actions and extras shared by the alarm receiver, service and ringing screen. */
internal object AlarmKeys {
    const val RING = "app.vesper.alarm.RING"
    const val SNOOZE_RING = "app.vesper.alarm.SNOOZE_RING"
    const val STOP = "app.vesper.alarm.STOP"
    const val SNOOZE = "app.vesper.alarm.SNOOZE"
    const val ID = "id"
    const val HOUR = "hour"
    const val MINUTE = "minute"
    const val LABEL = "label"
    const val ONCE = "once"
    const val CHANNEL = "alarms"
    const val DAILY = "app.vesper.daily.REMIND"
    const val DAILY_CHANNEL = "daily"
    const val DAILY_NOTE = 2_000_000_001 // the Daily reminder's notification id, apart from any alarm's
    const val SNOOZE_MS = 10 * 60_000L
    const val RING_MS = 10 * 60_000L

    /** A one-time alarm missed while the phone was off still rings (shortly after boot) if it is no later than this. */
    const val GRACE_MS = 10 * 60_000L

    fun ensureChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Ringing alarms"
            setSound(null, null) // the service plays the alarm sound itself
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        // the Daily reminder: one quiet line in the evening, no sound, no ring
        val daily = NotificationChannel(DAILY_CHANNEL, "Daily", NotificationManager.IMPORTANCE_LOW).apply {
            description = "What is left on the daily list in the evening"
        }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(daily)
    }
}

/** Arms alarms with AlarmManager.setAlarmClock. Ring and snooze are separate PendingIntents so a snooze never replaces the next real ring. */
class AndroidAlarmScheduler(private val app: NotebookApp) : AlarmScheduler {
    override val supported = true
    private val am get() = app.getSystemService(AlarmManager::class.java)
    private val zone get() = ZoneId.systemDefault()

    private fun pi(id: Long, snooze: Boolean) = PendingIntent.getBroadcast(
        app, 0,
        Intent(app, AlarmReceiver::class.java)
            .setAction(if (snooze) AlarmKeys.SNOOZE_RING else AlarmKeys.RING)
            .setData(Uri.parse("vesper-alarm://$id"))
            .putExtra(AlarmKeys.ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun setAt(id: Long, at: Long, snooze: Boolean) {
        val show = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi(id, snooze))
        } catch (e: SecurityException) {
            Log.e("Vesper", "exact alarms not allowed", e) // problem() tells the Moons hint
        }
    }

    override fun schedule(alarm: Alarm) {
        am.cancel(pi(alarm.id, true))
        val at = AlarmLogic.nextTrigger(alarm, System.currentTimeMillis(), zone)
        if (at == null) am.cancel(pi(alarm.id, false)) else setAt(alarm.id, at, false)
    }

    override fun cancel(id: Long) {
        am.cancel(pi(id, false))
        am.cancel(pi(id, true))
    }

    fun snooze(id: Long) = setAt(id, System.currentTimeMillis() + AlarmKeys.SNOOZE_MS, true)

    private fun dailyPi() = PendingIntent.getBroadcast(
        app, 1, Intent(app, AlarmReceiver::class.java).setAction(AlarmKeys.DAILY),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun scheduleDaily(hour: Int?) {
        val pi = dailyPi()
        if (hour == null) { am.cancel(pi); return }
        val at = dailyRemindAt(hour, System.currentTimeMillis(), zone)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) // a few minutes late is fine for a reminder
        }
    }

    /**
     * Re-arms every active alarm (app start, boot, clock or zone change; AlarmManager forgets them on reboot).
     * [afterGap]: the phone may have been off, so a one-time alarm missed by a little still rings; older ones go to the history.
     */
    suspend fun rescheduleAll(afterGap: Boolean) {
        scheduleDaily(dailyRemindHour(app.settings))
        val now = System.currentTimeMillis()
        val dao = app.db.alarmDao()
        for (a in dao.observeActive().first()) {
            val at = AlarmLogic.nextTrigger(a, now, zone)
            when {
                !a.enabled -> cancel(a.id)
                at == null -> {}
                at > now -> setAt(a.id, at, false)
                now - at <= AlarmKeys.GRACE_MS -> if (afterGap) setAt(a.id, now + 3000, false)
                else -> { dao.update(a.copy(firedAt = now)); cancel(a.id) }
            }
        }
    }

    override fun problem(): AlarmProblem? = when {
        Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms() -> AlarmProblem.Exact
        !NotificationManagerCompat.from(app).areNotificationsEnabled() -> AlarmProblem.Notifications
        Build.VERSION.SDK_INT >= 34 && !app.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() -> AlarmProblem.FullScreen
        else -> null
    }

    override fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && !NotificationManagerCompat.from(app).areNotificationsEnabled()) app.askNotifications?.invoke()
    }

    override fun openSettings(problem: AlarmProblem) {
        val intent = when (problem) {
            AlarmProblem.Exact ->
                if (Build.VERSION.SDK_INT >= 31) Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${app.packageName}")) else return
            AlarmProblem.Notifications ->
                Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, app.packageName)
            AlarmProblem.FullScreen ->
                if (Build.VERSION.SDK_INT >= 34) Intent(AndroidSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${app.packageName}")) else return
        }
        try { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { app.reportError("open settings", e) }
    }
}

/** Alarm times arriving, and the system events that wipe or move them (boot, app update, clock, time zone). */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as NotebookApp
        val pending = goAsync()
        app.appScope.launch {
            try {
                when (intent.action) {
                    AlarmKeys.RING -> ring(app, intent.getLongExtra(AlarmKeys.ID, -1), snoozed = false)
                    AlarmKeys.SNOOZE_RING -> ring(app, intent.getLongExtra(AlarmKeys.ID, -1), snoozed = true)
                    AlarmKeys.DAILY -> remindDaily(app)
                    else -> app.alarms.rescheduleAll(afterGap = true) // BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_SET, TIMEZONE_CHANGED
                }
            } catch (e: Exception) {
                Log.e("Vesper", "alarm receiver", e)
            } finally {
                pending.finish()
            }
        }
    }

    /** The evening reminder: arms tomorrow's, then posts one quiet line if anything on the daily list is still undone today. */
    private suspend fun remindDaily(app: NotebookApp) {
        app.alarms.scheduleDaily(dailyRemindHour(app.settings))
        val dao = app.db.brainDao()
        val text = dailyReminder(dailyItems(dao.observeLobes().first(), dao.observeIdeas().first()), System.currentTimeMillis(), ZoneId.systemDefault()) ?: return
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(app, 2, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = androidx.core.app.NotificationCompat.Builder(app, AlarmKeys.DAILY_CHANNEL)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentText(text)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(true)
            .build()
        try { NotificationManagerCompat.from(app).notify(AlarmKeys.DAILY_NOTE, n) } catch (e: SecurityException) { Log.e("Vesper", "daily reminder", e) }
    }

    private suspend fun ring(app: NotebookApp, id: Long, snoozed: Boolean) {
        val a = app.db.alarmDao().get(id) ?: return
        val once = a.repeatDays and AlarmLogic.WEEK == 0
        if (!snoozed) {
            if (!a.enabled || a.firedAt != null) return
            if (!once) app.alarms.schedule(a) // the next occurrence; a one-time alarm goes to history when it is stopped
        }
        ContextCompat.startForegroundService(
            app,
            Intent(app, AlarmService::class.java).setAction(AlarmKeys.RING)
                .putExtra(AlarmKeys.ID, a.id).putExtra(AlarmKeys.HOUR, a.hour).putExtra(AlarmKeys.MINUTE, a.minute)
                .putExtra(AlarmKeys.LABEL, a.label).putExtra(AlarmKeys.ONCE, once),
        )
    }
}
