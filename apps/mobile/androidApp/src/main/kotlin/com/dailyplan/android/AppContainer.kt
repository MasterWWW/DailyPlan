package com.dailyplan.android

import android.content.Context
import com.dailyplan.android.data.AndroidAttachmentStorage
import com.dailyplan.android.data.TokenStore
import com.dailyplan.shared.data.PlanRepository
import com.dailyplan.shared.db.DailyPlanDatabase
import com.dailyplan.shared.db.openDailyPlanDatabase
import com.dailyplan.shared.sync.SyncEngine
import com.dailyplan.shared.sync.SyncResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database: DailyPlanDatabase = openDailyPlanDatabase(appContext)
    val repository = PlanRepository(database)
    val tokenStore = TokenStore(appContext)
    val attachmentStorage = AndroidAttachmentStorage(appContext)
    val syncEngine = SyncEngine(
        repository = repository,
        tokenProvider = { tokenStore.token() },
        storage = attachmentStorage,
    )
    private val syncMutex = Mutex()

    suspend fun sync(): SyncResult = syncMutex.withLock {
        syncEngine.syncOnce()
    }
}
