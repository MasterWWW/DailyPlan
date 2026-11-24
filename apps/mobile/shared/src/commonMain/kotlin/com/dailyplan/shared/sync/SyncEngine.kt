package com.dailyplan.shared.sync

import com.dailyplan.shared.data.LocalAttachmentStorage
import com.dailyplan.shared.data.PlanRepository
import com.dailyplan.shared.model.PROTOCOL_VERSION
import com.dailyplan.shared.model.Status
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

data class SyncResult(
    val status: Status,
    val uploadedAttachments: Int,
    val downloadedAttachments: Int,
)

fun interface TokenProvider {
    suspend fun token(): String?
}

class SyncEngine(
    private val repository: PlanRepository,
    private val tokenProvider: TokenProvider,
    private val storage: LocalAttachmentStorage,
    private val json: Json = repository.json,
    private val apiFactory: (String, String) -> SyncApi = { server, token ->
        SyncApi(server, token, json)
    },
) {
    suspend fun syncOnce(): SyncResult {
        val settings = repository.settingsEntity()
        val serverUrl = settings.serverUrl.trim().trimEnd('/')
        require(serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) {
            "尚未配置服务器地址"
        }
        val token = tokenProvider.token()?.trim()
        require(!token.isNullOrEmpty()) { "尚未设置访问令牌" }

        val api = apiFactory(serverUrl, token)
        try {
            val status = api.status()
            require(status.protocolVersion == PROTOCOL_VERSION) {
                "服务器协议版本 ${status.protocolVersion} 不受支持"
            }
            val meta = repository.syncMeta()
            if (meta.datasetId != status.datasetId ||
                meta.restoreVersion != status.restoreVersion
            ) {
                repository.applySnapshot(api.snapshot(), status.datasetId)
            }

            for (operation in repository.pendingOperations()) {
                val receipt = api.submit(operation.payload)
                require(receipt.opId == operation.opId) { "服务器确认了错误的操作" }
                repository.completeOperation(operation.opId)
            }

            var uploaded = 0
            var attachmentError: Throwable? = null
            for (local in repository.pendingUploadAttachments()) {
                try {
                    val path = local.localPath
                        ?: error("本地附件 ${local.attachment.attachmentId} 缺少文件，无法上传")
                    val bytes = storage.read(path)
                    require(bytes.size.toLong() == local.attachment.byteSize) {
                        "本地附件大小与记录不一致"
                    }
                    require(sha256Hex(bytes) == local.attachment.sha256) {
                        "本地附件摘要校验失败，拒绝上传"
                    }
                    val remote =
                        api.uploadAttachment(local.attachment, bytes)
                    repository.markAttachmentRemote(remote)
                    uploaded++
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (attachmentError == null) {
                        attachmentError = error
                    }
                }
            }

            var cursor = repository.syncMeta().cursor
            var pages = 0
            while (pages < 50) {
                val page = api.changes(cursor)
                if (page.changes.isNotEmpty()) {
                    repository.applyChanges(page.changes, page.nextCursor)
                }
                cursor = page.nextCursor
                pages++
                if (!page.hasMore) {
                    break
                }
            }

            var downloaded = 0
            for (local in repository.missingLocalAttachments()) {
                val bytes = api.downloadAttachment(local.attachment.attachmentId)
                require(bytes.size.toLong() == local.attachment.byteSize) {
                    "下载附件大小校验失败"
                }
                require(sha256Hex(bytes) == local.attachment.sha256) {
                    "下载附件摘要校验失败"
                }
                val path = storage.write(
                    local.attachment.attachmentId,
                    local.attachment.mediaType,
                    bytes,
                )
                repository.setAttachmentLocalPath(local.attachment.attachmentId, path)
                downloaded++
            }
            val failure = attachmentError
            if (failure != null) {
                val message = failure.message
                    ?: failure::class.simpleName
                    ?: "附件同步失败"
                repository.markSyncError(message)
                throw failure
            }
            repository.markSyncSuccess(status, cursor)
            return SyncResult(status, uploaded, downloaded)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            repository.markSyncError(error.message ?: error::class.simpleName ?: "同步失败")
            throw error
        } finally {
            api.close()
        }
    }
}

expect fun sha256Hex(bytes: ByteArray): String
