package com.dailyplan.shared.rules

import com.dailyplan.shared.model.AcknowledgedReminder
import com.dailyplan.shared.model.ReminderPeriodWire
import com.dailyplan.shared.model.Task
import com.dailyplan.shared.model.WorkSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.time.Instant

class SharedRuleCasesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun sharedCasesMatch() {
        val path = listOf(
            "../../../shared/protocol/rules/cases.json".toPath(),
            "../../shared/protocol/rules/cases.json".toPath(),
            "shared/protocol/rules/cases.json".toPath(),
        ).firstOrNull { FileSystem.SYSTEM.exists(it) }
            ?: error("Cannot locate shared/protocol/rules/cases.json")
        val fixture = json.decodeFromString<CasesFile>(
            FileSystem.SYSTEM.read(path) { readUtf8() },
        )
        for (case in fixture.cases) {
            val result = evaluate(
                now = Instant.parse(case.now),
                tasks = case.tasks,
                settings = case.settings,
                acknowledged = case.acknowledged.map {
                    AcknowledgedReminder(it.workDate, it.period)
                },
            )
            assertEquals(
                case.expected.visibleTaskIds,
                result.visible.map(Task::taskId),
                case.name,
            )
            assertEquals(
                case.expected.attention,
                result.attention.map {
                    AttentionDto(it.taskId, it.overdue, it.dueSoon)
                },
                case.name,
            )
            assertEquals(
                case.expected.reminder,
                result.reminder?.let {
                    ReminderDto(it.workDate, it.period, it.taskIds)
                },
                case.name,
            )
        }
    }
}

@Serializable
private data class CasesFile(val cases: List<RuleCase>)

@Serializable
private data class RuleCase(
    val name: String,
    val now: String,
    val settings: WorkSettings,
    val acknowledged: List<AcknowledgedDto> = emptyList(),
    val tasks: List<Task>,
    val expected: Expected,
)

@Serializable
private data class Expected(
    @SerialName("visibleTaskIds")
    val visibleTaskIds: List<String>,
    val attention: List<AttentionDto>,
    val reminder: ReminderDto?,
)

@Serializable
private data class AcknowledgedDto(
    val workDate: String,
    val period: ReminderPeriodWire,
)

@Serializable
private data class AttentionDto(
    val taskId: String,
    val overdue: Boolean,
    val dueSoon: Boolean,
)

@Serializable
private data class ReminderDto(
    val workDate: String,
    val period: ReminderPeriodWire,
    val taskIds: List<String>,
)
