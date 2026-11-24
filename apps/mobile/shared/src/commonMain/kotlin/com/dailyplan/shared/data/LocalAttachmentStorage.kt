package com.dailyplan.shared.data

interface LocalAttachmentStorage {
    suspend fun read(path: String): ByteArray

    suspend fun write(attachmentId: String, mediaType: String, bytes: ByteArray): String
}
