package com.dailyplan.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

const val PROTOCOL_VERSION = 1

@Serializable
enum class Priority {
    @SerialName("normal")
    NORMAL,

    @SerialName("urgent")
    URGENT,

    @SerialName("very_urgent")
    VERY_URGENT,
}

@Serializable
enum class TitleSource {
    @SerialName("manual")
    MANUAL,

    @SerialName("voice_placeholder")
    VOICE_PLACEHOLDER,

    @SerialName("screenshot_placeholder")
    SCREENSHOT_PLACEHOLDER,
}

@Serializable
enum class AttachmentKind {
    @SerialName("screenshot")
    SCREENSHOT,

    @SerialName("recording")
    RECORDING,
}

@Serializable
enum class TranscriptionState {
    @SerialName("pending")
    PENDING,

    @SerialName("processing")
    PROCESSING,

    @SerialName("complete")
    COMPLETE,

    @SerialName("failed")
    FAILED,
}

@Serializable
data class Task(
    val taskId: String,
    val title: String,
    val titleSource: TitleSource = TitleSource.MANUAL,
    val createdOn: String,
    val dueDate: String,
    val dueTime: String? = null,
    val priority: Priority = Priority.NORMAL,
    val completed: Boolean = false,
    val completedAt: String? = null,
    val updatedAt: String = "",
)

@Serializable
data class WorkSettings(
    val workdays: List<Int> = listOf(1, 2, 3, 4, 5),
    val timeZone: String = "Asia/Shanghai",
    val workStart: String = "09:00",
    val workEnd: String = "18:00",
    val reminderLeadMinutes: Int = 15,
)

@Serializable
data class Attachment(
    val attachmentId: String,
    val taskId: String,
    val kind: AttachmentKind,
    val mediaType: String,
    val byteSize: Long,
    val sha256: String,
    val createdAt: String,
    val transcriptionStatus: TranscriptionState? = null,
    val transcript: String? = null,
    val transcriptionError: String? = null,
)

@Serializable
data class Status(
    val protocolVersion: Int,
    val serverTime: String,
    val datasetId: String,
    val restoreVersion: Long,
    val cursor: Long,
    val transcriptionEnabled: Boolean = false,
)

@Serializable
data class OperationReceipt(
    val opId: String,
    val cursor: Long,
    val task: Task? = null,
    val settings: WorkSettings? = null,
)

@Serializable
data class Change(
    val seq: Long,
    val type: String,
    val task: Task? = null,
    val settings: WorkSettings? = null,
    val attachment: Attachment? = null,
)

@Serializable
data class ChangesPage(
    val changes: List<Change> = emptyList(),
    val nextCursor: Long,
    val hasMore: Boolean = false,
)

@Serializable
data class Snapshot(
    val cursor: Long,
    val restoreVersion: Long,
    val tasks: List<Task> = emptyList(),
    val settings: WorkSettings,
    val attachments: List<Attachment> = emptyList(),
)

@Serializable
data class Operation(
    val opId: String,
    val deviceId: String,
    val type: String,
    val occurredAt: String? = null,
    val task: Task? = null,
    val taskId: String? = null,
    val set: JsonObject? = null,
    val fallback: Task? = null,
    val settings: WorkSettings? = null,
)

data class LocalAttachment(
    val attachment: Attachment,
    val localPath: String?,
    val remoteReady: Boolean,
)

data class SyncMeta(
    val cursor: Long,
    val datasetId: String?,
    val restoreVersion: Long?,
    val lastSuccessAt: String?,
    val lastError: String?,
)

data class AppSnapshot(
    val tasks: List<Task>,
    val completed: List<Task>,
    val settings: WorkSettings,
    val acknowledged: List<AcknowledgedReminder>,
    val pendingCount: Int,
    val serverUrl: String,
    val sync: SyncMeta,
)

data class AcknowledgedReminder(
    val workDate: String,
    val period: ReminderPeriodWire,
)

@Serializable
enum class ReminderPeriodWire {
    @SerialName("morning")
    MORNING,

    @SerialName("evening")
    EVENING,
}
