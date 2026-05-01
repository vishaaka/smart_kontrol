package com.example.kapiac.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.kapiac.DeviceConfigStore
import com.example.kapiac.MainActivity
import com.example.kapiac.R

class DualRelayWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { appWidgetId ->
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetUpdater.ACTION_RELAY_COMMAND) {
            WidgetUpdater.handleRelayCommand(this, context, intent)
        }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val widgetComponent = ComponentName(context, DualRelayWidgetProvider::class.java)
            val widgetIds = manager.getAppWidgetIds(widgetComponent)
            widgetIds.forEach { widgetId ->
                updateWidget(context, manager, widgetId)
            }
        }

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val config = DeviceConfigStore.load(context)
            val relay1 = config.relay1
            val relay2 = config.relay2
            val views = RemoteViews(context.packageName, R.layout.widget_dual_relay)

            val relay1Intent = Intent(context, DualRelayWidgetProvider::class.java).apply {
                action = WidgetUpdater.ACTION_RELAY_COMMAND
                putExtra(WidgetUpdater.EXTRA_CHANNEL, 1)
            }
            val relay2Intent = Intent(context, DualRelayWidgetProvider::class.java).apply {
                action = WidgetUpdater.ACTION_RELAY_COMMAND
                putExtra(WidgetUpdater.EXTRA_CHANNEL, 2)
            }

            val relay1PendingIntent = PendingIntent.getBroadcast(
                context,
                2101,
                relay1Intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val relay2PendingIntent = PendingIntent.getBroadcast(
                context,
                2102,
                relay2Intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val appIntent = Intent(context, MainActivity::class.java)
            val appPendingIntent = PendingIntent.getActivity(
                context,
                2200,
                appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            views.setTextViewText(R.id.widgetTitleText, context.getString(R.string.app_name))
            views.setTextViewText(R.id.widgetRelay1Button, relay1.label)
            views.setTextViewText(R.id.widgetRelay2Button, relay2.label)
            views.setOnClickPendingIntent(R.id.widgetRelay1Button, relay1PendingIntent)
            views.setOnClickPendingIntent(R.id.widgetRelay2Button, relay2PendingIntent)
            views.setOnClickPendingIntent(R.id.widgetRoot, appPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
