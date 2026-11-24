package com.dailyplan.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class DailyPlanApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannel()
        SyncWorker.enqueuePeriodic(this)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            ReminderNotifier.CHANNEL_ID,
            "上下班提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "在工作日上下班时提醒查看未完成事项"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
