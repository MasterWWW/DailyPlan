package com.dailyplan.shared.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class DailyPlanDao {
    @Query("SELECT * FROM tasks ORDER BY completed ASC, dueDate ASC, dueTime ASC, taskId ASC")
    abstract fun observeTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY completed ASC, dueDate ASC, dueTime ASC, taskId ASC")
    abstract suspend fun allTasks(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE taskId = :taskId")
    abstract suspend fun task(taskId: String): TaskEntity?

    @Upsert
    abstract suspend fun upsertTask(task: TaskEntity)

    @Upsert
    abstract suspend fun upsertTasks(tasks: List<TaskEntity>)

    @Query("DELETE FROM tasks")
    abstract suspend fun deleteAllTasks()

    @Query("DELETE FROM tasks WHERE taskId = :taskId")
    abstract suspend fun deleteTask(taskId: String)

    @Query("SELECT * FROM attachments WHERE taskId = :taskId ORDER BY createdAt ASC")
    abstract suspend fun attachmentsFor(taskId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE taskId = :taskId ORDER BY createdAt ASC")
    abstract fun observeAttachmentsFor(taskId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE attachmentId = :attachmentId")
    abstract suspend fun attachment(attachmentId: String): AttachmentEntity?

    @Query("SELECT * FROM attachments ORDER BY createdAt ASC")
    abstract suspend fun allAttachments(): List<AttachmentEntity>

    @Query(
        """
        SELECT * FROM attachments
        WHERE localPath IS NULL
        ORDER BY createdAt ASC
        """,
    )
    abstract suspend fun missingLocalAttachments(): List<AttachmentEntity>

    @Query(
        """
        SELECT * FROM attachments
        WHERE remoteReady = 0
        ORDER BY createdAt ASC
        """,
    )
    abstract suspend fun pendingUploadAttachments(): List<AttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAttachment(attachment: AttachmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAttachments(attachments: List<AttachmentEntity>)

    @Query("DELETE FROM attachments")
    abstract suspend fun deleteAllAttachments()

    @Query("UPDATE attachments SET localPath = :path WHERE attachmentId = :attachmentId")
    abstract suspend fun updateAttachmentPath(attachmentId: String, path: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertPending(operation: PendingOperationEntity)

    @Query("SELECT * FROM pending_operations ORDER BY localOrder ASC")
    abstract suspend fun pendingOperations(): List<PendingOperationEntity>

    @Query("DELETE FROM pending_operations WHERE opId = :opId")
    abstract suspend fun deletePending(opId: String)

    @Query("DELETE FROM pending_operations WHERE targetId = :targetId")
    abstract suspend fun deletePendingForTarget(targetId: String)

    @Query("DELETE FROM pending_operations")
    abstract suspend fun deleteAllPending()

    @Query(
        """
        SELECT (SELECT COUNT(*) FROM pending_operations) +
               (SELECT COUNT(*) FROM attachments WHERE remoteReady = 0)
        """,
    )
    abstract fun observePendingCount(): Flow<Int>

    @Query(
        """
        SELECT (SELECT COUNT(*) FROM pending_operations) +
               (SELECT COUNT(*) FROM attachments WHERE remoteReady = 0)
        """,
    )
    abstract suspend fun pendingCount(): Int

    @Query("SELECT * FROM work_settings WHERE id = 1")
    abstract suspend fun settings(): SettingsEntity?

    @Query("SELECT * FROM work_settings WHERE id = 1")
    abstract fun observeSettings(): Flow<SettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveSettings(settings: SettingsEntity)

    @Query("SELECT * FROM sync_state WHERE id = 1")
    abstract suspend fun syncState(): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE id = 1")
    abstract fun observeSyncState(): Flow<SyncStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveSyncState(state: SyncStateEntity)

    @Query("SELECT * FROM reminder_events")
    abstract suspend fun reminderEvents(): List<ReminderEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveReminderEvent(event: ReminderEventEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM reminder_events WHERE workDate = :workDate AND period = :period)")
    abstract suspend fun reminderWasShown(workDate: String, period: String): Boolean

    @Transaction
    open suspend fun createTask(task: TaskEntity, operation: PendingOperationEntity) {
        upsertTask(task)
        insertPending(operation)
    }

    @Transaction
    open suspend fun createTaskWithAttachment(
        task: TaskEntity,
        operation: PendingOperationEntity,
        attachment: AttachmentEntity,
    ) {
        upsertTask(task)
        insertAttachment(attachment)
        insertPending(operation)
    }

    @Transaction
    open suspend fun updateTask(task: TaskEntity, operation: PendingOperationEntity) {
        upsertTask(task)
        insertPending(operation)
    }

    @Transaction
    open suspend fun deleteTaskWithPending(taskId: String, operation: PendingOperationEntity) {
        deletePendingForTarget(taskId)
        insertPending(operation)
        deleteTask(taskId)
    }

    @Transaction
    open suspend fun saveSettingsWithOperation(
        settings: SettingsEntity,
        operation: PendingOperationEntity?,
    ) {
        saveSettings(settings)
        if (operation != null) {
            insertPending(operation)
        }
    }
}

@Database(
    entities = [
        TaskEntity::class,
        AttachmentEntity::class,
        PendingOperationEntity::class,
        SettingsEntity::class,
        SyncStateEntity::class,
        ReminderEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class DailyPlanDatabase : RoomDatabase() {
    abstract fun dao(): DailyPlanDao
}
