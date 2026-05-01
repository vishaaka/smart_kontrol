package com.example.kapiac.widget

import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.example.kapiac.DeviceConfigStore

object WidgetUpdater {
    const val ACTION_RELAY_COMMAND = "com.example.kapiac.widget.RELAY_COMMAND"
    const val EXTRA_CHANNEL = "channel"

    fun updateAll(context: Context) {
        DoorWidgetProvider.updateAll(context)
        DualRelayWidgetProvider.updateAll(context)
    }

    fun handleRelayCommand(provider: AppWidgetProvider, context: Context, intent: Intent) {
        val channel = intent.getIntExtra(EXTRA_CHANNEL, 1).coerceIn(1, 2)
        val config = DeviceConfigStore.load(context)
        val message = if (config.baseUrl.isBlank()) {
            "Hata: Uygulamadan cihaz adresi girin"
        } else {
            "${config.relayConfig(channel).label} komutu gonderiliyor..."
        }
        DeviceConfigStore.saveLastStatus(context, message)
        updateAll(context)
        RelayCommandWorker.enqueue(context.applicationContext, channel)
    }
}
