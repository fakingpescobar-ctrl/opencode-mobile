package org.opencode.mobile.social

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Будильники не переживают перезагрузку: после ребута Android снимает все alarm'ы,
 * и без этого receiver'а автономия молча заканчивалась на первый же перезапуск телефона.
 */
class MoltbookBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "телефон перезагружен — тик возвращается в расписание")
        MoltbookScheduler.schedule(context.applicationContext)
    }

    private companion object {
        const val TAG = "MoltbookBoot"
    }
}
