package com.tgwrist.app.family

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Настройка семейного режима через adb (receiver не экспортирован, но
 * `adb shell am broadcast -n com.tgwrist.app/.family.FamilyModeReceiver ...`
 * из shell доставляется):
 *
 *   -a com.tgwrist.app.FAMILY_SET --ez enabled true \
 *       --es trusted "123456789,987654321" --ei auto_answer_sec 20 --es keyword "/callme"
 *   -a com.tgwrist.app.FAMILY_GET      (текущие настройки в logcat, тег FamilyMode)
 */
class FamilyModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SET -> {
                if (intent.hasExtra("enabled")) FamilyMode.enabled = intent.getBooleanExtra("enabled", false)
                intent.getStringExtra("trusted")?.let { s ->
                    FamilyMode.setTrustedUserIds(s.split(',').mapNotNull { it.trim().toLongOrNull() })
                }
                if (intent.hasExtra("auto_answer_sec")) FamilyMode.autoAnswerDelaySec = intent.getIntExtra("auto_answer_sec", 20)
                intent.getStringExtra("keyword")?.let { FamilyMode.callbackKeyword = it }
                Log.i(TAG, "Updated: ${FamilyMode.describe()}")
            }
            ACTION_GET -> Log.i(TAG, "Current: ${FamilyMode.describe()}")
        }
    }

    companion object {
        private const val TAG = "FamilyMode"
        const val ACTION_SET = "com.tgwrist.app.FAMILY_SET"
        const val ACTION_GET = "com.tgwrist.app.FAMILY_GET"
    }
}
