package com.grokadile

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.grokadile.core.logging.CrashHandler
import com.grokadile.worker.AgentWorkScheduler
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

/**
 * App entry point. Bootstraps Hilt, installs the persistent crash handler,
 * provides the Hilt-aware WorkManager configuration (the default initializer is
 * disabled in the manifest), and schedules the resilience heartbeat.
 */
@HiltAndroidApp
class GrokadileApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var crashHandler: CrashHandler

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        crashHandler.install()
        AgentWorkScheduler.schedule(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .build()
}
