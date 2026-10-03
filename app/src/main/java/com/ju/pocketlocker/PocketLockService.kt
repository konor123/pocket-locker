package com.ju.pocketlocker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * 주머니 감지 포그라운드 서비스.
 *
 * 주머니 상태 판단 (3개 센서 융합):
 *  1. 근접 센서: 물체가 가까움 (주머니 안에서 다리에 닿음)
 *  2. 조도 센서: 임계값(lux)보다 어두움 (주머니 안은 어두움)
 *  3. 가속도 센서: 세로 방향 (|z| < 7.5) — 화면을 아래로 뒤집어 테이블에 둔 경우 제외
 *
 * 세 조건이 [lockDelayMs] 동안 계속 유지되면 DevicePolicyManager.lockNow() 로 화면을 끄고 잠금.
 * 주머니에서 꺼내면(근접 해제) 상태가 리셋되어 다음에 넣을 때 다시 잠금.
 */
class PocketLockService : Service(), SensorEventListener {

    companion object {
        const val ACTION_STOP = "com.ju.pocketlocker.ACTION_STOP"
        private const val CHANNEL_ID = "pocket_locker_monitor"
        private const val NOTIF_ID = 1
        private const val TAG = "PocketLockService"

        /** 평평하게 놓인 상태로 판단하는 z축 가속도 기준 (m/s^2). 중력 ≈ 9.8 */
        private const val FLAT_Z_THRESHOLD = 7.5f
    }

    private lateinit var prefs: Prefs
    private lateinit var sensorManager: SensorManager
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var powerManager: PowerManager
    private var adminComponent: ComponentName? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var hasLightSensor = false

    // 최근 센서 값 (센서 콜백·브로드캐스트 리시버 모두 메인 스레드에서 동작)
    private var proxNear = false
    private var hasProxEvent = false
    private var lux = Float.MAX_VALUE
    private var accelZ = 0f

    private var candidateSince = 0L
    private var lockedForInsertion = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                // 화면이 켜지면(=사용자가 꺼냄) 다음 주머니 삽입에 대비해 리셋
                candidateSince = 0L
                lockedForInsertion = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        sensorManager = getSystemService(SensorManager::class.java)
        devicePolicyManager = getSystemService(DevicePolicyManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        adminComponent = ComponentName(this, PocketLockAdmin::class.java)
        hasLightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) != null
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundWithNotification()
        startMonitoring()
        prefs.serviceEnabled = true
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "주머니 잠금 모니터링", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = Intent(this, PocketLockService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("주머니 잠금 동작 중")
            .setContentText("주머니에 넣으면 화면을 끄고 잠급니다")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "중지", stopPi)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun startMonitoring() {
        val prox = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (prox == null || accel == null) {
            Log.e(TAG, "필수 센서 없음 (proximity=$prox, accelerometer=$accel)")
            stopSelf()
            return
        }
        if (!hasLightSensor) Log.w(TAG, "조도 센서 없음: 근접+가속도만으로 판단합니다")
        sensorManager.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        // 화면이 꺼진 뒤에도 주머니에서 꺼내는 것을 감지하기 위한 부분 웨이크락
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocket-locker:monitor")
            .also { it.acquire() }
    }

    private fun stopMonitoring() {
        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) {
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        prefs.serviceEnabled = false
    }

    override fun onDestroy() {
        stopMonitoring()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                proxNear = event.values[0] < event.sensor.maximumRange
                hasProxEvent = true
            }
            Sensor.TYPE_LIGHT -> lux = event.values[0]
            Sensor.TYPE_ACCELEROMETER -> accelZ = event.values[2]
        }
        evaluate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun isPocketCandidate(): Boolean {
        if (!hasProxEvent || !proxNear) return false
        // 조도 센서가 있는 기기에서만 어둡기 조건 적용
        if (hasLightSensor && lux >= prefs.luxThreshold) return false
        // 평평하게 엎어/뉘어 놓은 상태(테이블 위) 제외: 주머니에선 보통 세로 방향
        if (abs(accelZ) > FLAT_Z_THRESHOLD) return false
        return true
    }

    private fun evaluate() {
        if (!powerManager.isInteractive) return // 화면이 이미 꺼져 있으면 할 일 없음
        val now = SystemClock.elapsedRealtime()
        if (isPocketCandidate()) {
            if (candidateSince == 0L) candidateSince = now
            if (!lockedForInsertion && now - candidateSince >= prefs.lockDelayMs) {
                lockNow()
            }
        } else {
            candidateSince = 0L
        }
        // 주머니에서 꺼내면(근접 해제) 다음 삽입 때 다시 잠그도록 리셋
        if (!proxNear) lockedForInsertion = false
    }

    private fun lockNow() {
        val comp = adminComponent ?: return
        if (!devicePolicyManager.isAdminActive(comp)) {
            Log.w(TAG, "기기 관리자 미활성화: 잠금을 생략합니다")
            return
        }
        try {
            devicePolicyManager.lockNow()
            lockedForInsertion = true
            Log.i(TAG, "주머니 감지 → 화면 잠금")
        } catch (e: SecurityException) {
            Log.e(TAG, "lockNow 실패", e)
        }
    }
}
