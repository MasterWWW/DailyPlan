package com.dailyplan.android.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.dailyplan.android.AppContainer
import com.dailyplan.android.MainActivity
import com.dailyplan.android.ReminderNotifier
import com.dailyplan.android.ReminderScheduler
import com.dailyplan.android.SyncWorker
import com.dailyplan.android.media.ScreenshotFiles
import com.dailyplan.android.media.WavRecorder
import com.dailyplan.android.widget.DailyPlanWidget
import com.dailyplan.shared.data.NewAttachment
import com.dailyplan.shared.model.AppSnapshot
import com.dailyplan.shared.model.AttachmentKind
import com.dailyplan.shared.model.Priority
import com.dailyplan.shared.model.Task
import com.dailyplan.shared.model.TitleSource
import com.dailyplan.shared.model.WorkSettings
import com.dailyplan.shared.rules.evaluate
import com.dailyplan.shared.sync.FOREGROUND_SYNC_INTERVAL_MILLIS
import com.dailyplan.shared.sync.syncRetryDelayMillis
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

@Composable
fun DailyPlanApp(
    container: AppContainer,
    initialMode: String,
    initialTaskId: String?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = container.repository
    val liveTasks by repository.observeTasks()
        .collectAsStateWithLifecycle(emptyList())
    val pendingCount by repository.observePendingCount()
        .collectAsStateWithLifecycle(0)
    var snapshot by remember { mutableStateOf<AppSnapshot?>(null) }
    var attachmentCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var thumbnails by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var now by remember { mutableStateOf(Clock.System.now()) }
    var isSyncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var isStoppingRecording by remember { mutableStateOf(false) }
    var requestTextFocus by remember {
        mutableStateOf(initialMode == MainActivity.MODE_TEXT)
    }
    var requestRecording by remember {
        mutableStateOf(initialMode == MainActivity.MODE_RECORD)
    }
    var screen by rememberSaveable {
        mutableStateOf(
            if (initialMode == MainActivity.MODE_DETAIL && initialTaskId != null) "detail"
            else "list",
        )
    }
    var detailId by rememberSaveable { mutableStateOf(initialTaskId) }
    val snackbar = remember { SnackbarHostState() }
    val recorder = remember { WavRecorder(context) }

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    fun returnToHomeAfterCapture(sourceMode: String) {
        if (initialMode == sourceMode) {
            (context as? Activity)?.moveTaskToBack(true)
        }
    }

    suspend fun reload() {
        snapshot = repository.snapshot()
        val counts = mutableMapOf<String, Int>()
        val thumbs = mutableMapOf<String, String>()
        for (task in liveTasks) {
            val attachments = repository.attachmentsFor(task.taskId)
            if (attachments.isNotEmpty()) counts[task.taskId] = attachments.size
            attachments.firstOrNull {
                it.attachment.kind == AttachmentKind.SCREENSHOT && it.localPath != null
            }?.localPath?.let { thumbs[task.taskId] = it }
        }
        attachmentCounts = counts
        thumbnails = thumbs
    }

    suspend fun syncNow(showResult: Boolean) {
        if (isSyncing) return
        val settings = repository.settingsEntity()
        if (settings.serverUrl.isBlank() || container.tokenStore.token().isNullOrBlank()) {
            if (showResult) message("请先在设置中填写服务器地址和访问令牌")
            return
        }
        isSyncing = true
        syncMessage = null
        runCatching { container.sync() }
            .onSuccess { result ->
                val changed = result.uploadedAttachments + result.downloadedAttachments
                if (showResult) {
                    message(if (changed > 0) "同步完成，传输 $changed 个附件" else "同步完成")
                }
            }
            .onFailure {
                syncMessage = it.message ?: "同步失败"
                if (showResult) message("同步失败：${syncMessage}")
            }
        isSyncing = false
        reload()
        DailyPlanWidget.updateAll(context)
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            runCatching {
                recorder.start(scope)
                isRecording = true
                requestRecording = false
            }.onFailure {
                message(it.message ?: "无法开始录音")
            }
        } else {
            requestRecording = false
            message("需要麦克风权限才能录音；文字、截图仍可正常使用")
        }
    }

    fun startRecording() {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                recorder.start(scope)
                isRecording = true
            }.onFailure { message(it.message ?: "无法开始录音") }
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun stopRecording() {
        if (!isRecording || isStoppingRecording) return
        scope.launch {
            isStoppingRecording = true
            runCatching {
                val recorded = recorder.stop()
                val work = snapshot?.settings ?: repository.settings()
                val title = "语音待办 · ${timeLabel(Clock.System.now(), work)}"
                repository.createTask(
                    title = title,
                    titleSource = TitleSource.VOICE_PLACEHOLDER,
                    attachment = NewAttachment(
                        attachmentId = recorded.attachmentId,
                        kind = AttachmentKind.RECORDING,
                        mediaType = "audio/wav",
                        byteSize = recorded.byteSize,
                        sha256 = recorded.sha256,
                        localPath = recorded.path,
                        createdAt = recorded.createdAt,
                    ),
                )
                SyncWorker.syncNow(context)
                reload()
                DailyPlanWidget.updateAll(context)
                "录音已存本机，稍后自动同步"
            }.onSuccess {
                message(it)
                returnToHomeAfterCapture(MainActivity.MODE_RECORD)
            }
                .onFailure { message(it.message ?: "录音保存失败") }
            isRecording = false
            isStoppingRecording = false
        }
    }

    val screenshotPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                var savedPath: String? = null
                runCatching {
                    val attachment = withContext(Dispatchers.IO) {
                        ScreenshotFiles.import(context, uri)
                    }
                    savedPath = attachment.localPath
                    val work = snapshot?.settings ?: repository.settings()
                    repository.createTask(
                        title = "截图 · ${timeLabel(Clock.System.now(), work)}",
                        titleSource = TitleSource.SCREENSHOT_PLACEHOLDER,
                        attachment = attachment,
                    )
                    SyncWorker.syncNow(context)
                    reload()
                    DailyPlanWidget.updateAll(context)
                }.onSuccess {
                    message("截图已存本机，稍后自动同步")
                }.onFailure {
                    savedPath?.let { File(it).delete() }
                    message(it.message ?: "截图保存失败")
                }
            }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ReminderNotifier.showIfDue(context, container)
        SyncWorker.enqueuePeriodic(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var failures = 0
            while (isActive) {
                var attempted = false
                var succeeded = true
                val settings = repository.settingsEntity()
                if (settings.serverUrl.isNotBlank() &&
                    !container.tokenStore.token().isNullOrBlank()
                ) {
                    attempted = true
                    succeeded = runCatching { container.sync() }.isSuccess
                    reload()
                    DailyPlanWidget.updateAll(context)
                }
                failures = if (attempted && !succeeded) failures + 1 else 0
                delay(
                    if (failures == 0) {
                        FOREGROUND_SYNC_INTERVAL_MILLIS
                    } else {
                        syncRetryDelayMillis(failures)
                    },
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(60_000)
            now = Clock.System.now()
            snapshot = repository.snapshot()
        }
    }

    LaunchedEffect(liveTasks, pendingCount) {
        reload()
    }

    LaunchedEffect(requestRecording) {
        if (requestRecording) {
            startRecording()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder.cancel()
        }
    }

    val allTasks = snapshot?.completed?.let { completed ->
        (liveTasks + completed).distinctBy(Task::taskId)
    } ?: liveTasks
    val settings = snapshot?.settings ?: WorkSettings()
    LaunchedEffect(settings) {
        ReminderScheduler.scheduleNext(context)
    }
    val evaluation = remember(allTasks, settings, snapshot?.acknowledged, now) {
        runCatching {
            evaluate(
                now = now,
                tasks = allTasks,
                settings = settings,
                acknowledged = snapshot?.acknowledged.orEmpty(),
            )
        }.getOrNull()
    }
    val visible = evaluation?.visible ?: liveTasks.filterNot(Task::completed)
    val attention = evaluation?.attention.orEmpty().associateBy { it.taskId }
    val completed = snapshot?.completed.orEmpty()
    val today = localNow(settings).date

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                "detail" -> {
                    val selected = detailId?.let { id -> allTasks.firstOrNull { it.taskId == id } }
                    if (selected == null) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else {
                        TaskDetailScreen(
                            container = container,
                            task = selected,
                            onBack = {
                                screen = "list"
                                scope.launch { reload() }
                            },
                            onSaved = {
                                scope.launch { reload() }
                            },
                            onMessage = ::message,
                        )
                    }
                }
                "settings" -> SettingsScreen(
                    container = container,
                    current = settings,
                    serverUrl = snapshot?.serverUrl.orEmpty(),
                    pendingCount = pendingCount,
                    sync = snapshot?.sync ?: repositorySnapshotSync(container),
                    onBack = { screen = "list" },
                    onSaved = {
                        scope.launch { reload() }
                        requestTextFocus = false
                    },
                    onSync = { scope.launch { syncNow(showResult = true) } },
                    onMessage = ::message,
                )
                else -> TodayScreen(
                    today = today,
                    tasks = visible,
                    completed = completed,
                    attention = attention,
                    pendingCount = pendingCount,
                    attachmentCounts = attachmentCounts,
                    thumbnails = thumbnails,
                    isSyncing = isSyncing,
                    syncMessage = syncMessage,
                    isRecording = isRecording,
                    isStoppingRecording = isStoppingRecording,
                    requestTextFocus = requestTextFocus,
                    reminder = evaluation?.reminder,
                    onAdd = { title ->
                        scope.launch {
                            runCatching {
                                repository.createTask(title)
                                SyncWorker.syncNow(context)
                                reload()
                                DailyPlanWidget.updateAll(context)
                            }.onSuccess {
                                returnToHomeAfterCapture(MainActivity.MODE_TEXT)
                            }.onFailure { message(it.message ?: "保存失败") }
                        }
                    },
                    onPickScreenshot = {
                        screenshotPicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    onStartRecording = ::startRecording,
                    onStopRecording = ::stopRecording,
                    onComplete = { task, completedNow ->
                        scope.launch {
                            runCatching {
                                repository.updateTask(task.taskId, completed = completedNow)
                                SyncWorker.syncNow(context)
                                reload()
                                DailyPlanWidget.updateAll(context)
                            }.onFailure { message(it.message ?: "状态保存失败") }
                        }
                    },
                    onOpenTask = { task ->
                        detailId = task.taskId
                        screen = "detail"
                    },
                    onOpenSettings = { screen = "settings" },
                    onSync = { scope.launch { syncNow(showResult = true) } },
                    onAcknowledgeReminder = {
                        val active = evaluation?.reminder
                        if (active != null) {
                            scope.launch {
                                runCatching {
                                    repository.acknowledgeReminder(
                                        active.workDate,
                                        active.period.name.lowercase(),
                                    )
                                    ReminderNotifier.dismiss(context)
                                    reload()
                                }.onFailure {
                                    message(it.message ?: "已查看状态保存失败")
                                }
                            }
                        }
                    },
                )
            }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }
    }

    BackHandler(enabled = screen != "list") {
        screen = "list"
        detailId = null
    }
}

private fun repositorySnapshotSync(container: AppContainer) =
    com.dailyplan.shared.model.SyncMeta(0, null, null, null, null)
