package com.ju.pocketlocker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 주머니 감지 포그라운드 서비스.
 *
 * 주머니 상태 판단 (근접 + 조도 2개 센서):
 *  1. 근접 센서: 물체가 가까움 (주머니 안에서 다리/옷감에 닿음) — 필수 조건
 *  2. 조도 센서: 임계값(lux)보다 어두움 — 보조 조건.
 *     주머니 안에서는 근접센서를 가리는 것이 빛도 함께 막으므로 두 신호가 항상 같이 간다.
 *     조도 센서가 없는 기기에서는 근접 센서만으로 판단한다.
 *
 * 설계 우선순위: 주머니 감지율(재현율) 최우선. 주머니가 아닐 때의 오작동(예: 테이블에
 * 엎어두기)은 어느 정도 허용한다. 주머니에서는 반드시 화면이 꺼져야 오조작을 막을 수 있다.
 *
 * 세 조건이 [lockDelayMs] 동안 계속 유지되면 접근성 서비스의
 * GLOBAL_ACTION_LOCK_SCREEN 으로 화면을 잠근다.
 * 전원 버튼과 같은 방식이라 잠금 후에도 지문 잠금해제가 동작한다.
 * (DevicePolicyManager.lockNow()는 강력 인증을 요구해서 지문 대신 PIN을 요구하게 됨)
 *
 * 주머니에서 꺼내면(근접 해제 또는 화면 켜짐) 상태가 리셋되어 다음에 넣을 때 다시 잠근다.
 *
 * 서비스 보호: 포그라운드 서비스 + START_STICKY + 배터리 최적화 제외 요청으로
 * 시스템/제조사에 의해 종료되지 않도록 한다. (안드로이드에 '중요 프로세스 지정' API는 없음)
 */
class PocketLockService : Service(), SensorEventListener {

    companion object {
        const val ACTION_STOP = "com.ju.pocketlocker.ACTION_STOP"
        private const val CHANNEL_ID = "pocket_locker_monitor"
        private const val NOTIF_ID = 1
        private const val TAG = "PocketLockService"
    }

    private lateinit var prefs: Prefs
    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private var hasLightSensor = false

    // 최근 센서 값 (센서 콜백·브로드캐스트 리시버 모두 메인 스레드에서 동작)
    private var proxNear = false
    private var hasProxEvent = false
    private var lux = Float.MAX_VALUE

    private var candidateLogged = false
    private var lockedForInsertion = false

    // 잠금 타이머: 근접/조도 센서는 값이 바뀔 때만 이벤트가 오므로,
    // 센서 이벤트 횟수에 의존하지 않고 Handler로 지연 잠금을 예약한다.
    private val handler = Handler(Looper.getMainLooper())
    private var lockPending = false
    private val lockRunnable = Runnable {
        lockPending = false
        // 예약 시점의 마지막 센서값으로 최종 판단 (변화가 없으면 후보 상태 유지)
        if (powerManager.isInteractive && isPocketCandidate() && !lockedForInsertion) {
            lockScreen()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                // 화면이 켜지면(=사용자가 꺼냄) 다음 주머니 삽입에 대비해 리셋
                cancelPendingLock()
                candidateLogged = false
                lockedForInsertion = false
            }
        }
    }

    /** 예약된 잠금을 취소한다 */
    private fun cancelPendingLock() {
        if (lockPending) {
            handler.removeCallbacks(lockRunnable)
            lockPending = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        sensorManager = getSystemService(SensorManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        hasLightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) != null
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LogStore.append(this, "모니터링 중지됨")
            stopMonitoring()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundWithNotification()
        startMonitoring()
        prefs.serviceEnabled = true
        LogStore.append(this, "모니터링 시작됨")
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_MIN: 무음·최소 표시. 포그라운드 서비스는 알림 없이 실행 불가라
            // 시스템상 필수인 최소 형태로만 유지하고, 상태 확인은 앱 내 로그로 한다.
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "주머니 잠금 모니터링", NotificationManager.IMPORTANCE_MIN)
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
        // 포그라운드 서비스는 시스템상 알림이 필수라 최소 형태로만 유지.
        // 상태 확인은 앱 내 설정 화면 하단의 로그로 한다.
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
        if (prox == null) {
            Log.e(TAG, "근접 센서 없음")
            LogStore.append(this, "시작 실패: 근접 센서가 없음")
            stopSelf()
            return
        }
        if (!hasLightSensor) Log.w(TAG, "조도 센서 없음: 근접 센서만으로 판단합니다")
        sensorManager.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun stopMonitoring() {
        cancelPendingLock()
        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) {
        }
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
        }
        evaluate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun isPocketCandidate(): Boolean {
        if (!hasProxEvent || !proxNear) return false
        // 조도 센서가 있는 기기에서만 어둡기 조건 적용.
        // 주머니 안에서는 근접센서를 가리는 것이 빛도 함께 막으므로 두 신호가 항상 같이 간다.
        if (hasLightSensor && lux >= prefs.luxThreshold) return false
        return true
    }

    private fun evaluate() {
        if (!powerManager.isInteractive) return // 화면이 이미 꺼져 있으면 할 일 없음
        if (isPocketCandidate()) {
            if (!candidateLogged) {
                candidateLogged = true
                LogStore.append(this, "주머니 후보 감지됨 (잠금 대기 중)")
            }
            if (!lockPending && !lockedForInsertion) {
                lockPending = true
                handler.postDelayed(lockRunnable, prefs.lockDelayMs)
            }
        } else {
            cancelPendingLock()
            candidateLogged = false
        }
        // 주머니에서 꺼내면(근접 해제) 다음 삽입 때 다시 잠그도록 리셋
        if (!proxNear) lockedForInsertion = false
    }

    private fun lockScreen() {
        val ok = PocketAccessibilityService.instance?.lockScreen() ?: false
        if (ok) {
            lockedForInsertion = true
            LogStore.append(this, "주머니 감지 → 화면 잠금")
            Log.i(TAG, "주머니 감지 → 화면 잠금")
        } else {
            LogStore.append(this, "잠금 실패: 접근성 서비스가 연결되지 않음")
            Log.w(TAG, "접근성 서비스 미연결: 잠금을 생략합니다")
        }
    }
}
