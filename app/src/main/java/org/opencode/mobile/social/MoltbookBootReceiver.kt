package org.opencode.mobile.social

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Будильники не переживают перезагрузку: после ребута Android снимает все alarm'ы,
 * и без этого receiver'а автономия молча заканчивалась на первый же перезапуск телефона.
 *
 * Второе событие — `MY_PACKAGE_REPLACED`, обновление самого приложения: оно снимает
 * alarm'ы точно так же. Раньше его не слушали, поэтому автономия умирала на каждой
 * установке новой версии и оживала только после того, как пользователь откроет
 * приложение руками. Если обновление пришло, пока телефон был выключен, BOOT_COMPLETED
 * мог и не случиться — то есть гонка, где автономия не восстанавливалась никогда.
 */
class MoltbookBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i(TAG, "$action — тик возвращается в расписание")
        // ensureScheduled, а не schedule: снимает alarm только если его реально
        // нет, и не растягивает уже выбранный агентом интервал.
        MoltbookScheduler.ensureScheduled(context.applicationContext)
    }

    private companion object {
        const val TAG = "MoltbookBoot"
    }
}
