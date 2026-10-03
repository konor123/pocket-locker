package com.ju.pocketlocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** 재부팅 후 모니터링 서비스 자동 복원 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs(context).serviceEnabled) return
        ContextCompat.startForegroundService(context, Intent(context, PocketLockService::class.java))
    }
}
