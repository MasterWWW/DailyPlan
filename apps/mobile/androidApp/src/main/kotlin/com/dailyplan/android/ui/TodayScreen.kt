package com.dailyplan.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailyplan.shared.model.ReminderPeriodWire
import com.dailyplan.shared.model.Task
import com.dailyplan.shared.rules.Reminder
import com.dailyplan.shared.rules.TaskAttention
import com.dailyplan.shared.rules.TaskAttention as Attention
import kotlinx.datetime.LocalDate

@Composable
fun TodayScreen(
    today: LocalDate,
    tasks: List<Task>,
    completed: List<Task>,
    attention: Map<String, Attention>,
    pendingCount: Int,
    attachmentCounts: Map<String, Int>,
    thumbnails: Map<String, String>,
    isSyncing: Boolean,
    syncMessage: String?,
    isRecording: Boolean,
    isStoppingRecording: Boolean,
    requestTextFocus: Boolean,
    reminder: Reminder?,
    onAdd: (String) -> Unit,
    onPickScreenshot: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onComplete: (Task, Boolean) -> Unit,
    onOpenTask: (Task) -> Unit,
    onOpenSettings: () -> Unit,
    onSync: () -> Unit,
    onAcknowledgeReminder: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(requestTextFocus) {
        if (requestTextFocus) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "今天",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.8).sp,
                )
                Text(
                    text = if (tasks.isEmpty()) "清单已经清空" else "${tasks.size} 件待完成",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            }
            if (isSyncing) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            } else {
                IconButton(onClick = onSync) {
                    Icon(Icons.Outlined.Sync, contentDescription = "立即同步")
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "设置")
            }
        }

        if (pendingCount > 0 || syncMessage != null) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = syncMessage ?: "已存本机，待同步 $pendingCount 项",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        if (reminder != null) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (reminder.period == ReminderPeriodWire.MORNING) {
                                "上班提醒"
                            } else {
                                "下班前回顾"
                            },
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "${reminder.taskIds.size} 件待办等待查看",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    FilledTonalButton(onClick = onAcknowledgeReminder) {
                        Text("已查看")
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            placeholder = { Text("随手记下一件事") },
            shape = RoundedCornerShape(18.dp),
            maxLines = 3,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    val title = input.trim()
                    if (title.isNotEmpty()) {
                        onAdd(title)
                        input = ""
                    }
                },
            ),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPickScreenshot) {
                        Icon(Icons.Outlined.Image, contentDescription = "选截图")
                    }
                    IconButton(
                        onClick = if (isRecording) onStopRecording else onStartRecording,
                        enabled = !isStoppingRecording,
                    ) {
                        if (isStoppingRecording) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                if (isRecording) Icons.Outlined.StopCircle else Icons.Outlined.Mic,
                                contentDescription = if (isRecording) "停止录音" else "开始录音",
                                tint = if (isRecording) MaterialTheme.colorScheme.error else
                                    MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            val title = input.trim()
                            if (title.isNotEmpty()) {
                                onAdd(title)
                                input = ""
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.Send, contentDescription = "保存")
                    }
                }
            },
        )

        if (isRecording) {
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "正在录音，点麦克风停止并保存",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FilledTonalButton(onClick = onStopRecording) {
                        Text("停止")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(tasks, key = { it.taskId }) { task ->
                TaskRow(
                    task = task,
                    today = today,
                    attention = attention[task.taskId],
                    attachmentCount = attachmentCounts[task.taskId] ?: 0,
                    thumbnailPath = thumbnails[task.taskId],
                    onComplete = { onComplete(task, true) },
                    onOpen = { onOpenTask(task) },
                )
            }
            if (completed.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "已完成",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                items(completed.take(12), key = { "done-${it.taskId}" }) { task ->
                    TaskRow(
                        task = task,
                        today = today,
                        attention = null,
                        attachmentCount = attachmentCounts[task.taskId] ?: 0,
                        thumbnailPath = thumbnails[task.taskId],
                        onComplete = { onComplete(task, false) },
                        onOpen = { onOpenTask(task) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: Task,
    today: LocalDate,
    attention: TaskAttention?,
    attachmentCount: Int,
    thumbnailPath: String?,
    onComplete: () -> Unit,
    onOpen: () -> Unit,
) {
    val thumbnail = remember(thumbnailPath) {
        thumbnailPath?.let { decodeSampled(it, 128) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onComplete,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                if (task.completed) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = if (task.completed) "标记为未完成" else "完成",
                tint = if (task.completed) MaterialTheme.colorScheme.primary else
                    MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (thumbnailPath != null) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = "截图缩略图",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Outlined.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (task.completed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Row(
                modifier = Modifier.padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = dueLabel(task, today),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (attachmentCount > 0) {
                    Text(
                        text = "  ·  $attachmentCount 个附件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val marker = when {
                    attention?.overdue == true -> "已逾期"
                    task.priority == com.dailyplan.shared.model.Priority.VERY_URGENT -> "非常紧急"
                    task.priority == com.dailyplan.shared.model.Priority.URGENT -> "紧急"
                    attention?.dueSoon == true -> "临期"
                    else -> null
                }
                if (marker != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = marker,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (attention?.overdue == true) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.secondary
                        },
                    )
                }
            }
        }
    }
}
