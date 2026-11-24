package com.dailyplan.android.data

import android.content.Context
import com.dailyplan.shared.data.LocalAttachmentStorage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidAttachmentStorage(context: Context) : LocalAttachmentStorage {
    private val directory = File(context.filesDir, "attachments").apply { mkdirs() }

    override suspend fun read(path: String): ByteArray = withContext(Dispatchers.IO) {
        File(path).readBytes()
    }

    override suspend fun write(
        attachmentId: String,
        mediaType: String,
        bytes: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        val extension = when (mediaType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "audio/wav" -> "wav"
            else -> "bin"
        }
        val target = File(directory, "$attachmentId.$extension")
        val temp = File(directory, "$attachmentId.$extension.tmp")
        temp.writeBytes(bytes)
        check(temp.renameTo(target)) { "无法保存附件" }
        target.absolutePath
    }
}
