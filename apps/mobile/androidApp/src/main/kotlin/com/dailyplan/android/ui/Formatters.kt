package com.dailyplan.android.ui

import com.dailyplan.shared.model.Priority
import com.dailyplan.shared.model.Task
import com.dailyplan.shared.model.WorkSettings
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

fun localNow(settings: WorkSettings): LocalDateTime = runCatching {
    Clock.System.now().toLocalDateTime(TimeZone.of(settings.timeZone))
}.getOrElse {
    Clock.System.now().toLocalDateTime(TimeZone.UTC)
}

fun localDate(instant: Instant, settings: WorkSettings): LocalDate = runCatching {
    instant.toLocalDateTime(TimeZone.of(settings.timeZone)).date
}.getOrElse {
    instant.toLocalDateTime(TimeZone.UTC).date
}

fun dueLabel(task: Task, today: LocalDate): String {
    val due = runCatching { LocalDate.parse(task.dueDate) }.getOrNull()
        ?: return task.dueDate
    val day = when (due) {
        today -> "今天"
        today.minus(DatePeriod(days = 1)) -> "昨天"
        else -> "${due.monthNumber} 月 ${due.dayOfMonth} 日"
    }
    return task.dueTime?.let { "$day $it" } ?: day
}

fun timeLabel(instant: Instant, settings: WorkSettings): String {
    val local = runCatching { instant.toLocalDateTime(TimeZone.of(settings.timeZone)) }.getOrNull()
        ?: return ""
    return "${local.hour.toString().padStart(2, '0')}:" +
        local.minute.toString().padStart(2, '0')
}

fun priorityLabel(priority: Priority): String = when (priority) {
    Priority.NORMAL -> "普通"
    Priority.URGENT -> "紧急"
    Priority.VERY_URGENT -> "非常紧急"
}

fun transcriptionLabel(status: com.dailyplan.shared.model.TranscriptionState?): String? =
    when (status) {
        com.dailyplan.shared.model.TranscriptionState.PENDING -> "等待转写"
        com.dailyplan.shared.model.TranscriptionState.PROCESSING -> "正在转写"
        com.dailyplan.shared.model.TranscriptionState.COMPLETE -> "转写完成"
        com.dailyplan.shared.model.TranscriptionState.FAILED -> "转写失败"
        null -> null
    }
