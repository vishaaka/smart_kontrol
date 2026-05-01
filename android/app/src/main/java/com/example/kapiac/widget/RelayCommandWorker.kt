package com.example.kapiac.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.kapiac.DeviceConfigStore
import com.example.kapiac.DoorApi

class RelayCommandWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val channel = inputData.getInt(KEY_CHANNEL, 1).coerceIn(1, 2)
        val context = applicationContext
        val config = DeviceConfigStore.load(context)
        val result = if (config.baseUrl.isBlank()) {
            "Hata: Uygulamadan cihaz adresi girin"
        } else {
            val response = DoorApi.performRelayAction(config, channel)
            if (response.ok) {
                "${config.relayConfig(channel).label}: ${response.message}"
            } else {
                "Hata: ${response.message}"
            }
        }

        DeviceConfigStore.saveLastStatus(context, result)
        WidgetUpdater.updateAll(context)
        return Result.success()
    }

    companion object {
        private const val KEY_CHANNEL = "channel"

        fun enqueue(context: Context, channel: Int) {
            val request = OneTimeWorkRequestBuilder<RelayCommandWorker>()
                .setInputData(Data.Builder().putInt(KEY_CHANNEL, channel).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
