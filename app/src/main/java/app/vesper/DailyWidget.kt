package app.vesper

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import app.vesper.data.Idea
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** The home-screen Daily widget: the Daily items as candles in centred rows, drawn as pictures in the app's fonts; a tap anywhere opens the app on the Daily card. */
class DailyWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = async(context) { refresh(context) }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) = async(context) { refresh(context) }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> async(context) {
                resetToday(context.applicationContext as NotebookApp)
                refresh(context)
            }
            else -> super.onReceive(context, intent)
        }
    }

    private fun async(context: Context, block: suspend () -> Unit) {
        val pending = goAsync()
        (context.applicationContext as NotebookApp).appScope.launch {
            try { block() } catch (e: Exception) { Log.e("Vesper", "daily widget", e) } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_OPEN_DAILY = "app.vesper.OPEN_DAILY"
        private suspend fun resetToday(app: NotebookApp) {
            val zone = ZoneId.systemDefault()
            val day = LocalDate.now(zone)
            resetDaily(app.db, app.settings, day, dayStart(day, zone))
        }

        suspend fun items(app: NotebookApp): List<Idea> {
            val dao = app.db.brainDao()
            return dailyItems(dao.observeLobes().first(), dao.observeIdeas().first(), DailyOrder.uids.value)
        }

        /** The widget [id]'s size in dp (portrait: its narrowest width, its tallest height). */
        fun sizeDp(manager: AppWidgetManager, id: Int): Pair<Float, Float> {
            val o = manager.getAppWidgetOptions(id)
            return o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).toFloat() to o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180).toFloat()
        }

        /** What a cell shows for an item. */
        fun label(i: Idea) = i.title.ifBlank { i.body.lineSequence().firstOrNull().orEmpty() }.ifBlank { "..." }

        /** Redraws every placed widget (the frame, the header and the body, each one picture). Cheap when none is placed. */
        suspend fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, DailyWidget::class.java))
            if (ids.isEmpty()) return
            armMidnight(context)
            val items = items(context.applicationContext as NotebookApp)
            val d = context.resources.displayMetrics.density
            val open = PendingIntent.getActivity(
                // Own action + request code: an intent equal to the launcher's only brings the task forward and never reaches onNewIntent.
                context, 3, Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_DAILY).putExtra("open", "daily").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            for (id in ids) {
                val (w, h) = sizeDp(manager, id)
                val (bw, bh) = widgetBody(w, h)
                val v = RemoteViews(context.packageName, R.layout.widget_daily)
                if (Build.VERSION.SDK_INT >= 31) WidgetArt.layoutFrame(v, d, context.resources.getDimension(android.R.dimen.system_app_widget_background_radius)) else v.setImageViewBitmap(R.id.widget_frame, WidgetArt.frame(w, h, d))
                v.setImageViewBitmap(R.id.widget_header, WidgetArt.header(context, w - 2 * WIDGET_PAD, items.count { it.done }, items.size, d))
                val bd = widgetBodyDensity(bw, bh, d)
                v.setImageViewBitmap(R.id.widget_body, if (items.isEmpty()) WidgetArt.empty(context, bw, bh, bd)
                    else WidgetArt.body(context, items.map { label(it) to it.done }, widgetLayout(items.size, w, h), bw, bh, bd))
                for (view in intArrayOf(R.id.widget_root, R.id.widget_frame, R.id.widget_header, R.id.widget_body)) v.setOnClickPendingIntent(view, open)
                if (Build.VERSION.SDK_INT >= 31) for (view in intArrayOf(R.id.fc_card, R.id.fc_panel, R.id.fc_top_edge, R.id.fc_top_line, R.id.fc_bottom_edge, R.id.fc_bottom_line,
                    R.id.fc_left_edge, R.id.fc_left_line, R.id.fc_right_edge, R.id.fc_right_line, R.id.fc_tl, R.id.fc_tr, R.id.fc_bl, R.id.fc_br)) v.setOnClickPendingIntent(view, open)
                manager.updateAppWidget(id, v)
            }
        }

        // DATE_CHANGED never reaches a manifest receiver on Android 8+, so an inexact alarm snuffs yesterday's candles instead.
        private fun armMidnight(context: Context) {
            val zone = ZoneId.systemDefault()
            val at = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 60_000
            val pi = PendingIntent.getBroadcast(context, 2, Intent(context, DailyWidget::class.java).setAction(Intent.ACTION_DATE_CHANGED),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            context.getSystemService(android.app.AlarmManager::class.java).setAndAllowWhileIdle(android.app.AlarmManager.RTC, at, pi)
        }
    }
}
