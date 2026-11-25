package com.dailyplan.android.ui

import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import com.dailyplan.android.AppContainer
import com.dailyplan.android.media.ScreenshotFiles
import com.dailyplan.android.widget.DailyPlanWidget
import com.dailyplan.shared.model.AttachmentKind
import com.dailyplan.shared.model.LocalAttachment
import com.dailyplan.shared.model.Priority
import com.dailyplan.shared.model.Task
import com.dailyplan.shared.sync.SyncApi
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    container: AppContainer,
    task: Task,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember(task.taskId, task.title) { mutableStateOf(task.title) }
    var dueDate by remember(task.taskId, task.dueDate) { mutableStateOf(task.dueDate) }
    var dueTime by remember(task.taskId, task.dueTime) { mutableStateOf(task.dueTime.orEmpty()) }
    var priority by remember(task.taskId, task.priority) { mutableStateOf(task.priority) }
    val attachments by container.repository.observeAttachmentsFor(task.taskId)
        .collectAsStateWithLifecycle(emptyList())
    var previewPath by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val attachment = ScreenshotFiles.import(context, uri)
                    container.repository.addAttachment(task.taskId, attachment)
                    DailyPlanWidget.updateAll(context)
                }.onSuccess {
                    onMessage("截图已加到待办")
                }.onFailure {
                    onMessage(it.message ?: "截图保存失败")
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
            }
            Text(
                "待办详情",
                modifier = Modifier.weight(1f),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(
                onClick = {
                    scope.launch {
                        runCatching {
                            container.repository.updateTask(
                                task.taskId,
                                completed = !task.completed,
                            )
                            DailyPlanWidget.updateAll(context)
                        }.onFailure { onMessage(it.message ?: "状态保存失败") }
                    }
                },
            ) {
                Icon(
                    if (task.completed) Icons.Outlined.CheckCircle
                    else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (task.completed) "标记为未完成" else "完成",
                    tint = if (task.completed) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { confirmingDelete = true }) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除待办")
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("标题") },
            shape = RoundedCornerShape(14.dp),
            maxLines = 4,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = dueDate,
                onValueChange = { dueDate = it },
                modifier = Modifier.weight(1.3f),
                label = { Text("截止日期") },
                placeholder = { Text("YYYY-MM-DD") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            OutlinedTextField(
                value = dueTime,
                onValueChange = { dueTime = it },
                modifier = Modifier.weight(1f),
                label = { Text("时刻") },
                placeholder = { Text("HH:mm") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "紧急程度",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Priority.entries.forEach { value ->
                FilterChip(
                    selected = priority == value,
                    onClick = { priority = value },
                    label = { Text(priorityLabel(value)) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                scope.launch {
                    saving = true
                    runCatching {
                        container.repository.updateTask(
                            taskId = task.taskId,
                            title = title,
                            dueDate = dueDate,
                            dueTime = dueTime.trim().ifBlank { null },
                            dueTimeSet = true,
                            priority = priority,
                        )
                        DailyPlanWidget.updateAll(context)
                        onSaved()
                        onMessage("已保存")
                    }.onFailure {
                        onMessage(it.message ?: "保存失败")
                    }
                    saving = false
                }
            },
            enabled = !saving && title.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (saving) "正在保存…" else "保存修改")
        }

        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "截图与录音",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            ) {
                Icon(Icons.Outlined.Image, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("加截图")
            }
        }
        Spacer(Modifier.height(10.dp))
        attachments.filter { it.localPath != null }.forEach { local ->
            if (local.attachment.kind == AttachmentKind.SCREENSHOT) {
                ScreenshotCard(local, onClick = { previewPath = local.localPath })
            } else {
                RecordingCard(
                    local = local,
                    onRetry = {
                        scope.launch {
                            runCatching {
                                val settings = container.repository.settingsEntity()
                                val token = container.tokenStore.token()
                                require(token != null) { "尚未设置访问令牌" }
                                val api = SyncApi(settings.serverUrl, token)
                                try {
                                    api.retryTranscription(local.attachment.attachmentId)
                                } finally {
                                    api.close()
                                }
                                container.sync()
                            }.onFailure { onMessage(it.message ?: "重试失败") }
                        }
                    },
                )
            }
        }
        if (attachments.none { it.localPath != null }) {
            Text(
                "还没有截图或录音。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(32.dp))
    }

    previewPath?.let { path ->
        ImagePreview(path) { previewPath = null }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除待办") },
            text = { Text("将同时删除该待办下的所有截图和录音，且无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        scope.launch {
                            val failure = runCatching {
                                val removed = container.repository.deleteTask(task.taskId)
                                removed.forEach { local ->
                                    local.localPath?.let { container.attachmentStorage.delete(it) }
                                }
                                DailyPlanWidget.updateAll(context)
                            }.exceptionOrNull()
                            if (failure != null) {
                                onMessage(failure.message ?: "删除失败")
                            } else {
                                onBack()
                                onMessage("已删除")
                            }
                        }
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun ScreenshotCard(local: LocalAttachment, onClick: () -> Unit) {
    val path = local.localPath ?: return
    val bitmap = remember(path) { decodeSampled(path, 720) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(bottom = 10.dp),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "截图",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text("截图文件无法打开", modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun RecordingCard(local: LocalAttachment, onRetry: () -> Unit) {
    val path = local.localPath ?: return
    var playing by remember(path) { mutableStateOf(false) }
    var player by remember(path) { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(path) {
        onDispose {
            player?.release()
            player = null
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        if (playing) {
                            player?.pause()
                            playing = false
                        } else {
                            val current = player ?: MediaPlayer().apply {
                                setDataSource(path)
                                prepare()
                                setOnCompletionListener {
                                    playing = false
                                    seekTo(0)
                                }
                            }.also { player = it }
                            current.start()
                            playing = true
                        }
                    },
                ) {
                    Icon(
                        if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (playing) "暂停" else "播放录音",
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("录音", fontWeight = FontWeight.Medium)
                    transcriptionLabel(local.attachment.transcriptionStatus)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (local.attachment.transcriptionStatus ==
                    com.dailyplan.shared.model.TranscriptionState.FAILED
                ) {
                    OutlinedButton(onClick = onRetry) { Text("重试") }
                }
            }
            local.attachment.transcript?.takeIf(String::isNotBlank)?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            local.attachment.transcriptionError?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ImagePreview(path: String, onDismiss: () -> Unit) {
    val bitmap = remember(path) { decodeSampled(path, 1600) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.9f))
                .clickable(onClick = onDismiss)
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "原图预览",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text("截图文件无法打开", color = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
    }
}

