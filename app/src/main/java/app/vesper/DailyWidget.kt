package app.vesper

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import app.vesper.data.Idea
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId

/** The 2x2 home-screen Daily widget: the Daily items as little candles; a tap on a row lights or snuffs it, like the app's Daily card. */
class DailyWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = async(context) { refresh(context) }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> async(context) {
                val app = context.applicationContext as NotebookApp
                resetToday(app) // a widget left stale over midnight must not flip an old tick
                val item = app.db.brainDao().getIdea(intent.getLongExtra(EXTRA_ID, -1)) ?: return@async
                tickDaily(app.db, item, !item.done, LocalDate.now())
                refresh(context)
            }
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
        const val ACTION_TOGGLE = "app.vesper.DAILY_TOGGLE"
        const val EXTRA_ID = "id"
        const val MAX_ROWS = 8

        private suspend fun resetToday(app: NotebookApp) {
            val zone = ZoneId.systemDefault()
            val day = LocalDate.now(zone)
            resetDaily(app.db, app.settings, day, dayStart(day, zone))
        }

        suspend fun items(app: NotebookApp): List<Idea> {
            val dao = app.db.brainDao()
            return dailyItems(dao.observeLobes().first(), dao.observeIdeas().first(), DailyOrder.uids.value)
        }

        /** Redraws every placed widget (the header here, the rows through the list service). Cheap when none is placed. */
        suspend fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, DailyWidget::class.java))
            if (ids.isEmpty()) return
            armMidnight(context)
            val items = items(context.applicationContext as NotebookApp)
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val toggle = PendingIntent.getBroadcast(
                context, 1, Intent(context, DailyWidget::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, // mutable: each row fills in its own id
            )
            for (id in ids) {
                val v = RemoteViews(context.packageName, R.layout.widget_daily)
                v.setTextViewText(R.id.widget_header, "DAILY · ${items.count { it.done }}/${items.size}")
                v.setOnClickPendingIntent(R.id.widget_header, open)
                v.setRemoteAdapter(R.id.widget_list, Intent(context, DailyWidgetService::class.java).setData(Uri.parse("vesper://widget/$id")))
                v.setEmptyView(R.id.widget_list, R.id.widget_empty)
                v.setPendingIntentTemplate(R.id.widget_list, toggle)
                manager.updateAppWidget(id, v)
                manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
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

        /** A 7x11 pixel candle: lit = white wax and a lilac flame, else dark wax and no flame. */
        fun candle(lit: Boolean): Bitmap {
            val s = 3
            val bmp = Bitmap.createBitmap(7 * s, 11 * s, Bitmap.Config.ARGB_8888)
            fun px(x: Int, y: Int, w: Int, h: Int, c: Int) { for (i in x * s until (x + w) * s) for (j in y * s until (y + h) * s) bmp.setPixel(i, j, c) }
            if (lit) { px(3, 0, 1, 1, 0xFFB69AFF.toInt()); px(2, 1, 3, 2, 0xFFB69AFF.toInt()) }
            px(3, 3, 1, 1, 0xFF6B5A9A.toInt()) // wick
            px(2, 4, 3, 7, if (lit) Color.WHITE else 0xFF3A2A55.toInt())
            return bmp
        }
    }
}

class DailyWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext as NotebookApp)

    private class Factory(private val app: NotebookApp) : RemoteViewsFactory {
        private var items = emptyList<Idea>()
        private val lit by lazy { DailyWidget.candle(true) }
        private val dark by lazy { DailyWidget.candle(false) }

        override fun onDataSetChanged() { items = runBlocking { DailyWidget.items(app) } } // runs on a binder thread, where blocking is fine
        override fun getCount() = minOf(items.size, DailyWidget.MAX_ROWS) + if (items.size > DailyWidget.MAX_ROWS) 1 else 0
        override fun getViewAt(position: Int): RemoteViews {
            val v = RemoteViews(app.packageName, R.layout.widget_daily_row)
            val item = items.getOrNull(position)
            if (position >= DailyWidget.MAX_ROWS || item == null) { // the "+N" row
                v.setViewVisibility(R.id.widget_candle, View.INVISIBLE)
                v.setTextViewText(R.id.widget_name, "+${items.size - DailyWidget.MAX_ROWS}")
                v.setTextColor(R.id.widget_name, 0xFF8A7AB8.toInt())
                return v
            }
            v.setImageViewBitmap(R.id.widget_candle, if (item.done) lit else dark)
            v.setTextViewText(R.id.widget_name, item.title.ifBlank { "Untitled" })
            v.setTextColor(R.id.widget_name, if (item.done) 0xFFE6DCFF.toInt() else 0xFF9A8CC4.toInt())
            v.setOnClickFillInIntent(R.id.widget_row, Intent().putExtra(DailyWidget.EXTRA_ID, item.id))
            return v
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = items.getOrNull(position)?.id ?: -1L
        override fun hasStableIds() = true
        override fun onCreate() {}
        override fun onDestroy() {}
    }
}
