package com.ju.pocketlocker

import android.content.ComponentName
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 킵얼라이브 워치독 (musicinfo 앱의 방식 참고).
 *
 * NotificationListenerService는 시스템이 직접 바인드하는 서비스라서,
 * 프로세스가 죽으면 시스템이 자동으로 다시 바인드(재시작)해준다.
 * 바인드될 때마다 모니터링 포그라운드 서비스가 살아있는지 확인하고
 * 꺼져 있으면 다시 시작한다.
 */
class PocketKeepAliveService : NotificationListenerService() {

    companion object {
        private const val TAG = "PocketKeepAlive"
    }

    override fun onCreate() {
        super.onCreate()
        ensureMonitoringService()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "시스템 바인드됨: 모니터링 서비스 상태 확인")
        LogStore.append(this, "킵얼라이브 연결됨")
        ensureMonitoringService()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "시스템 바인드 해제됨: 재바인드 요청")
        LogStore.append(this, "킵얼라이브 해제됨 → 재연결 요청")
        // 시스템에 재바인드를 요청한다
        try {
            requestRebind(ComponentName(this, PocketKeepAliveService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "재바인드 요청 실패", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureMonitoringService()
        return START_STICKY
    }

    /** 모니터링이 켜져 있었는데 서비스가 죽어 있으면 다시 시작한다 */
    private fun ensureMonitoringService() {
        if (!Prefs(this).serviceEnabled) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, PocketLockService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "모니터링 서비스 시작 실패", e)
        }
    }
}
