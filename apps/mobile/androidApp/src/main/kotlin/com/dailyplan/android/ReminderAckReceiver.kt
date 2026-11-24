package com.dailyplan.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReminderAckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ACKNOWLEDGE) return
        val workDate = intent.getStringExtra(EXTRA_WORK_DATE) ?: return
        val period = intent.getStringExtra(EXTRA_PERIOD) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val application = context.applicationContext as DailyPlanApplication
                application.container.repository.acknowledgeReminder(workDate, period)
            } finally {
                ReminderNotifier.dismiss(context)
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_ACKNOWLEDGE = "com.dailyplan.android.ACKNOWLEDGE_REMINDER"
        const val EXTRA_WORK_DATE = "workDate"
        const val EXTRA_PERIOD = "period"
    }
}
