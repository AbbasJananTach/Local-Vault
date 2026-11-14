package com.example.service

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.crypto.AutoHideKeyStoreHelper
import com.example.data.VaultPreferences
import com.example.data.VaultStorage

/**
 * Dual-Engine Background Auto-Hide Worker powered by WorkManager.
 *
 * Runs cleanly even when Local Vault has been closed/swiped from Recents or after device reboot:
 * 1. Engine A (ContentUriTrigger): Wakes within seconds when MediaStore Images/Videos change.
 * 2. Engine B (Periodic Fallback): Wakes every 15 minutes to catch any OEM-suppressed file saves.
 *
 * All folder sweeps run inside `VaultRepository.vaultMutex` via `VaultStorage.sweepWatchedFoldersNow`
 * so background sweeps never race or create duplicate vault entries.
 */
class AutoHideWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = VaultPreferences(applicationContext)
        val isTriggerWork = tags.contains(AutoHideScheduler.TAG_MEDIA_TRIGGER)

        try {
            if (!prefs.autoHideEnabled.value) {
                return Result.success()
            }

            val watchedFolders = prefs.watchedFolders.value
            if (watchedFolders.isEmpty()) {
                return Result.success()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                return Result.retry()
            }

            val masterKey = AutoHideKeyStoreHelper.unsealMasterKeyForAutoHide(applicationContext)
                ?: return Result.success()

            val vaultStorage = VaultStorage(applicationContext)
            vaultStorage.sweepWatchedFoldersNow(
                watchedPaths = watchedFolders,
                masterKey = masterKey,
                isDecoy = false
            )
        } catch (_: Throwable) {
            return Result.retry()
        } finally {
            // Engine A continuous self-renewing chain: re-register ContentUriTrigger for the next media event
            if (isTriggerWork && prefs.autoHideEnabled.value && prefs.watchedFolders.value.isNotEmpty()) {
                AutoHideScheduler.scheduleMediaContentTrigger(applicationContext)
            }
        }

        return Result.success()
    }
}
