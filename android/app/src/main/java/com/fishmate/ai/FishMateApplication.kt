package com.fishmate.ai

import android.app.Application
import com.fishmate.ai.core.Log2
import com.fishmate.ai.data.repository.SyncManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class FishMateApplication : Application() {

    @Inject
    lateinit var syncManager: SyncManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log2.i(TAG, "FishMate ${BuildConfig.VERSION_NAME} starting (debug=${BuildConfig.DEBUG})")

        // Drain anything logged while offline last time, before the user even
        // opens a screen, so their catches reach the server as soon as there
        // is signal.
        appScope.launch {
            runCatching { syncManager.syncPending() }
                .onSuccess { if (it > 0) Log2.i(TAG, "replayed $it queued write(s) at launch") }
                .onFailure { Log2.d(TAG, "startup sync skipped: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "FishMateApp"
    }
}
