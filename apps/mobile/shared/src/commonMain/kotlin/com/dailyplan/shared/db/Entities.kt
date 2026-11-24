package com.dailyplan.shared.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dailyplan.shared.model.AttachmentKind
import com.dailyplan.shared.model.Priority
import com.dailyplan.shared.model.TitleSource
import com.dailyplan.shared.model.TranscriptionState

@Entity(
    tableName = "tasks",
    indices = [Index(value = ["completed", "dueDate", "dueTime"])],
)
data class TaskEntity(
    @PrimaryKey val taskId: String,
    val title: String,
    val titleSource: TitleSource,
    val createdOn: String,
    val dueDate: String,
    val dueTime: String?,
    val priority: Priority,
    val completed: Boolean,
    val completedAt: String?,
    val updatedAt: String,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId")],
)
data class AttachmentEntity(
    @PrimaryKey val attachmentId: String,
    val taskId: String,
    val kind: AttachmentKind,
    val mediaType: String,
    val byteSize: Long,
    val sha256: String,
    val localPath: String?,
    val remoteReady: Boolean,
    val transcriptionStatus: TranscriptionState?,
    val transcript: String?,
    val transcriptionError: String?,
    val createdAt: String,
)

@Entity(
    tableName = "pending_operations",
    indices = [
        Index(value = ["opId"], unique = true),
        Index(value = ["targetId"]),
    ],
)
data class PendingOperationEntity(
    @PrimaryKey(autoGenerate = true) val localOrder: Long = 0,
    val opId: String,
    val targetId: String,
    val dirtyFieldsJson: String,
    val payloadJson: String,
    val createdAt: String,
)

@Entity(tableName = "work_settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val workdaysCsv: String,
    val timeZone: String,
    val workStart: String,
    val workEnd: String,
    val reminderLeadMinutes: Int,
    val serverUrl: String,
    val deviceId: String,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    val cursor: Long = 0,
    val datasetId: String? = null,
    val restoreVersion: Long? = null,
    val lastSuccessAt: String? = null,
    val lastError: String? = null,
)

@Entity(
    tableName = "reminder_events",
    primaryKeys = ["workDate", "period"],
)
data class ReminderEventEntity(
    val workDate: String,
    val period: String,
    val shownAt: String,
    val acknowledgedAt: String?,
)
