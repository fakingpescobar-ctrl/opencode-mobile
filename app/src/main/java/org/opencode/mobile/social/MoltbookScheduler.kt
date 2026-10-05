package org.opencode.mobile.social

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Будильник для тиков. AlarmManager, а не WorkManager: WorkManager в проекте нет, а
 * тянуть новую зависимость ради одного периодического вызова — лишний вес в APK и
 * в оффлайн-сборке.
 *
 * `setInexactRepeating`, а не exact: два часа не требуют попадания в секунду, зато
 * система не будит процесс из Doze точно в срок — на батарее это честнее, а для
 * «заглянуть раз в пару часов» точность не нужна.
 */
internal object MoltbookScheduler {
    private const val TAG = "MoltbookSched"

    /** Раз в два часа: чаще платформа всё равно упрётся в rate limit и лимит трафика. */
    const val INTERVAL_MS = 2 * 60 * 60 * 1000L

    /** Интервал при попытке раньше срока — когда сервер ещё не поднят. */
    const val RETRY_MS = 15 * 60 * 1000L

    fun schedule(
        context: Context,
        delayMs: Long = INTERVAL_MS,
    ) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        manager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + delayMs,
            INTERVAL_MS,
            pendingIntent(context),
        )
        Log.i(TAG, "следующий тик через ${delayMs / 60000} мин")
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        manager.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, MoltbookAlarmReceiver::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    const val ACTION_TICK = "org.opencode.mobile.social.TICK"

    /** Код должен совпадать в [schedule] и в receiver'е — иначе alarm не сработает. */
    const val REQUEST_CODE = 4417
}
