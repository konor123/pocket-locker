package com.ju.pocketlocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * 화면 잠금용 접근성 서비스.
 *
 * GLOBAL_ACTION_LOCK_SCREEN은 전원 버튼을 누른 것과 같은 방식으로 잠그므로,
 * DevicePolicyManager.lockNow()와 달리 잠금 후에도 지문 잠금해제가 그대로 동작한다.
 * (lockNow()는 기기 관리자 잠금으로 취급되어 강력 인증(PIN/패턴)을 요구하게 됨)
 */
class PocketAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PocketAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** 화면 잠금 시도. 성공 시 true */
    fun lockScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
}
