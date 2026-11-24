package com.dailyplan.android.media

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.dailyplan.shared.data.NewAttachment
import com.dailyplan.shared.model.AttachmentKind
import com.dailyplan.shared.sync.sha256Hex
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ScreenshotFiles {
    suspend fun import(
        context: Context,
        uri: Uri,
    ): NewAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val sourceType = resolver.getType(uri)
            ?.lowercase()
            ?.takeIf { it == "image/png" || it == "image/jpeg" }
        val original = resolver.openInputStream(uri)?.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (output.size() <= MAX_BYTES) {
                val read = stream.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: error("无法读取截图")
        require(original.size <= MAX_BYTES) { "截图不能超过 100 MB" }
        require(original.isNotEmpty()) { "截图内容为空" }

        val bytes: ByteArray
        val mediaType: String
        if (sourceType != null) {
            bytes = original
            mediaType = sourceType
        } else {
            val bitmap = BitmapFactory.decodeByteArray(original, 0, original.size)
                ?: error("系统选择的文件不是可识别的图片")
            val output = ByteArrayOutputStream()
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, output)) {
                "无法转换截图格式"
            }
            bitmap.recycle()
            bytes = output.toByteArray()
            mediaType = "image/jpeg"
        }
        val attachmentId = UUID.randomUUID().toString()
        val extension = if (mediaType == "image/png") "png" else "jpg"
        val directory = File(context.filesDir, "attachments").apply { mkdirs() }
        val target = File(directory, "$attachmentId.$extension")
        val temp = File(directory, "$attachmentId.$extension.tmp")
        temp.writeBytes(bytes)
        check(temp.renameTo(target)) { "无法保存截图" }
        NewAttachment(
            attachmentId = attachmentId,
            kind = AttachmentKind.SCREENSHOT,
            mediaType = mediaType,
            byteSize = bytes.size.toLong(),
            sha256 = sha256Hex(bytes),
            localPath = target.absolutePath,
            createdAt = Instant.now().toString(),
        )
    }

    private const val MAX_BYTES = 100 * 1024 * 1024
}
