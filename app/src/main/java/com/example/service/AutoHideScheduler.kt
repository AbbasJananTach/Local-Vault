package com.example.service

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules and manages Local Vault's Dual-Engine 24/7 Background Auto-Hide WorkManager jobs:
 * - Engine A: `ContentUriTrigger` on `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` and
 *   `MediaStore.Video.Media.EXTERNAL_CONTENT_URI` with a 5-second max delay, forming a
 *   self-renewing observer chain that wakes even if the app process is closed.
 * - Engine B: 15-minute `PeriodicWorkRequest` fallback for extra reliability across OEMs.
 */
object AutoHideScheduler {
    const val WORK_NAME_TRIGGER = "local_vault_autohide_media_trigger"
    const val WORK_NAME_PERIODIC = "local_vault_autohide_periodic"
    const val TAG_MEDIA_TRIGGER = "autohide_media_trigger"

    fun scheduleAll(context: Context) {
        scheduleMediaContentTrigger(context)
        schedulePeriodicFallback(context)
    }

    fun scheduleMediaContentTrigger(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentMaxDelay(5, TimeUnit.SECONDS)
                .build()

            val triggerRequest = OneTimeWorkRequestBuilder<AutoHideWorker>()
                .setConstraints(constraints)
                .addTag(TAG_MEDIA_TRIGGER)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME_TRIGGER,
                ExistingWorkPolicy.REPLACE,
                triggerRequest
            )
        } catch (_: Throwable) {
        }
    }

    fun schedulePeriodicFallback(context: Context) {
        try {
            val periodicRequest = PeriodicWorkRequestBuilder<AutoHideWorker>(15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
        } catch (_: Throwable) {
        }
    }

    fun cancelAll(context: Context) {
        try {
            val wm = WorkManager.getInstance(context.applicationContext)
            wm.cancelUniqueWork(WORK_NAME_TRIGGER)
            wm.cancelUniqueWork(WORK_NAME_PERIODIC)
        } catch (_: Throwable) {
        }
    }
}
