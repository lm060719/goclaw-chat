package xyz.limo060719.goclaw

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Hilt application entry point. Referenced by AndroidManifest `android:name=".GoClawApp"`.
 *
 * Also supplies WorkManager's [Configuration] so `@HiltWorker` workers (e.g. the approval-check
 * poller) can have their dependencies injected. The default WorkManager initializer is removed in
 * the manifest so this on-demand configuration is the one that takes effect.
 */
@HiltAndroidApp
class GoClawApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
