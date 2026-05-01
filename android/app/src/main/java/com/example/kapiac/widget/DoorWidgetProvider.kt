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

class DoorWidgetProvider : AppWidgetProvider() {
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
            val widgetComponent = ComponentName(context, DoorWidgetProvider::class.java)
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
            val primaryChannel = config.primaryRelay
            val relay = config.relayConfig(primaryChannel)
            val views = RemoteViews(context.packageName, R.layout.widget_single_relay)

            val actionIntent = Intent(context, DoorWidgetProvider::class.java).apply {
                action = WidgetUpdater.ACTION_RELAY_COMMAND
                putExtra(WidgetUpdater.EXTRA_CHANNEL, primaryChannel)
            }
            val actionPendingIntent = PendingIntent.getBroadcast(
                context,
                1100 + primaryChannel,
                actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val appIntent = Intent(context, MainActivity::class.java)
            val appPendingIntent = PendingIntent.getActivity(
                context,
                1200,
                appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            views.setTextViewText(R.id.widgetTitleText, context.getString(R.string.app_name))
            views.setTextViewText(R.id.widgetOpenButton, relay.label)
            views.setOnClickPendingIntent(R.id.widgetOpenButton, actionPendingIntent)
            views.setOnClickPendingIntent(R.id.widgetRoot, appPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
