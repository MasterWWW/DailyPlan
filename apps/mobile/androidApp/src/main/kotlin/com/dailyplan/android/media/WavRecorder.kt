package com.dailyplan.android.media

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.dailyplan.shared.sync.sha256Hex
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RecordedAttachment(
    val attachmentId: String,
    val path: String,
    val byteSize: Long,
    val sha256: String,
    val createdAt: String,
)

class WavRecorder(private val context: Context) {
    private var record: AudioRecord? = null
    private var writer: Job? = null
    private var rawFile: File? = null
    private var running = false

    fun start(scope: CoroutineScope) {
        check(!running) { "录音已经开始" }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "设备不支持 16 kHz 单声道录音" }
        val bufferSize = maxOf(minBuffer * 2, SAMPLE_RATE / 4)
        val recorder = createRecorder(bufferSize)
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "麦克风初始化失败"
        }
        val directory = File(context.filesDir, "attachments").apply { mkdirs() }
        val raw = File(directory, "${UUID.randomUUID()}.pcm")
        rawFile = raw
        record = recorder
        running = true
        recorder.startRecording()
        writer = scope.launch(Dispatchers.IO) {
            BufferedOutputStream(FileOutputStream(raw)).use { output ->
                val buffer = ByteArray(bufferSize)
                var written = 0L
                while (isActive && running && written < MAX_PCM_BYTES) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read > 0) {
                        val length = minOf(read.toLong(), MAX_PCM_BYTES - written).toInt()
                        if (length > 0) {
                            output.write(buffer, 0, length)
                            written += length
                        }
                    }
                }
                if (written >= MAX_PCM_BYTES) {
                    recorder.runCatching { stop() }
                }
            }
        }
    }

    suspend fun stop(): RecordedAttachment = withContext(Dispatchers.IO) {
        check(running) { "当前没有录音" }
        running = false
        writer?.join()
        writer = null
        record?.runCatching { stop() }
        record?.runCatching { release() }
        record = null

        val raw = checkNotNull(rawFile) { "录音文件丢失" }
        val attachmentId = UUID.randomUUID().toString()
        val directory = raw.parentFile ?: File(context.filesDir, "attachments")
        val target = File(directory, "$attachmentId.wav")
        val dataSize = raw.length()
        require(dataSize > 0) {
            raw.delete()
            "没有录到声音"
        }
        FileOutputStream(target).use { stream ->
            stream.write(wavHeader(dataSize))
            raw.inputStream().use { it.copyTo(stream) }
        }
        raw.delete()
        rawFile = null
        RecordedAttachment(
            attachmentId = attachmentId,
            path = target.absolutePath,
            byteSize = target.length(),
            sha256 = sha256Hex(target.readBytes()),
            createdAt = Instant.now().toString(),
        )
    }

    fun cancel() {
        running = false
        writer?.cancel()
        writer = null
        record?.runCatching { stop() }
        record?.runCatching { release() }
        record = null
        rawFile?.delete()
        rawFile = null
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(bufferSize: Int): AudioRecord =
        AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

    private fun wavHeader(dataSize: Long): ByteArray {
        val byteRate = SAMPLE_RATE * 2
        val header = ByteArray(44)
        fun putAscii(offset: Int, value: String) {
            value.encodeToByteArray().copyInto(header, offset)
        }
        fun putInt(offset: Int, value: Long) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value shr 8) and 0xff).toByte()
            header[offset + 2] = ((value shr 16) and 0xff).toByte()
            header[offset + 3] = ((value shr 24) and 0xff).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value shr 8) and 0xff).toByte()
        }
        putAscii(0, "RIFF")
        putInt(4, dataSize + 36)
        putAscii(8, "WAVEfmt ")
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, 1)
        putInt(24, SAMPLE_RATE.toLong())
        putInt(28, byteRate.toLong())
        putShort(32, 2)
        putShort(34, 16)
        putAscii(36, "data")
        putInt(40, dataSize)
        return header
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_PCM_BYTES = SAMPLE_RATE * 2L * 60L * 20L
    }
}
