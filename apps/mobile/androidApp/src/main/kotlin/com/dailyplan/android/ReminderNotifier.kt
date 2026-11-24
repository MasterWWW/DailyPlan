package com.dailyplan.android

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dailyplan.shared.model.Priority
import com.dailyplan.shared.model.Task
import com.dailyplan.shared.rules.evaluate
import kotlin.time.Clock

object ReminderNotifier {
    const val CHANNEL_ID = "dailyplan-reminders"
    private const val NOTIFICATION_ID = 3107

    suspend fun showIfDue(context: Context, container: AppContainer) {
        val snapshot = container.repository.snapshot()
        val now = Clock.System.now()
        val evaluation = runCatching {
            evaluate(
                now = now,
                tasks = snapshot.tasks + snapshot.completed,
                settings = snapshot.settings,
                acknowledged = snapshot.acknowledged,
            )
        }.getOrNull() ?: return
        val reminder = evaluation.reminder ?: return
        val period = reminder.period.name.lowercase()
        if (container.repository.reminderWasShown(reminder.workDate, period)) return
        val tasksById = evaluation.visible.associateBy { it.taskId }
        val attentionById = evaluation.attention.associateBy { it.taskId }
        val reminderTasks = reminder.taskIds.mapNotNull(tasksById::get)
        val priorityCount = reminderTasks.count { task ->
            val attention = attentionById[task.taskId]
            attention?.overdue == true ||
                attention?.dueSoon == true ||
                task.priority == Priority.URGENT ||
                task.priority == Priority.VERY_URGENT
        }
        val posted = post(
            context,
            reminder.period == com.dailyplan.shared.model.ReminderPeriodWire.MORNING,
            reminderTasks,
            priorityCount,
            reminder.workDate,
            period,
        )
        if (posted) {
            container.repository.markReminderShown(reminder.workDate, period)
        }
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun post(
        context: Context,
        morning: Boolean,
        tasks: List<Task>,
        priorityCount: Int,
        workDate: String,
        period: String,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val openIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_MODE, MainActivity.MODE_LIST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val acknowledgeIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID + 1,
            Intent(context, ReminderAckReceiver::class.java)
                .setAction(ReminderAckReceiver.ACTION_ACKNOWLEDGE)
                .putExtra(ReminderAckReceiver.EXTRA_WORK_DATE, workDate)
                .putExtra(ReminderAckReceiver.EXTRA_PERIOD, period),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (morning) "上班前看一眼今天的事" else "下班前收个尾"
        val text = if (priorityCount > 0) {
            "${tasks.size} 件未完成，其中 $priorityCount 件需要优先处理"
        } else {
            "${tasks.size} 件未完成"
        }
        val style = NotificationCompat.InboxStyle()
        tasks.take(5).forEach { style.addLine(it.title) }
        if (tasks.size > 5) style.addLine("还有 ${tasks.size - 5} 件")
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_view,
                    "已查看",
                    acknowledgeIntent,
                ).build(),
            )
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
        return true
    }
}
