package com.dailyplan.android

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.dailyplan.android.ui.DailyPlanApp
import com.dailyplan.android.ui.DailyPlanTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var networkCallbackRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            SyncWorker.syncNow(this@MainActivity)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as DailyPlanApplication).container
        setContent {
            DailyPlanTheme {
                DailyPlanApp(
                    container = container,
                    initialMode = intent.getStringExtra(EXTRA_MODE) ?: MODE_LIST,
                    initialTaskId = intent.getStringExtra(EXTRA_TASK_ID),
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        SyncWorker.syncNow(this)
        lifecycleScope.launch(Dispatchers.IO) {
            val container = (application as DailyPlanApplication).container
            ReminderNotifier.showIfDue(this@MainActivity, container)
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                .registerNetworkCallback(request, networkCallback)
            networkCallbackRegistered = true
        }
    }

    override fun onStop() {
        if (networkCallbackRegistered) {
            runCatching {
                getSystemService(ConnectivityManager::class.java)
                    .unregisterNetworkCallback(networkCallback)
            }
            networkCallbackRegistered = false
        }
        super.onStop()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_TASK_ID = "taskId"
        const val MODE_LIST = "list"
        const val MODE_TEXT = "text"
        const val MODE_RECORD = "record"
        const val MODE_DETAIL = "detail"
    }
}
