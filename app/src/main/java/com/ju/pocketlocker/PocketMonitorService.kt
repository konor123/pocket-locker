package com.ju.pocketlocker

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * 주머니 감지 모니터링 서비스 (musicinfo 앱의 방식 참고).
 *
 * NotificationListenerService는 시스템이 직접 바인드하는 서비스라서
 * 포그라운드 알림 없이도 프로세스 우선순위가 시스템급으로 유지되고,
 * 프로세스가 죽으면 시스템이 자동으로 다시 바인드(재시작)해준다.
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
 * 2단계 동작:
 *  1단계: 주머니 후보가 screenOffDelayMs(기본 500ms) 지속되면
 *         PROXIMITY_SCREEN_OFF_WAKE_LOCK으로 화면만 끈다 (전화 받을 때와 같은 방식, 잠금은 안 됨).
 *  2단계: 화면이 꺼진 후 lockDelayMs(기본 1000ms)가 지나면 접근성 서비스의
 *         GLOBAL_ACTION_LOCK_SCREEN으로 잠근다 (전원 버튼과 같은 방식이라 지문 잠금해제 유지).
 *  1초 안에 꺼내면 잠금 없이 화면만 다시 켜진다 (유예 기간).
 *
 * 통화 중에는 주머니로 판단하지 않는다 (귀에 댄 상태가 후보 조건과 동일하므로).
 * 화면 꺼짐은 디스플레이만 끄는 것으로, CPU를 재우거나 깨우지 않는다.
 * 이후 동작은 전원 버튼을 누른 것과 동일하므로 다른 실행 중 앱에 영향을 주지 않는다.
 *
 * 주머니에서 꺼내면(근접 해제 또는 화면 켜짐) 상태가 리셋되어 다음에 넣을 때 다시 동작한다.
 */
class PocketMonitorService : NotificationListenerService(), SensorEventListener {

    companion object {
        private const val TAG = "PocketMonitor"

        @Volatile
        var instance: PocketMonitorService? = null
            private set
    }

    private lateinit var prefs: Prefs
    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private lateinit var audioManager: AudioManager
    private var hasLightSensor = false
    private var monitoringActive = false

    // 최근 센서 값 (센서 콜백·브로드캐스트 리시버 모두 메인 스레드에서 동작)
    private var proxNear = false
    private var hasProxEvent = false
    private var lux = Float.MAX_VALUE

    private var candidateLogged = false
    private var lockedForInsertion = false

    // 2단계 상태
    private var screenOffPending = false // 1단계 타이머 예약됨
    private var lockPending = false // 2단계 타이머 예약됨
    private var screenForcedOff = false // 근접 웨이크락으로 화면을 끈 상태
    private var proximityWakeLock: PowerManager.WakeLock? = null

    private val handler = Handler(Looper.getMainLooper())

    /** 1단계: 후보가 screenOffDelayMs 지속되면 화면만 끈다 */
    private val screenOffRunnable = Runnable {
        screenOffPending = false
        if (!isPocketCandidate() || lockedForInsertion) return@Runnable
        if (powerManager.isInteractive) {
            acquireProximityWakeLock()
            screenForcedOff = true
            LogStore.append(this, "화면 꺼짐 (잠금 대기 중)")
            Log.i(TAG, "화면 꺼짐 (잠금 대기 중)")
        } else {
            // 이미 꺼져 있음 (화면 타임아웃 등) — 꺼짐 단계로 간주하고 잠금 타이머로
            LogStore.append(this, "화면 꺼짐 감지 (잠금 대기 중)")
        }
        lockPending = true
        handler.postDelayed(lockRunnable, prefs.lockDelayMs)
    }

    /** 2단계: 화면이 꺼진 후 lockDelayMs가 지나면 잠금 */
    private val lockRunnable = Runnable {
        lockPending = false
        if (!isPocketCandidate() || lockedForInsertion) return@Runnable
        // 화면이 켜져 있으면 잠그지 않음 (유예 기간 안에 꺼낸 경우)
        if (!screenForcedOff && powerManager.isInteractive) return@Runnable
        if (lockScreen()) {
            if (screenForcedOff) {
                releaseProximityWakeLock()
                screenForcedOff = false
            }
        }
        // 잠금 실패 시 화면은 계속 꺼진 상태로 유지 (오조작 방지). 꺼내면 해제됨.
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                // 화면이 켜지면(=사용자가 꺼냄) 다음 주머니 삽입에 대비해 리셋
                cancelTimers()
                candidateLogged = false
                lockedForInsertion = false
                if (screenForcedOff) {
                    releaseProximityWakeLock()
                    screenForcedOff = false
                }
            }
        }
    }

    /** 예약된 타이머를 모두 취소한다 */
    private fun cancelTimers() {
        if (screenOffPending) {
            handler.removeCallbacks(screenOffRunnable)
            screenOffPending = false
        }
        if (lockPending) {
            handler.removeCallbacks(lockRunnable)
            lockPending = false
        }
    }

    /** 화면만 끄는 근접 웨이크락 (전화 수신 시와 같은 방식). 화면이 꺼져도 잠그지는 않는다. */
    private fun acquireProximityWakeLock() {
        if (proximityWakeLock?.isHeld == true) return
        proximityWakeLock = powerManager.newWakeLock(
            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "pocket-locker:screen-off"
        ).also { it.acquire() }
    }

    private fun releaseProximityWakeLock() {
        try {
            proximityWakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        proximityWakeLock = null
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        sensorManager = getSystemService(SensorManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
        hasLightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) != null
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "시스템 바인드됨")
        LogStore.append(this, "모니터링 서비스 연결됨")
        // 이전에 켜져 있었으면(또는 재바인드) 모니터링 복원
        setMonitoringEnabled(prefs.serviceEnabled)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "시스템 바인드 해제됨: 재바인드 요청")
        LogStore.append(this, "모니터링 서비스 해제됨 → 재연결 요청")
        stopMonitoring()
        if (instance === this) instance = null
        try {
            requestRebind(ComponentName(this, PocketMonitorService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "재바인드 요청 실패", e)
        }
    }

    override fun onDestroy() {
        stopMonitoring()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    /** 모니터링 켜기/끄기 (설정 화면 토글에서 호출, 중복 호출 안전) */
    fun setMonitoringEnabled(enabled: Boolean) {
        if (enabled) startMonitoring() else stopMonitoring()
    }

    private fun startMonitoring() {
        if (monitoringActive) return
        val prox = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (prox == null) {
            Log.e(TAG, "근접 센서 없음")
            LogStore.append(this, "시작 실패: 근접 센서가 없음")
            prefs.serviceEnabled = false
            return
        }
        if (!hasLightSensor) Log.w(TAG, "조도 센서 없음: 근접 센서만으로 판단합니다")
        sensorManager.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        monitoringActive = true
        LogStore.append(this, "모니터링 시작됨")
    }

    private fun stopMonitoring() {
        cancelTimers()
        if (screenForcedOff) {
            releaseProximityWakeLock()
            screenForcedOff = false
        }
        if (monitoringActive) {
            try {
                sensorManager.unregisterListener(this)
            } catch (_: Exception) {
            }
            monitoringActive = false
            LogStore.append(this, "모니터링 중지됨")
        }
    }

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
        // 통화 중에는 주머니로 판단하지 않음 (전화 앱의 화면 제어를 방해하지 않기 위해).
        // 귀에 댄 상태가 주머니 후보 조건(근접+어두움)과 동일하기 때문.
        if (inCall()) return false
        if (!hasProxEvent || !proxNear) return false
        // 조도 센서가 있는 기기에서만 어둡기 조건 적용.
        // 주머니 안에서는 근접센서를 가리는 것이 빛도 함께 막으므로 두 신호가 항상 같이 간다.
        if (hasLightSensor && lux >= prefs.luxThreshold) return false
        return true
    }

    /** 통화 중 여부 (권한 불필요). 통화 중 귀에 댄 상태는 주머니와 구분이 안 되므로 제외한다. */
    private fun inCall(): Boolean {
        val mode = audioManager.mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun evaluate() {
        if (!powerManager.isInteractive) {
            // 화면 꺼짐 상태: 꺼내기(근접 해제)만 처리한다.
            // (근접 센서는 화면이 꺼져도 동작하므로 꺼내기를 감지할 수 있다)
            if (screenForcedOff && !proxNear) {
                releaseProximityWakeLock()
                screenForcedOff = false
                cancelTimers()
                candidateLogged = false
                LogStore.append(this, "주머니에서 꺼냄 → 화면 켜짐")
            }
            return
        }
        if (isPocketCandidate() && !lockedForInsertion) {
            if (!candidateLogged) {
                candidateLogged = true
                LogStore.append(this, "주머니 후보 감지됨 (화면 꺼짐 대기 중)")
            }
            if (!screenOffPending && !screenForcedOff && !lockPending) {
                screenOffPending = true
                handler.postDelayed(screenOffRunnable, prefs.screenOffDelayMs)
            }
        } else {
            cancelTimers()
            candidateLogged = false
            if (screenForcedOff) {
                releaseProximityWakeLock()
                screenForcedOff = false
            }
        }
        // 주머니에서 꺼내면(근접 해제) 다음 삽입 때 다시 잠그도록 리셋
        if (!proxNear) lockedForInsertion = false
    }

    /** 화면 잠금 시도. 성공 시 true */
    private fun lockScreen(): Boolean {
        val ok = PocketAccessibilityService.instance?.lockScreen() ?: false
        if (ok) {
            lockedForInsertion = true
            LogStore.append(this, "주머니 감지 → 화면 잠금")
            Log.i(TAG, "주머니 감지 → 화면 잠금")
        } else {
            LogStore.append(this, "잠금 실패: 접근성 서비스가 연결되지 않음")
            Log.w(TAG, "접근성 서비스 미연결: 잠금을 생략합니다")
        }
        return ok
    }
}
