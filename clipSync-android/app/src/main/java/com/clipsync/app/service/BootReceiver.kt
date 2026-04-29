package com.clipsync.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.clipsync.app.core.FileLogger

/**
 * BroadcastReceiver that starts the ClipboardService on device boot.
 * Handles BOOT_COMPLETED and MY_PACKAGE_REPLACED events.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                FileLogger.d(TAG, "Boot/Package replacement received, starting service")
                startClipboardService(context)
            }
        }
    }

    private fun startClipboardService(context: Context) {
        val serviceIntent = Intent(context, ClipboardService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
