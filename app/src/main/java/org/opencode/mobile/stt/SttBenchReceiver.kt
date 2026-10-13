package org.opencode.mobile.stt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Отладочный вход для распознавания: кладёт в очередь [WhisperTranscribeService]
 * не микрофон, а WAV-файл. Нужен, чтобы гонять замеры скорости и точности
 * автономно, без человека перед микрофоном.
 *
 * Запуск с ПК:
 * ```
 * adb shell am broadcast -n org.opencode.mobile.debug/org.opencode.mobile.stt.SttBenchReceiver \
 *   -a org.opencode.mobile.STT_BENCH \
 *   -e file /data/local/tmp/bench/clip.wav \
 *   -e model base -e engine whisper
 * ```
 * Результат приходит в logcat под тегом `VOICE`:
 * `worker: задача #N, ... модель=..., движок=...` и `worker: результат #N: '...'`
 *
 * Именно `-n`: broadcast только с action до приёмника не доходит (проверено —
 * `am broadcast` завершается за 0 мс, ни одного вызова), а с явным компонентом
 * срабатывает мгновенно.
 *
 * Работа делается НЕ здесь, а в сервисе: у BroadcastReceiver через goAsync
 * примерно 10 секунд, а turbo считает дольше — первая же попытка дала ANR
 * «Timeout receiver in proc». Приёмник только передаёт задание и выходит.
 *
 * Работает ТОЛЬКО в debug-сборке: приложение ещё и HTTP-сервер поднимает на
 * 4096, а экспортированный приёмник, читающий произвольный файл, — дыра.
 */
class SttBenchReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val service = serviceIntentFor(context, intent)
        if (service != null) context.startForegroundService(service)
    }

    /**
     * Задание для сервиса либо null, если это не наш broadcast / не debug / нет файла.
     * Сделано одним `when` без ранних возвратов: detekt держит лимит ReturnCount = 2,
     * а проверок тут три.
     */
    private fun serviceIntentFor(
        context: Context,
        intent: Intent,
    ): Intent? {
        val path = intent.getStringExtra(EXTRA_FILE).orEmpty()
        return when {
            intent.action != ACTION -> null
            !org.opencode.mobile.BuildConfig.DEBUG -> {
                Log.w(TAG, "ignored: not a debug build")
                null
            }

            path.isBlank() -> {
                Log.w(TAG, "no '$EXTRA_FILE' extra")
                null
            }

            else ->
                Intent(context, WhisperTranscribeService::class.java)
                    .setAction(ACTION)
                    .putExtra(EXTRA_FILE, path)
                    .putExtra(EXTRA_MODEL, intent.getStringExtra(EXTRA_MODEL))
                    .putExtra(EXTRA_ENGINE, intent.getStringExtra(EXTRA_ENGINE))
        }
    }

    companion object {
        const val TAG = "STT_BENCH"
        const val ACTION = "org.opencode.mobile.STT_BENCH"
        const val EXTRA_FILE = "file"
        const val EXTRA_MODEL = "model"
        const val EXTRA_ENGINE = "engine"
    }
}
