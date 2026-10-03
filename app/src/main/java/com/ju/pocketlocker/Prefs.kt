package com.ju.pocketlocker

import android.content.Context
import androidx.core.content.edit

/** 앱 설정 (SharedPreferences 래퍼) */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("pocket_locker", Context.MODE_PRIVATE)

    /** 주머니로 판단하는 조도 임계값 (lux). 이 값보다 어두워야 함 */
    var luxThreshold: Float
        get() = sp.getFloat("lux_threshold", 10f)
        set(v) = sp.edit { putFloat("lux_threshold", v) }

    /** 주머니 상태가 이 시간(ms)만큼 지속되면 잠금 */
    var lockDelayMs: Long
        get() = sp.getLong("lock_delay_ms", 1200L)
        set(v) = sp.edit { putLong("lock_delay_ms", v) }

    /** 모니터링 서비스가 켜져 있었는지 (재부팅 후 복원용) */
    var serviceEnabled: Boolean
        get() = sp.getBoolean("service_enabled", false)
        set(v) = sp.edit { putBoolean("service_enabled", v) }
}
