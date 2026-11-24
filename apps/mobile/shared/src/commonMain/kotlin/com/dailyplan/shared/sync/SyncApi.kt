package com.dailyplan.shared.sync

import com.dailyplan.shared.model.Attachment
import com.dailyplan.shared.model.ChangesPage
import com.dailyplan.shared.model.Operation
import com.dailyplan.shared.model.OperationReceipt
import com.dailyplan.shared.model.Snapshot
import com.dailyplan.shared.model.Status
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ApiException(
    val statusCode: Int,
    val code: String,
    override val message: String,
) : Exception(message)

class SyncApi(
    private val serverUrl: String,
    private val token: String,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    },
    private val client: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 120_000
        }
    },
) {
    private val base = serverUrl.trim().trimEnd('/')

    suspend fun status(): Status = request {
        client.get(url("/v1/status")) {
            bearer()
        }
    }

    suspend fun submit(operation: Operation): OperationReceipt = request {
        client.post(url("/v1/operations")) {
            bearer()
            contentType(ContentType.Application.Json)
            setBody(operation)
        }
    }

    suspend fun changes(after: Long, limit: Int = 100): ChangesPage = request {
        client.get(url("/v1/changes?after=$after&limit=$limit")) {
            bearer()
        }
    }

    suspend fun snapshot(): Snapshot = request {
        client.get(url("/v1/snapshot")) {
            bearer()
        }
    }

    suspend fun uploadAttachment(
        attachment: Attachment,
        bytes: ByteArray,
    ): Attachment {
        val kind = when (attachment.kind.name) {
            "SCREENSHOT" -> "screenshot"
            else -> "recording"
        }
        val path = "/v1/attachments/${attachment.attachmentId}" +
            "?taskId=${attachment.taskId.encodeURLParameter()}" +
            "&kind=${kind.encodeURLParameter()}" +
            "&mediaType=${attachment.mediaType.encodeURLParameter()}"
        return request {
            client.put(url(path)) {
                bearer()
                contentType(ContentType.parse(attachment.mediaType))
                header("X-Content-SHA256", attachment.sha256)
                header(HttpHeaders.ContentLength, bytes.size.toString())
                setBody(bytes)
            }
        }
    }

    suspend fun downloadAttachment(attachmentId: String): ByteArray {
        val response = client.get(url("/v1/attachments/$attachmentId")) {
            bearer()
        }
        if (!response.status.isSuccess()) {
            throw apiError(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    suspend fun retryTranscription(attachmentId: String): Attachment = request {
        client.post(url("/v1/attachments/$attachmentId/transcription/retry")) {
            bearer()
        }
    }

    fun close() {
        client.close()
    }

    private fun url(path: String) = io.ktor.http.URLBuilder().apply {
        takeFrom(base + path)
    }.build()

    private fun io.ktor.client.request.HttpRequestBuilder.bearer() {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private suspend inline fun <reified T> request(
        block: () -> io.ktor.client.statement.HttpResponse,
    ): T {
        val response = block()
        if (!response.status.isSuccess()) {
            throw apiError(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    private fun apiError(statusCode: Int, body: String): ApiException {
        val parsed = runCatching {
            val error = json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
            val code = error?.get("code")?.jsonPrimitive?.contentOrNull ?: "http_$statusCode"
            val message = error?.get("message")?.jsonPrimitive?.contentOrNull
            if (message.isNullOrBlank()) "服务器返回 HTTP $statusCode" else message
            code to message
        }.getOrNull()
        return ApiException(
            statusCode = statusCode,
            code = parsed?.first ?: "http_$statusCode",
            message = parsed?.second?.takeIf(String::isNotBlank)
                ?: body.take(300).ifBlank { "服务器返回 HTTP $statusCode" },
        )
    }
}
