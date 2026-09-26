package org.opencode.mobile.account

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

/**
 * Отправляет deep link в Яндекс Музыку и держит её задачу в фокусе, пока идёт запуск.
 *
 * Зачем отдельная Activity, если `startActivity` умеет и сам:
 *
 * 1. Мост работает в фоне, у него нет видимого окна, и Android создаёт целевую Activity, но не
 *    выводит её задачу наверх. Яндекс принимает ссылку промежуточной `UrlActivity` с
 *    `LAUNCH_SINGLE_TASK`: она инстанцируется, но не получает `onResume`, разбор ссылки не
 *    доходит до конца. Ссылка выглядит доставленной, а плейлист не открывается.
 * 2. Система разрешает такой запуск, если у процесса есть видимое окно. Поэтому поднимаем
 *    прозрачную Activity и отправляем ссылку из её `onResume`.
 * 3. Закрыться сразу после отправки тоже нельзя: пока мы живы, чужая задача остаётся в фокусе.
 *    Стоит нам исчезнуть раньше, чем Яндекс дойдёт до экрана плейлиста, и система вернёт то,
 *    что было в фокусе до нас, - то есть главный экран, ради ухода с которого всё затевалось.
 *
 * Поэтому окно живёт до [release]: его зовёт плеер, когда запуск подтверждён или провален.
 * Страховка от зависшего плеера - [HOLD_LIMIT_MS], после которого окно закрывается само.
 *
 * Наследие от этого окна нулевое: [Theme.OpencodeMobile.Invisible] рисует прозрачный фон без
 * анимации, `noHistory` убирает его из истории, пустой `taskAffinity` держит подальше от нашей
 * задачи. Обзор доступности ему не мешает: сервис смотрит все окна и фильтрует по пакету, а
 * кнопку нажимает через `ACTION_CLICK` по узлу, а не тапом по координатам.
 */
class YandexPlaylistLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Отправка живёт в onResume, а не здесь: на момент onCreate окна ещё нет, и мы получили
        // бы ровно ту же отмену, ради устранения которой эта Activity и написана.
    }

    override fun onResume() {
        super.onResume()
        val target = intent?.getStringExtra(EXTRA_LINK)
        if (target.isNullOrBlank() || !target.startsWith("$SCHEME:")) {
            // Молча закрываемся: запускать посторонний адрес из своего имени - худшая идея.
            finish()
            return
        }
        if (sent) {
            // onResume приходит повторно после возврата из чужой задачи - второй раз не шлём.
            finish()
            return
        }
        sent = true
        holding.set(WeakReference(this))
        handler.postDelayed(::finish, HOLD_LIMIT_MS)
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(target))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setPackage(YandexPlaylistPlayer.PACKAGE),
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        holding.compareAndSet(WeakReference(this), null)
    }

    private val handler = Handler(Looper.getMainLooper())

    private var sent = false

    companion object {
        /** Ссылка, которую нужно открыть. Кладётся в интент, а не в статическое поле. */
        const val EXTRA_LINK = "playlist_link"

        private const val SLACK_MS = 10_000L

        private const val HOLD_LIMIT_MS = YandexPlaylistPlayer.WORST_CASE_MS + SLACK_MS

        private const val SCHEME = "yandexmusic"

        private val holding = AtomicReference<WeakReference<YandexPlaylistLaunchActivity>?>(null)

        /**
         * Отпустить чужую задачу: закрыть окно, которое её держит.
         *
         * Вызывается плеером в `finally` - и на успехе, и на любой ошибке, потому что забытое
         * окно оставило бы поверх Яндекс Музыки невидимую Activity на минуту.
         */
        fun release() {
            val held = holding.getAndSet(null)?.get() ?: return
            held.runOnUiThread {
                if (!held.isFinishing && !held.isDestroyed) held.finish()
            }
        }
    }
}
