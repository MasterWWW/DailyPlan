package com.dailyplan.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.components.FilledButton
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.dailyplan.android.DailyPlanApplication
import com.dailyplan.android.MainActivity
import com.dailyplan.android.ui.dueLabel
import com.dailyplan.shared.model.Task
import com.dailyplan.shared.rules.evaluate
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

class DailyPlanWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as DailyPlanApplication
        val snapshot = app.container.repository.snapshot()
        val evaluation = runCatching {
            evaluate(
                Clock.System.now(),
                snapshot.tasks + snapshot.completed,
                snapshot.settings,
                snapshot.acknowledged,
            )
        }.getOrNull()
        val tasks = evaluation?.visible.orEmpty()
        val attention = evaluation?.attention.orEmpty().associateBy { it.taskId }
        val timezone = runCatching { TimeZone.of(snapshot.settings.timeZone) }
            .getOrDefault(TimeZone.currentSystemDefault())
        val today = Clock.System.now().toLocalDateTime(timezone).date

        provideContent {
            WidgetContent(tasks, attention, today)
        }
    }

    companion object {
        val taskIdKey = ActionParameters.Key<String>("taskId")
        val modeKey = ActionParameters.Key<String>("mode")

        suspend fun updateAll(context: Context) {
            DailyPlanWidget().updateAll(context)
        }
    }
}

@Composable
private fun WidgetContent(
    tasks: List<Task>,
    attention: Map<String, com.dailyplan.shared.rules.TaskAttention>,
    today: LocalDate,
) {
    val availableHeight = LocalSize.current.height
    val visibleLimit = when {
        availableHeight < 230.dp -> 1
        availableHeight < 280.dp -> 2
        availableHeight < 330.dp -> 3
        else -> 4
    }
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(0xFFF7F5F0.toInt()))
            .padding(12.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "今天",
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp),
            )
            Spacer(GlanceModifier.width(8.dp))
            Text(
                text = if (tasks.isEmpty()) "已经清空" else "${tasks.size} 件",
                style = TextStyle(
                    fontSize = 13.sp,
                    color = ColorProvider(0xFF6D6A63.toInt()),
                ),
            )
        }
        if (tasks.isEmpty()) {
            Spacer(GlanceModifier.height(8.dp))
            Text(
                text = "没有未完成事项",
                style = TextStyle(
                    fontSize = 15.sp,
                    color = ColorProvider(0xFF6D6A63.toInt()),
                ),
            )
        } else {
            tasks.take(visibleLimit).forEach { task ->
                WidgetTaskRow(task, attention[task.taskId], today)
            }
        }
        Spacer(GlanceModifier.fillMaxSize())
        Row(
            modifier = GlanceModifier.fillMaxWidth().height(48.dp),
            horizontalAlignment = Alignment.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledButton(
                text = "打字",
                onClick = actionStartActivity<MainActivity>(
                    actionParametersOf(DailyPlanWidget.modeKey to MainActivity.MODE_TEXT),
                ),
                modifier = GlanceModifier.size(76.dp, 48.dp),
            )
            Spacer(GlanceModifier.width(8.dp))
            FilledButton(
                text = "语音",
                onClick = actionStartActivity<MainActivity>(
                    actionParametersOf(DailyPlanWidget.modeKey to MainActivity.MODE_RECORD),
                ),
                modifier = GlanceModifier.size(76.dp, 48.dp),
            )
        }
    }
}

@Composable
private fun WidgetTaskRow(
    task: Task,
    attention: com.dailyplan.shared.rules.TaskAttention?,
    today: LocalDate,
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledButton(
            text = "○",
            onClick = actionRunCallback<CompleteTaskAction>(
                actionParametersOf(DailyPlanWidget.taskIdKey to task.taskId),
            ),
            modifier = GlanceModifier.size(48.dp),
        )
        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .height(48.dp)
                .padding(horizontal = 6.dp)
                .clickable(
                    actionStartActivity<MainActivity>(
                        actionParametersOf(
                            DailyPlanWidget.modeKey to MainActivity.MODE_DETAIL,
                            DailyPlanWidget.taskIdKey to task.taskId,
                        ),
                    ),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = task.title,
                maxLines = 1,
                style = TextStyle(
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = ColorProvider(0xFF1E1C18.toInt()),
                ),
            )
            val marker = when {
                attention?.overdue == true -> "已逾期"
                task.priority == com.dailyplan.shared.model.Priority.VERY_URGENT -> "非常紧急"
                task.priority == com.dailyplan.shared.model.Priority.URGENT -> "紧急"
                attention?.dueSoon == true -> "临期"
                else -> null
            }
            Text(
                text = listOfNotNull(dueLabel(task, today), marker).joinToString(" · "),
                maxLines = 1,
                style = TextStyle(
                    fontSize = 11.sp,
                    color = if (marker == null) {
                        ColorProvider(0xFF6D6A63.toInt())
                    } else {
                        ColorProvider(0xFFB0442D.toInt())
                    },
                ),
            )
        }
    }
}
