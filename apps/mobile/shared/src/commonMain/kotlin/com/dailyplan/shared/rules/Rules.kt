package com.dailyplan.shared.rules

import com.dailyplan.shared.model.Priority
import com.dailyplan.shared.model.ReminderPeriodWire
import com.dailyplan.shared.model.Task
import com.dailyplan.shared.model.WorkSettings
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

data class TaskAttention(
    val taskId: String,
    val overdue: Boolean,
    val dueSoon: Boolean,
)

data class Reminder(
    val workDate: String,
    val period: ReminderPeriodWire,
    val taskIds: List<String>,
)

data class Evaluation(
    val visible: List<Task>,
    val attention: List<TaskAttention>,
    val reminder: Reminder?,
)

class RuleException(message: String) : IllegalArgumentException(message)

fun evaluate(
    now: Instant,
    tasks: List<Task>,
    settings: WorkSettings,
    acknowledged: List<com.dailyplan.shared.model.AcknowledgedReminder> = emptyList(),
): Evaluation {
    validateSettings(settings)
    val timezone = try {
        TimeZone.of(settings.timeZone)
    } catch (error: IllegalArgumentException) {
        throw RuleException("unknown time zone ${settings.timeZone}")
    }
    val nowLocal = now.toLocalDateTime(timezone)
    val nowInstant = nowLocal.toInstant(timezone)
    val ranked = tasks.asSequence()
        .filterNot { it.completed }
        .map { task ->
            val schedule = taskSchedule(task, settings, timezone)
            val attention = classify(task, schedule, nowInstant, nowLocal.date)
            val rank = when {
                attention.overdue -> 0
                task.priority == Priority.URGENT || task.priority == Priority.VERY_URGENT -> 1
                attention.dueSoon -> 2
                else -> 3
            }
            RankedTask(rank, schedule.dueAt, task, attention)
        }
        .sortedWith(
            compareBy<RankedTask> { it.rank }
                .thenBy { it.due }
                .thenBy { it.task.taskId },
        )
        .toList()

    val visible = ranked.map { it.task }
    val attention = ranked.map { it.attention }
    val reminder = activeReminder(
        nowLocal = nowLocal,
        settings = settings,
        morningTaskIds = ranked.filter { it.rank < 3 }.map { it.task.taskId },
        allTaskIds = visible.map { it.taskId },
        acknowledged = acknowledged,
    )
    return Evaluation(visible, attention, reminder)
}

private data class RankedTask(
    val rank: Int,
    val due: Instant,
    val task: Task,
    val attention: TaskAttention,
)

private data class TaskSchedule(
    val dueAt: Instant,
    val dueSoonFrom: LocalDate,
)

private data class WorkdayWindow(
    val start: LocalTime,
    val end: LocalTime,
) {
    fun evening(reminderLeadMinutes: Int): LocalTime {
        val seconds = (secondsOfDay(end) - reminderLeadMinutes * 60).coerceAtLeast(0)
        return LocalTime(
            second = seconds % 60,
            minute = (seconds / 60) % 60,
            hour = seconds / 3600,
        )
    }
}

private fun validateSettings(settings: WorkSettings) {
    if (settings.workdays.isEmpty() ||
        settings.workdays.any { it !in 1..7 } ||
        settings.reminderLeadMinutes < 0
    ) {
        throw RuleException("invalid work settings")
    }
    workdayWindow(settings)
}

private fun classify(
    task: Task,
    schedule: TaskSchedule,
    now: Instant,
    today: LocalDate,
): TaskAttention {
    val overdue = now > schedule.dueAt
    val dueSoon = !overdue && today >= schedule.dueSoonFrom
    return TaskAttention(task.taskId, overdue, dueSoon)
}

private fun taskSchedule(
    task: Task,
    settings: WorkSettings,
    timezone: TimeZone,
): TaskSchedule {
    val dueDate = parseDate(task.dueDate)
    val time = task.dueTime?.let(::parseClock) ?: LocalTime(23, 59, 59, 999_999_999)
    return TaskSchedule(
        dueAt = LocalDateTime(dueDate, time).toInstant(timezone),
        dueSoonFrom = previousWorkday(dueDate, settings.workdays),
    )
}

private fun activeReminder(
    nowLocal: LocalDateTime,
    settings: WorkSettings,
    morningTaskIds: List<String>,
    allTaskIds: List<String>,
    acknowledged: List<com.dailyplan.shared.model.AcknowledgedReminder>,
): Reminder? {
    if (allTaskIds.isEmpty() || nowLocal.dayOfWeek.isoDayNumber !in settings.workdays) {
        return null
    }
    val window = workdayWindow(settings)
    val period = when {
        nowLocal.time >= window.evening(settings.reminderLeadMinutes) -> ReminderPeriodWire.EVENING
        nowLocal.time >= window.start -> ReminderPeriodWire.MORNING
        else -> return null
    }
    val taskIds = if (period == ReminderPeriodWire.EVENING) allTaskIds else morningTaskIds
    if (taskIds.isEmpty()) {
        return null
    }
    val workDate = nowLocal.date.toString()
    if (acknowledged.any { it.workDate == workDate && it.period == period }) {
        return null
    }
    return Reminder(workDate, period, taskIds)
}

private fun previousWorkday(date: LocalDate, workdays: List<Int>): LocalDate {
    var candidate = date.minus(DatePeriod(days = 1))
    repeat(14) {
        if (candidate.dayOfWeek.isoDayNumber in workdays) {
            return candidate
        }
        candidate = candidate.minus(DatePeriod(days = 1))
    }
    throw RuleException("invalid work settings")
}

private fun parseDate(value: String): LocalDate = try {
    LocalDate.parse(value)
} catch (error: IllegalArgumentException) {
    throw RuleException("invalid date $value")
}

private fun parseClock(value: String): LocalTime = try {
    LocalTime.parse(value)
} catch (error: IllegalArgumentException) {
    throw RuleException("invalid time $value")
}

private fun workdayWindow(settings: WorkSettings): WorkdayWindow {
    val start = parseClock(settings.workStart)
    val end = parseClock(settings.workEnd)
    if (start >= end) {
        throw RuleException("invalid work settings")
    }
    return WorkdayWindow(start, end)
}

fun nextReminderInstant(
    now: Instant,
    settings: WorkSettings,
    maxDays: Int = 14,
): Instant? {
    val timezone = try {
        TimeZone.of(settings.timeZone)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val window = runCatching { workdayWindow(settings) }.getOrNull() ?: return null
    val evening = window.evening(settings.reminderLeadMinutes)
    val today = now.toLocalDateTime(timezone).date
    var best: Instant? = null
    for (days in 0..maxDays) {
        val date = today.plusDate(days)
        if (date.dayOfWeek.isoDayNumber !in settings.workdays) continue
        for (time in listOf(window.start, evening)) {
            val candidate = date.atTime(time).toInstant(timezone)
            if (candidate > now && (best == null || candidate < best)) {
                best = candidate
            }
        }
    }
    return best
}

private fun LocalDate.plusDate(days: Int): LocalDate = this.plus(DatePeriod(days = days))

private fun secondsOfDay(time: LocalTime): Int =
    time.hour * 3600 + time.minute * 60 + time.second
