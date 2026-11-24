package com.dailyplan.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.dailyplan.shared.rules.nextReminderInstant
import kotlinx.coroutines.flow.first
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

object ReminderScheduler {
    private const val REQUEST_CODE = 4107

    suspend fun scheduleNext(context: Context) {
        val app = context.applicationContext as DailyPlanApplication
        val settings = app.container.repository.settings()
        val now = Clock.System.now()
        val next = nextReminderInstant(now, settings)
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pending = pendingIntent(context)
        if (next == null) {
            alarmManager.cancel(pending)
            return
        }
        val triggerAt = next.toEpochMilliseconds()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !alarmManager.canScheduleExactAlarms()
        ) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pending,
            )
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pending,
            )
        }
    }

    fun nextLabel(
        now: Instant,
        settings: com.dailyplan.shared.model.WorkSettings,
    ): String? {
        val next = nextReminderInstant(now, settings) ?: return null
        val local = next.toLocalDateTime(TimeZone.of(settings.timeZone))
        return "${local.date} ${local.hour.toString().padStart(2, '0')}:" +
            local.minute.toString().padStart(2, '0')
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ReminderReceiver::class.java)
                .setAction(ReminderReceiver.ACTION_REMIND),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
