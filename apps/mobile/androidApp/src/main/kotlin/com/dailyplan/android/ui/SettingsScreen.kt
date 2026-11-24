package com.dailyplan.android.ui

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailyplan.android.AppContainer
import com.dailyplan.android.ReminderScheduler
import com.dailyplan.shared.model.SyncMeta
import com.dailyplan.shared.model.WorkSettings
import com.dailyplan.shared.sync.SyncApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

@Composable
fun SettingsScreen(
    container: AppContainer,
    current: WorkSettings,
    serverUrl: String,
    pendingCount: Int,
    sync: SyncMeta,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onSync: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var server by remember(serverUrl) { mutableStateOf(serverUrl) }
    var token by remember { mutableStateOf("") }
    var timezone by remember(current.timeZone) { mutableStateOf(current.timeZone) }
    var workStart by remember(current.workStart) { mutableStateOf(current.workStart) }
    var workEnd by remember(current.workEnd) { mutableStateOf(current.workEnd) }
    var lead by remember(current.reminderLeadMinutes) {
        mutableStateOf(current.reminderLeadMinutes.toString())
    }
    var workdays by remember(current.workdays) {
        mutableStateOf(current.workdays.toSet())
    }
    var saving by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var tokenLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        token = container.tokenStore.token().orEmpty()
        tokenLoaded = true
    }

    fun save(showSync: Boolean = false) {
        scope.launch {
            saving = true
            runCatching {
                val minutes = lead.toIntOrNull() ?: error("提前量需要是整数")
                require(minutes in 0..600) { "提前量需在 0 到 600 分钟之间" }
                require(workdays.isNotEmpty()) { "至少选择一个工作日" }
                container.repository.saveSettings(
                    WorkSettings(
                        workdays = workdays.sorted(),
                        timeZone = timezone.trim(),
                        workStart = workStart.trim(),
                        workEnd = workEnd.trim(),
                        reminderLeadMinutes = minutes,
                    ),
                    server,
                )
                if (token.isBlank()) {
                    container.tokenStore.clearToken()
                } else {
                    container.tokenStore.saveToken(token)
                }
                ReminderScheduler.scheduleNext(context)
                onSaved()
                if (showSync) {
                    runCatching { container.sync() }
                        .onFailure { onMessage(it.message ?: "同步失败") }
                }
                onMessage("设置已保存")
            }.onFailure {
                onMessage(it.message ?: "设置保存失败")
            }
            saving = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
            }
            Text(
                "设置",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))

        SectionTitle("公司内网服务")
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("服务器地址") },
            placeholder = { Text("http://192.168.10.20:8787") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("访问令牌") },
            singleLine = true,
            enabled = tokenLoaded,
            visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(14.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        testing = true
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val api = SyncApi(server, token)
                                try {
                                    api.status()
                                } finally {
                                    api.close()
                                }
                            }
                        }.onSuccess { status ->
                            onMessage(
                                "连接成功，协议 v${status.protocolVersion}，" +
                                    "服务器游标 ${status.cursor}",
                            )
                        }.onFailure {
                            onMessage("连接失败：${it.message}")
                        }
                        testing = false
                    }
                },
                enabled = !testing && server.isNotBlank() && token.isNotBlank(),
            ) {
                Text(if (testing) "测试中…" else "测试连接")
            }
            OutlinedButton(onClick = onSync) {
                Text("立即同步")
            }
        }
        Spacer(Modifier.height(10.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "待同步 $pendingCount 项",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    sync.lastSuccessAt?.let { "最近成功：$it" } ?: "尚未成功同步",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                sync.lastError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionTitle("作息与提醒")
        Text(
            "工作日",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                1 to "一",
                2 to "二",
                3 to "三",
                4 to "四",
                5 to "五",
                6 to "六",
                7 to "日",
            ).forEach { (day, label) ->
                FilterChip(
                    selected = day in workdays,
                    onClick = {
                        workdays = if (day in workdays) workdays - day else workdays + day
                    },
                    label = { Text("周$label") },
                    leadingIcon = if (day in workdays) {
                        {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                modifier = Modifier.height(16.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = timezone,
            onValueChange = { timezone = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("工作时区") },
            placeholder = { Text("Asia/Shanghai") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = workStart,
                onValueChange = { workStart = it },
                modifier = Modifier.weight(1f),
                label = { Text("上班") },
                placeholder = { Text("09:00") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            OutlinedTextField(
                value = workEnd,
                onValueChange = { workEnd = it },
                modifier = Modifier.weight(1f),
                label = { Text("下班") },
                placeholder = { Text("18:00") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            OutlinedTextField(
                value = lead,
                onValueChange = { lead = it.filter(Char::isDigit).take(3) },
                modifier = Modifier.weight(0.9f),
                label = { Text("提前分钟") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "下次提醒：" + (
                ReminderScheduler.nextLabel(Clock.System.now(), current) ?: "未安排"
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            if (!alarmManager.canScheduleExactAlarms()) {
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                ) {
                    Text("允许精确闹钟，确保提醒准点")
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Button(
            onClick = { save(showSync = true) },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (saving) "正在保存…" else "保存并同步")
        }
        Text(
            "令牌只保存在 Android 加密存储中。使用内网 HTTP 时，数据仅发往你填写的服务地址。",
            modifier = Modifier.padding(top = 10.dp, bottom = 32.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(bottom = 8.dp),
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
    )
}
