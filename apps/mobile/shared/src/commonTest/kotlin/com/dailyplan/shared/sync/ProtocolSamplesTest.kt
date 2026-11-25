package com.dailyplan.shared.sync

import com.dailyplan.shared.model.Attachment
import com.dailyplan.shared.model.AttachmentKind
import com.dailyplan.shared.model.ChangesPage
import com.dailyplan.shared.model.Operation
import com.dailyplan.shared.model.OperationReceipt
import com.dailyplan.shared.model.Snapshot
import com.dailyplan.shared.model.Status
import com.dailyplan.shared.model.TranscriptionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

class ProtocolSamplesTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun serverSamplesDecode() {
        val status = load<Status>("status.json")
        assertEquals(1, status.protocolVersion)
        assertEquals(12, status.cursor)
        assertTrue(status.transcriptionEnabled)

        val create = load<Operation>("operation-task-create.json")
        assertEquals("task.create", create.type)
        assertEquals(null, create.task?.dueTime)

        val update = load<Operation>("operation-task-update.json")
        assertEquals("task.update", update.type)
        assertEquals("15:00", update.set?.get("dueTime")?.toString()?.trim('"'))

        val delete = load<Operation>("operation-task-delete.json")
        assertEquals("task.delete", delete.type)
        assertEquals("aa24217b-93d7-4a3b-b9bf-f9c79e9fca3d", delete.taskId)

        val settings = load<Operation>("operation-settings-update.json")
        assertEquals("settings.update", settings.type)
        assertNotNull(settings.settings)

        val receipt = load<OperationReceipt>("operation-receipt.json")
        assertEquals(1, receipt.cursor)
        assertNotNull(receipt.task)

        val changes = load<ChangesPage>("changes-page.json")
        assertEquals(12, changes.nextCursor)
        assertEquals(1, changes.changes.size)
        assertTrue(changes.changes.single().task?.completed == true)

        val snapshot = load<Snapshot>("snapshot.json")
        assertEquals(12, snapshot.cursor)
        assertEquals(1, snapshot.tasks.size)
        assertTrue(snapshot.attachments.isEmpty())

        val attachment = load<Attachment>("attachment.json")
        assertEquals(AttachmentKind.RECORDING, attachment.kind)
        assertEquals(TranscriptionState.PENDING, attachment.transcriptionStatus)
    }

    private inline fun <reified T> load(name: String): T {
        val path = listOf(
            "../../../shared/protocol/samples/server/$name".toPath(),
            "../../shared/protocol/samples/server/$name".toPath(),
            "shared/protocol/samples/server/$name".toPath(),
        ).firstOrNull { FileSystem.SYSTEM.exists(it) }
            ?: error("Cannot locate shared/protocol/samples/server/$name")
        return json.decodeFromString(FileSystem.SYSTEM.read(path) { readUtf8() })
    }
}
