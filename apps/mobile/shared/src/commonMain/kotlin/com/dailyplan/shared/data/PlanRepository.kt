package com.dailyplan.shared.data

import androidx.room.withTransaction
import com.dailyplan.shared.db.AttachmentEntity
import com.dailyplan.shared.db.DailyPlanDatabase
import com.dailyplan.shared.db.PendingOperationEntity
import com.dailyplan.shared.db.SettingsEntity
import com.dailyplan.shared.db.SyncStateEntity
import com.dailyplan.shared.db.TaskEntity
import com.dailyplan.shared.model.AcknowledgedReminder
import com.dailyplan.shared.model.AppSnapshot
import com.dailyplan.shared.model.Attachment
import com.dailyplan.shared.model.AttachmentKind
import com.dailyplan.shared.model.Change
import com.dailyplan.shared.model.LocalAttachment
import com.dailyplan.shared.model.Operation
import com.dailyplan.shared.model.Priority
import com.dailyplan.shared.model.ReminderPeriodWire
import com.dailyplan.shared.model.Snapshot
import com.dailyplan.shared.model.Status
import com.dailyplan.shared.model.SyncMeta
import com.dailyplan.shared.model.Task
import com.dailyplan.shared.model.TitleSource
import com.dailyplan.shared.model.TranscriptionState
import com.dailyplan.shared.model.WorkSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class NewAttachment(
    val attachmentId: String,
    val kind: AttachmentKind,
    val mediaType: String,
    val byteSize: Long,
    val sha256: String,
    val localPath: String,
    val createdAt: String,
)

data class PendingOperation(
    val localOrder: Long,
    val opId: String,
    val targetId: String,
    val dirtyFields: Set<String>,
    val payload: Operation,
    val payloadJson: String,
)

class PlanRepository(
    private val database: DailyPlanDatabase,
    private val clock: Clock = Clock.System,
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    },
) {
    private val dao = database.dao()

    suspend fun initialize() {
        val existing = dao.settings()
        if (existing == null) {
            dao.saveSettings(
                SettingsEntity(
                    workdaysCsv = "1,2,3,4,5",
                    timeZone = "Asia/Shanghai",
                    workStart = "09:00",
                    workEnd = "18:00",
                    reminderLeadMinutes = 15,
                    serverUrl = "",
                    deviceId = newId(),
                ),
            )
        }
        if (dao.syncState() == null) {
            dao.saveSyncState(SyncStateEntity())
        }
    }

    fun observeTasks(): Flow<List<Task>> = dao.observeTasks().map { rows ->
        rows.map(TaskEntity::toModel)
    }

    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    suspend fun settings(): WorkSettings = dao.settings()?.toWorkSettings()
        ?: WorkSettings()

    suspend fun settingsEntity(): SettingsEntity {
        initialize()
        return checkNotNull(dao.settings())
    }

    suspend fun snapshot(): AppSnapshot {
        val settings = settingsEntity()
        val tasks = dao.allTasks().map(TaskEntity::toModel)
        val acknowledged = dao.reminderEvents()
            .mapNotNull { event ->
                if (event.acknowledgedAt == null) {
                    null
                } else {
                    val period = when (event.period) {
                        "morning" -> ReminderPeriodWire.MORNING
                        "evening" -> ReminderPeriodWire.EVENING
                        else -> null
                    }
                    period?.let { AcknowledgedReminder(event.workDate, it) }
                }
            }
        return AppSnapshot(
            tasks = tasks.filterNot(Task::completed),
            completed = tasks.filter(Task::completed).sortedByDescending { it.completedAt },
            settings = settings.toWorkSettings(),
            acknowledged = acknowledged,
            pendingCount = dao.pendingCount(),
            serverUrl = settings.serverUrl,
            sync = dao.syncState().orEmpty().toSyncMeta(),
        )
    }

    suspend fun attachmentsFor(taskId: String): List<LocalAttachment> =
        dao.attachmentsFor(taskId).map(AttachmentEntity::toLocal)

    fun observeAttachmentsFor(taskId: String): Flow<List<LocalAttachment>> =
        dao.observeAttachmentsFor(taskId).map { rows -> rows.map(AttachmentEntity::toLocal) }

    suspend fun task(taskId: String): Task? = dao.task(taskId)?.toModel()

    suspend fun attachment(attachmentId: String): LocalAttachment? =
        dao.attachment(attachmentId)?.toLocal()

    suspend fun createTask(
        title: String,
        dueDate: String? = null,
        dueTime: String? = null,
        priority: Priority = Priority.NORMAL,
        titleSource: TitleSource = TitleSource.MANUAL,
        attachment: NewAttachment? = null,
    ): Task {
        val settings = settingsEntity()
        val now = clock.now()
        val timezone = TimeZone.of(settings.timeZone)
        val createdOn = now.toLocalDateTime(timezone).date.toString()
        val normalTitle = title.trim()
        require(normalTitle.isNotEmpty() && normalTitle.length <= 500) {
            "标题需要 1 到 500 个字符"
        }
        val effectiveDueDate = dueDate ?: createdOn
        LocalDate.parse(effectiveDueDate)
        dueTime?.let(LocalTime::parse)
        val task = Task(
            taskId = newId(),
            title = normalTitle,
            titleSource = titleSource,
            createdOn = createdOn,
            dueDate = effectiveDueDate,
            dueTime = dueTime,
            priority = priority,
            completed = false,
            completedAt = null,
            updatedAt = now.toString(),
        )
        val operation = Operation(
            opId = newId(),
            deviceId = settings.deviceId,
            type = "task.create",
            occurredAt = now.toString(),
            task = task,
        )
        val pending = pendingEntity(operation, task.taskId, listOf("task"), now)
        if (attachment == null) {
            dao.createTask(task.toEntity(), pending)
        } else {
            val entity = AttachmentEntity(
                attachmentId = attachment.attachmentId,
                taskId = task.taskId,
                kind = attachment.kind,
                mediaType = attachment.mediaType,
                byteSize = attachment.byteSize,
                sha256 = attachment.sha256,
                localPath = attachment.localPath,
                remoteReady = false,
                transcriptionStatus = if (attachment.kind == AttachmentKind.RECORDING) {
                    TranscriptionState.PENDING
                } else {
                    null
                },
                transcript = null,
                transcriptionError = null,
                createdAt = attachment.createdAt,
            )
            dao.createTaskWithAttachment(task.toEntity(), pending, entity)
        }
        return task
    }

    suspend fun updateTask(
        taskId: String,
        title: String? = null,
        dueDate: String? = null,
        dueTime: String? = null,
        dueTimeSet: Boolean = false,
        priority: Priority? = null,
        completed: Boolean? = null,
    ): Task {
        val settings = settingsEntity()
        val now = clock.now()
        val current = dao.task(taskId) ?: error("待办不存在")
        val set = buildJsonObject {
            title?.let {
                put("title", it.trim())
                put("titleSource", TitleSource.MANUAL.name.lowercase())
            }
            dueDate?.let {
                LocalDate.parse(it)
                put("dueDate", it)
            }
            if (dueTimeSet) {
                put("dueTime", dueTime?.let(::JsonPrimitive) ?: JsonNull)
            }
            priority?.let {
                put("priority", it.toWire())
            }
            completed?.let {
                put("completed", it)
            }
        }
        require(set.isNotEmpty()) { "没有需要保存的修改" }
        val dirty = set.keys.toList()
        val merged = current.applySet(set, now.toString())
        val operation = Operation(
            opId = newId(),
            deviceId = settings.deviceId,
            type = "task.update",
            occurredAt = now.toString(),
            taskId = taskId,
            set = set,
            fallback = merged.toModel(),
        )
        dao.updateTask(
            merged,
            pendingEntity(operation, taskId, dirty, now),
        )
        return merged.toModel()
    }

    suspend fun addAttachment(taskId: String, attachment: NewAttachment) {
        val current = dao.task(taskId) ?: error("待办不存在")
        require(attachment.byteSize > 0) { "附件不能为空" }
        dao.insertAttachment(
            AttachmentEntity(
                attachmentId = attachment.attachmentId,
                taskId = taskId,
                kind = attachment.kind,
                mediaType = attachment.mediaType,
                byteSize = attachment.byteSize,
                sha256 = attachment.sha256,
                localPath = attachment.localPath,
                remoteReady = false,
                transcriptionStatus = if (attachment.kind == AttachmentKind.RECORDING) {
                    TranscriptionState.PENDING
                } else {
                    null
                },
                transcript = null,
                transcriptionError = null,
                createdAt = attachment.createdAt,
            ),
        )
        dao.upsertTask(current.copy(updatedAt = clock.now().toString()))
    }

    suspend fun saveSettings(work: WorkSettings, serverUrl: String) {
        validateWorkSettings(work)
        val current = settingsEntity()
        val now = clock.now()
        val normalizedUrl = serverUrl.trim().trimEnd('/')
        require(normalizedUrl.isEmpty() || normalizedUrl.startsWith("http://") || normalizedUrl.startsWith("https://")) {
            "服务器地址需要以 http:// 或 https:// 开头"
        }
        val next = current.copy(
            workdaysCsv = work.workdays.sorted().joinToString(","),
            timeZone = work.timeZone,
            workStart = work.workStart,
            workEnd = work.workEnd,
            reminderLeadMinutes = work.reminderLeadMinutes,
            serverUrl = normalizedUrl,
        )
        val workChanged = current.toWorkSettings() != work
        val operation = if (workChanged) {
            val payload = Operation(
                opId = newId(),
                deviceId = current.deviceId,
                type = "settings.update",
                occurredAt = now.toString(),
                settings = work,
            )
            pendingEntity(payload, "settings", listOf("settings"), now)
        } else {
            null
        }
        dao.saveSettingsWithOperation(next, operation)
    }

    suspend fun pendingOperations(): List<PendingOperation> =
        dao.pendingOperations().mapNotNull { row ->
            runCatching {
                PendingOperation(
                    localOrder = row.localOrder,
                    opId = row.opId,
                    targetId = row.targetId,
                    dirtyFields = json.decodeFromString(
                        ListSerializer(String.serializer()),
                        row.dirtyFieldsJson,
                    ).toSet(),
                    payload = json.decodeFromString(Operation.serializer(), row.payloadJson),
                    payloadJson = row.payloadJson,
                )
            }.getOrNull()
        }

    suspend fun completeOperation(opId: String) = dao.deletePending(opId)

    suspend fun pendingUploadAttachments(): List<LocalAttachment> =
        dao.pendingUploadAttachments().map(AttachmentEntity::toLocal)

    suspend fun missingLocalAttachments(): List<LocalAttachment> =
        dao.missingLocalAttachments().map(AttachmentEntity::toLocal)

    suspend fun markAttachmentRemote(remote: Attachment) {
        val local = dao.attachment(remote.attachmentId)
        dao.insertAttachment(remote.toEntity(local?.localPath))
    }

    suspend fun setAttachmentLocalPath(attachmentId: String, path: String) {
        dao.updateAttachmentPath(attachmentId, path)
    }

    suspend fun syncMeta(): SyncMeta = dao.syncState().orEmpty().toSyncMeta()

    suspend fun markSyncSuccess(status: Status, cursor: Long) {
        dao.saveSyncState(
            SyncStateEntity(
                cursor = cursor,
                datasetId = status.datasetId,
                restoreVersion = status.restoreVersion,
                lastSuccessAt = clock.now().toString(),
                lastError = null,
            ),
        )
    }

    suspend fun markSyncError(message: String) {
        val current = dao.syncState().orEmpty()
        dao.saveSyncState(current.copy(lastError = message.take(500)))
    }

    suspend fun markReminderShown(workDate: String, period: String) {
        val current = dao.reminderEvents().firstOrNull {
            it.workDate == workDate && it.period == period
        }
        dao.saveReminderEvent(
            current?.copy(shownAt = current.shownAt.ifEmpty { clock.now().toString() })
                ?: com.dailyplan.shared.db.ReminderEventEntity(
                    workDate = workDate,
                    period = period,
                    shownAt = clock.now().toString(),
                    acknowledgedAt = null,
                ),
        )
    }

    suspend fun acknowledgeReminder(workDate: String, period: String) {
        val current = dao.reminderEvents().firstOrNull {
            it.workDate == workDate && it.period == period
        }
        dao.saveReminderEvent(
            com.dailyplan.shared.db.ReminderEventEntity(
                workDate = workDate,
                period = period,
                shownAt = current?.shownAt?.ifEmpty { clock.now().toString() }
                    ?: clock.now().toString(),
                acknowledgedAt = clock.now().toString(),
            ),
        )
    }

    suspend fun reminderWasShown(workDate: String, period: String): Boolean =
        dao.reminderWasShown(workDate, period)

    suspend fun applySnapshot(snapshot: Snapshot, datasetId: String) {
        database.withTransaction {
            val pending = dao.pendingOperations()
            val localTasks = dao.allTasks().associateBy { it.taskId }
            val localAttachments = dao.allAttachments()
            val localPaths = localAttachments.associate { it.attachmentId to it.localPath }
            val settings = checkNotNull(dao.settings())
            val decoded = pending.map { decodeOperation(it) }

            dao.deleteAllPending()
            dao.deleteAllAttachments()
            dao.deleteAllTasks()
            dao.upsertTasks(snapshot.tasks.map(Task::toEntity))
            dao.saveSettings(
                settings.copyWorkSettings(snapshot.settings),
            )
            snapshot.attachments.forEach { attachment ->
                dao.insertAttachment(attachment.toEntity(localPaths[attachment.attachmentId]))
            }
            for (task in localTasks.values) {
                val hasPendingCreate = decoded.any {
                    it?.type == "task.create" && it.task?.taskId == task.taskId
                }
                if (hasPendingCreate && dao.task(task.taskId) == null) {
                    dao.upsertTask(task)
                    localAttachments
                        .filter { it.taskId == task.taskId }
                        .forEach { dao.insertAttachment(it) }
                }
            }
            for ((row, operation) in pending.zip(decoded)) {
                if (operation != null) {
                    reapplyPending(operation, localTasks)
                }
                dao.insertPending(row.copy(localOrder = 0))
            }
            for (attachment in localAttachments) {
                if (attachment.localPath != null &&
                    dao.attachment(attachment.attachmentId) == null &&
                    dao.task(attachment.taskId) != null
                ) {
                    dao.insertAttachment(attachment)
                }
            }
            dao.saveSyncState(
                SyncStateEntity(
                    cursor = snapshot.cursor,
                    datasetId = datasetId,
                    restoreVersion = snapshot.restoreVersion,
                    lastSuccessAt = clock.now().toString(),
                    lastError = null,
                ),
            )
        }
    }

    suspend fun applyChanges(changes: List<Change>, nextCursor: Long) {
        database.withTransaction {
            val pending = dao.pendingOperations()
            val dirtyByTarget = pending.groupBy { it.targetId }
                .mapValues { (_, rows) ->
                    rows.flatMap { row ->
                        runCatching {
                            json.decodeFromString(
                                ListSerializer(String.serializer()),
                                row.dirtyFieldsJson,
                            )
                        }.getOrDefault(emptyList())
                    }.toSet()
                }
            val hasPendingSettings = dirtyByTarget.containsKey("settings")
            for (change in changes) {
                change.task?.let { remote ->
                    val local = dao.task(remote.taskId)?.toModel()
                    val merged = mergeTask(local, remote, dirtyByTarget[remote.taskId].orEmpty())
                    dao.upsertTask(merged.toEntity())
                }
                change.settings?.let { work ->
                    if (!hasPendingSettings) {
                        val current = checkNotNull(dao.settings())
                        dao.saveSettings(
                            current.copyWorkSettings(work),
                        )
                    }
                }
                change.attachment?.let { attachment ->
                    val localPath = dao.attachment(attachment.attachmentId)?.localPath
                    dao.insertAttachment(attachment.toEntity(localPath))
                }
            }
            val state = dao.syncState().orEmpty()
            dao.saveSyncState(state.copy(cursor = nextCursor))
        }
    }

    private suspend fun reapplyPending(
        operation: Operation,
        localTasks: Map<String, TaskEntity>,
    ) {
        when (operation.type) {
            "task.create" -> {
                val task = operation.task ?: return
                if (dao.task(task.taskId) == null) {
                    dao.upsertTask(task.toEntity())
                }
            }
            "task.update" -> {
                val taskId = operation.taskId ?: return
                val current = dao.task(taskId)
                    ?: operation.fallback?.toEntity()
                    ?: localTasks[taskId]
                    ?: return
                val set = operation.set ?: return
                dao.upsertTask(current.applySet(set, operation.occurredAt ?: clock.now().toString()))
            }
            "settings.update" -> {
                val work = operation.settings ?: return
                val settings = checkNotNull(dao.settings())
                dao.saveSettings(
                    settings.copyWorkSettings(work),
                )
            }
        }
    }

    private fun decodeOperation(row: PendingOperationEntity): Operation? =
        runCatching {
            json.decodeFromString(Operation.serializer(), row.payloadJson)
        }.getOrNull()

    private fun pendingEntity(
        operation: Operation,
        targetId: String,
        dirtyFields: List<String>,
        now: Instant,
    ): PendingOperationEntity = PendingOperationEntity(
        opId = operation.opId,
        targetId = targetId,
        dirtyFieldsJson = json.encodeToString(
            ListSerializer(String.serializer()),
            dirtyFields,
        ),
        payloadJson = json.encodeToString(Operation.serializer(), operation),
        createdAt = now.toString(),
    )

    private fun mergeTask(local: Task?, remote: Task, dirty: Set<String>): Task {
        if (local == null) return remote
        return remote.copy(
            title = if ("title" in dirty) local.title else remote.title,
            titleSource = if ("titleSource" in dirty) local.titleSource else remote.titleSource,
            dueDate = if ("dueDate" in dirty) local.dueDate else remote.dueDate,
            dueTime = if ("dueTime" in dirty) local.dueTime else remote.dueTime,
            priority = if ("priority" in dirty) local.priority else remote.priority,
            completed = if ("completed" in dirty) local.completed else remote.completed,
            completedAt = if ("completed" in dirty) local.completedAt else remote.completedAt,
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun newId(): String = Uuid.random().toString()
}

private fun TaskEntity.toModel(): Task = Task(
    taskId = taskId,
    title = title,
    titleSource = titleSource,
    createdOn = createdOn,
    dueDate = dueDate,
    dueTime = dueTime,
    priority = priority,
    completed = completed,
    completedAt = completedAt,
    updatedAt = updatedAt,
)

private fun Task.toEntity(): TaskEntity = TaskEntity(
    taskId = taskId,
    title = title,
    titleSource = titleSource,
    createdOn = createdOn,
    dueDate = dueDate,
    dueTime = dueTime,
    priority = priority,
    completed = completed,
    completedAt = completedAt,
    updatedAt = updatedAt,
)

private fun AttachmentEntity.toLocal(): LocalAttachment = LocalAttachment(
    attachment = Attachment(
        attachmentId = attachmentId,
        taskId = taskId,
        kind = kind,
        mediaType = mediaType,
        byteSize = byteSize,
        sha256 = sha256,
        createdAt = createdAt,
        transcriptionStatus = transcriptionStatus,
        transcript = transcript,
        transcriptionError = transcriptionError,
    ),
    localPath = localPath,
    remoteReady = remoteReady,
)

private fun Attachment.toEntity(localPath: String?): AttachmentEntity = AttachmentEntity(
    attachmentId = attachmentId,
    taskId = taskId,
    kind = kind,
    mediaType = mediaType,
    byteSize = byteSize,
    sha256 = sha256,
    localPath = localPath,
    remoteReady = true,
    transcriptionStatus = transcriptionStatus,
    transcript = transcript,
    transcriptionError = transcriptionError,
    createdAt = createdAt,
)

private fun SettingsEntity.toWorkSettings(): WorkSettings = WorkSettings(
    workdays = workdaysCsv.split(',')
        .mapNotNull(String::toIntOrNull)
        .ifEmpty { listOf(1, 2, 3, 4, 5) },
    timeZone = timeZone,
    workStart = workStart,
    workEnd = workEnd,
    reminderLeadMinutes = reminderLeadMinutes,
)

private fun SettingsEntity.copyWorkSettings(work: WorkSettings): SettingsEntity = copy(
    workdaysCsv = work.workdays.sorted().joinToString(","),
    timeZone = work.timeZone,
    workStart = work.workStart,
    workEnd = work.workEnd,
    reminderLeadMinutes = work.reminderLeadMinutes,
)

private fun SyncStateEntity?.toSyncMeta(): SyncMeta = if (this == null) {
    SyncMeta(0, null, null, null, null)
} else {
    SyncMeta(cursor, datasetId, restoreVersion, lastSuccessAt, lastError)
}

private fun SyncStateEntity?.orEmpty(): SyncStateEntity =
    this ?: SyncStateEntity()

private fun Priority.toWire(): String = when (this) {
    Priority.NORMAL -> "normal"
    Priority.URGENT -> "urgent"
    Priority.VERY_URGENT -> "very_urgent"
}

private fun priorityFromWire(value: String?): Priority = when (value) {
    "urgent" -> Priority.URGENT
    "very_urgent" -> Priority.VERY_URGENT
    else -> Priority.NORMAL
}

private fun applyTitleSource(value: String?): TitleSource = when (value) {
    "voice_placeholder" -> TitleSource.VOICE_PLACEHOLDER
    "screenshot_placeholder" -> TitleSource.SCREENSHOT_PLACEHOLDER
    else -> TitleSource.MANUAL
}

private fun TaskEntity.applySet(set: JsonObject, now: String): TaskEntity {
    val updatedTitle = set["title"]?.jsonPrimitive?.contentOrNull ?: title
    val updatedSource = set["titleSource"]?.jsonPrimitive?.contentOrNull
        ?.let(::applyTitleSource)
        ?: titleSource
    val updatedDueDate = set["dueDate"]?.jsonPrimitive?.contentOrNull ?: dueDate
    val updatedDueTime = when {
        "dueTime" !in set -> dueTime
        set["dueTime"] == JsonNull -> null
        else -> set["dueTime"]?.jsonPrimitive?.contentOrNull
    }
    val updatedPriority = if ("priority" in set) {
        priorityFromWire(set["priority"]?.jsonPrimitive?.contentOrNull)
    } else {
        priority
    }
    val updatedCompleted = set["completed"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
        ?: completed
    val updatedCompletedAt = when {
        !updatedCompleted -> null
        completed -> completedAt
        else -> now
    }
    return copy(
        title = updatedTitle,
        titleSource = updatedSource,
        dueDate = updatedDueDate,
        dueTime = updatedDueTime,
        priority = updatedPriority,
        completed = updatedCompleted,
        completedAt = updatedCompletedAt,
        updatedAt = now,
    )
}

private fun validateWorkSettings(settings: WorkSettings) {
    require(settings.workdays.isNotEmpty() && settings.workdays.all { it in 1..7 }) {
        "至少选择一个工作日"
    }
    require(settings.reminderLeadMinutes in 0..600) { "提前量需在 0 到 600 分钟之间" }
    TimeZone.of(settings.timeZone)
    val start = LocalTime.parse(settings.workStart)
    val end = LocalTime.parse(settings.workEnd)
    require(start < end) { "下班时间需要晚于上班时间" }
}
