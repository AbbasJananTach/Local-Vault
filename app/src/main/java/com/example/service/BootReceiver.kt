package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.crypto.AutoHideKeyStoreHelper
import com.example.data.VaultPreferences

/**
 * Re-schedules 24/7 WorkManager Auto-Hide jobs (ContentUriTrigger + Periodic Fallback)
 * and revives `AutoHideService` automatically after device reboot or app update.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = VaultPreferences(context)
            val watchedFolders = prefs.watchedFolders.value
            if (prefs.autoHideEnabled.value && watchedFolders.isNotEmpty()) {
                AutoHideScheduler.scheduleAll(context)
                val sealedKey = AutoHideKeyStoreHelper.unsealMasterKeyForAutoHide(context)
                if (sealedKey != null) {
                    try {
                        val serviceIntent = Intent(context, AutoHideService::class.java).apply {
                            putExtra("WATCHED_PATHS", watchedFolders.toTypedArray())
                            putExtra("IS_DECOY", false)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    } catch (_: Throwable) {
                        // On Android 12+ if background FGS start is blocked, WorkManager Engine A & B still run reliably
                    }
                }
            }
        }
    }
}
