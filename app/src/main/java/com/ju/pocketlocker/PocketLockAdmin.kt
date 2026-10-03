package com.ju.pocketlocker

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** 기기 관리자 리시버: 화면 잠금(lockNow) 권한을 위해 필요 */
class PocketLockAdmin : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, "기기 관리자 활성화됨: 주머니 잠금을 사용할 수 있습니다", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, "기기 관리자 비활성화됨: 주머니 잠금이 동작하지 않습니다", Toast.LENGTH_SHORT).show()
    }
}
