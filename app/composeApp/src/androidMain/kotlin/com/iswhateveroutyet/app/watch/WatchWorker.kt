package com.iswhateveroutyet.app.watch

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.iswhateveroutyet.app.data.WhateverRepository
import com.iswhateveroutyet.app.push.notifyRelease
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import java.util.concurrent.TimeUnit
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Android's no-Firebase notification path: a periodic WorkManager job (only scheduled when the
 * app was built without FCM config) that polls the site data and posts system notifications for
 * subscribed flips — same shared diff logic as the desktop tray watcher, but with the baseline
 * persisted in Settings because each run is a fresh process. The first ever run only records the
 * baseline, so installing the app never notifies.
 */
class WatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = Settings()
        val client = HttpClient()
        return try {
            val data = WhateverRepository(client).load()
            val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
            val states = computeStates(data.items, today)
            val baseline = settings.getStringOrNull(BASELINE_KEY)?.let {
                try {
                    Json.decodeFromString<Map<String, OutState>>(it)
                } catch (e: Exception) {
                    null
                }
            }
            if (baseline != null) {
                diffChanges(baseline, data.items, states, watchTopics(settings), today)
                    .forEach { change ->
                        notifyRelease(applicationContext, NOTIFY_TITLE, change.message, change.item.id)
                    }
            }
            settings.putString(BASELINE_KEY, Json.encodeToString(states))
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        } finally {
            client.close()
        }
    }

    companion object {
        const val BASELINE_KEY = "watchBaseline"
        private const val WORK_NAME = "release-watch"

        /** ~Matches the checker's 30-minute cron cadence; KEEP means re-calling is a no-op. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WatchWorker>(30, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .build(),
            )
        }
    }
}
