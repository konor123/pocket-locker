package com.ju.pocketlocker

import android.Manifest
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private lateinit var tvAdmin: TextView
    private lateinit var tvService: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvSensors: TextView
    private lateinit var btnAdmin: Button
    private lateinit var btnToggle: Button
    private lateinit var btnBattery: Button
    private lateinit var etLux: EditText
    private lateinit var etDelay: EditText
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        dpm = getSystemService(DevicePolicyManager::class.java)
        adminComponent = ComponentName(this, PocketLockAdmin::class.java)

        tvAdmin = findViewById(R.id.tvAdmin)
        tvService = findViewById(R.id.tvService)
        tvBattery = findViewById(R.id.tvBattery)
        tvSensors = findViewById(R.id.tvSensors)
        btnAdmin = findViewById(R.id.btnAdmin)
        btnToggle = findViewById(R.id.btnToggle)
        btnBattery = findViewById(R.id.btnBattery)
        etLux = findViewById(R.id.etLux)
        etDelay = findViewById(R.id.etDelay)
        btnSave = findViewById(R.id.btnSave)

        btnAdmin.setOnClickListener { requestAdmin() }
        btnToggle.setOnClickListener { toggleService() }
        btnBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }
        btnSave.setOnClickListener { saveSettings() }

        etLux.setText(prefs.luxThreshold.toString())
        etDelay.setText(prefs.lockDelayMs.toString())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun isAdminActive() = dpm.isAdminActive(adminComponent)

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val mgr = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return mgr.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == PocketLockService::class.java.name }
    }

    private fun refresh() {
        val adminOk = isAdminActive()
        tvAdmin.text = if (adminOk) "기기 관리자: 활성화됨 ✓" else "기기 관리자: 비활성화 ✗ (화면 잠금에 필요)"
        btnAdmin.isEnabled = !adminOk

        val running = isServiceRunning()
        tvService.text = if (running) "모니터링: 동작 중" else "모니터링: 중지됨"
        btnToggle.text = if (running) "모니터링 중지" else "모니터링 시작"

        val battIgnored = isBatteryOptIgnored()
        tvBattery.text = if (battIgnored) "배터리 최적화: 제외됨 ✓" else "배터리 최적화: 적용 중 ✗ (백그라운드에서 죽을 수 있음)"
        btnBattery.isEnabled = !battIgnored

        val sm = getSystemService(SensorManager::class.java)
        val prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null
        val light = sm.getDefaultSensor(Sensor.TYPE_LIGHT) != null
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        tvSensors.text = "센서: 근접 ${yn(prox)} · 조도 ${yn(light)} · 가속도 ${yn(accel)}" +
            if (!prox || !accel) "\n※ 근접·가속도 센서가 없으면 동작하지 않습니다" else ""
    }

    private fun yn(b: Boolean) = if (b) "있음" else "없음"

    private fun isBatteryOptIgnored(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        // 제조사 배터리 최적화(도즈) 대상에서 제외 → 백그라운드에서 서비스가 죽지 않게
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun requestAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "주머니에 넣었을 때 화면을 끄고 잠그기 위해 기기 관리자 권한이 필요합니다."
            )
        }
        startActivity(intent)
    }

    private fun toggleService() {
        if (isServiceRunning()) {
            val stop = Intent(this, PocketLockService::class.java)
                .setAction(PocketLockService.ACTION_STOP)
            startService(stop)
            refresh()
            return
        }
        if (!isAdminActive()) {
            Toast.makeText(this, "먼저 기기 관리자 권한을 활성화하세요", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
            )
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, PocketLockService::class.java))
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

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            toggleService()
        }
    }
}
