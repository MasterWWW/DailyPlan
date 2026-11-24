package com.dailyplan.android.widget

import android.content.Context
import android.widget.Toast
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.dailyplan.android.DailyPlanApplication
import com.dailyplan.android.SyncWorker

class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val taskId = parameters[DailyPlanWidget.taskIdKey] ?: return
        val app = context.applicationContext as DailyPlanApplication
        runCatching {
            app.container.repository.updateTask(taskId, completed = true)
            DailyPlanWidget.updateAll(context)
            SyncWorker.syncNow(context)
        }.onFailure {
            Toast.makeText(context, it.message ?: "完成失败", Toast.LENGTH_SHORT).show()
        }
    }
}
