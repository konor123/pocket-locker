package com.ju.pocketlocker

import android.content.ComponentName
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var tvA11y: TextView
    private lateinit var tvNls: TextView
    private lateinit var tvService: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvSensors: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnA11y: Button
    private lateinit var btnNls: Button
    private lateinit var btnToggle: Button
    private lateinit var btnBattery: Button
    private lateinit var btnClearLog: Button
    private lateinit var etLux: EditText
    private lateinit var etDelay: EditText
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)

        tvA11y = findViewById(R.id.tvA11y)
        tvNls = findViewById(R.id.tvNls)
        tvService = findViewById(R.id.tvService)
        tvBattery = findViewById(R.id.tvBattery)
        tvSensors = findViewById(R.id.tvSensors)
        tvLog = findViewById(R.id.tvLog)
        btnA11y = findViewById(R.id.btnA11y)
        btnNls = findViewById(R.id.btnNls)
        btnToggle = findViewById(R.id.btnToggle)
        btnBattery = findViewById(R.id.btnBattery)
        btnClearLog = findViewById(R.id.btnClearLog)
        etLux = findViewById(R.id.etLux)
        etDelay = findViewById(R.id.etDelay)
        btnSave = findViewById(R.id.btnSave)

        btnA11y.setOnClickListener { openAccessibilitySettings() }
        btnNls.setOnClickListener { openNotificationListenerSettings() }
        btnToggle.setOnClickListener { toggleService() }
        btnBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }
        btnSave.setOnClickListener { saveSettings() }
        btnClearLog.setOnClickListener {
            LogStore.clear(this)
            refreshLog()
            Toast.makeText(this, "로그를 지웠습니다", Toast.LENGTH_SHORT).show()
        }

        etLux.setText(prefs.luxThreshold.toString())
        etDelay.setText(prefs.lockDelayMs.toString())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, PocketAccessibilityService::class.java).flattenToString()
        val enabled =
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val expected = ComponentName(this, PocketMonitorService::class.java).flattenToString()
        val enabled =
            Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
                ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun isBatteryOptIgnored(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun refresh() {
        val a11yOk = isAccessibilityEnabled()
        tvA11y.text = if (a11yOk) "접근성 서비스: 활성화됨 ✓" else "접근성 서비스: 비활성화 ✗ (화면 잠금에 필요)"
        btnA11y.isEnabled = !a11yOk

        val nlsOk = isNotificationAccessEnabled()
        tvNls.text = if (nlsOk) "알림 접근: 허용됨 ✓" else "알림 접근: 비허용 ✗ (모니터링에 필요)"
        btnNls.isEnabled = !nlsOk

        // 모니터링 상태: pref(켜짐 설정) + NLS 바인드 여부로 표시
        val monOn = prefs.serviceEnabled
        val nlsConnected = PocketMonitorService.instance != null
        tvService.text = when {
            !monOn -> "모니터링: 꺼짐"
            nlsConnected -> "모니터링: 동작 중 ✓"
            else -> "모니터링: 켜짐 (시스템 바인드 대기 중…)"
        }
        btnToggle.text = if (monOn) "모니터링 중지" else "모니터링 시작"

        val battIgnored = isBatteryOptIgnored()
        tvBattery.text =
            if (battIgnored) "배터리 최적화: 제외됨 ✓" else "배터리 최적화: 적용 중 ✗ (백그라운드에서 죽을 수 있음)"
        btnBattery.isEnabled = !battIgnored

        val sm = getSystemService(SensorManager::class.java)
        val prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null
        val light = sm.getDefaultSensor(Sensor.TYPE_LIGHT) != null
        tvSensors.text = "센서: 근접 ${yn(prox)} · 조도 ${yn(light)}" +
            if (!prox) "\n※ 근접 센서가 없으면 동작하지 않습니다" else ""

        refreshLog()
    }

    private fun refreshLog() {
        tvLog.text = LogStore.read(this).ifEmpty { "로그가 없습니다" }
    }

    private fun yn(b: Boolean) = if (b) "있음" else "없음"

    private fun openNotificationListenerSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        Toast.makeText(this, "'주머니 잠금 모니터링'을 허용해주세요", Toast.LENGTH_LONG).show()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "설치된 앱에서 '주머니 잠금'을 켜주세요", Toast.LENGTH_LONG).show()
    }

    private fun requestIgnoreBatteryOptimizations() {
        // 제조사 배터리 최적화(도즈) 대상에서 제외 → 백그라운드에서 서비스가 죽지 않게
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun toggleService() {
        if (!isNotificationAccessEnabled()) {
            Toast.makeText(this, "모니터링을 위해 알림 접근을 허용하세요", Toast.LENGTH_LONG).show()
            return
        }
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "먼저 접근성 서비스를 활성화하세요", Toast.LENGTH_LONG).show()
            return
        }
        // NLS 바인딩은 시스템이 관리하므로, 여기서는 설정만 뒤집고
        // 바인드된 서비스 인스턴스에 직접 전달한다 (미바인드 시 onListenerConnected에서 pref로 복원)
        prefs.serviceEnabled = !prefs.serviceEnabled
        PocketMonitorService.instance?.setMonitoringEnabled(prefs.serviceEnabled)
        val msg = if (prefs.serviceEnabled) "모니터링 시작" else "모니터링 중지"
        LogStore.append(this, msg)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun saveSettings() {
        val lux = etLux.text.toString().toFloatOrNull()
        val delay = etDelay.text.toString().toLongOrNull()
        if (lux == null || lux < 0) {
            Toast.makeText(this, "조도 임계값을 올바르게 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }
        if (delay == null || delay < 300) {
            Toast.makeText(this, "잠금 대기 시간은 300ms 이상 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.luxThreshold = lux
        prefs.lockDelayMs = delay
        Toast.makeText(this, "설정 저장됨", Toast.LENGTH_SHORT).show()
    }
}
