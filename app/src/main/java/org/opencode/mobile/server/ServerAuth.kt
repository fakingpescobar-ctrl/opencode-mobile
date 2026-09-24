package org.opencode.mobile.server

import android.content.Context
import android.util.Base64
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Общая авторизация для HTTP-клиентов приложения, ходящих на локальный
 * opencode serve (порт 4096). После включения OPENCODE_SERVER_PASSWORD
 * serve отдаёт 401 на все запросы без заголовка Authorization, поэтому
 * каждый клиент (ChatOverlay, WebView и т.п.) должен слать Basic-заголовок.
 *
 * Пароль берётся из того же SharedPreferences, который serve задаёт в env
 * при старте (см. OpencodeRuntime.startServe). Читаем один раз при старте
 * и кэшируем в поле password.
 */
object ServerAuth {
    /** Пароль локального сервера (24 hex-символа), null пока не прочитан. */
    @Volatile
    var password: String? = null
        private set

    private val lock = ReentrantLock()

    /** Заполняется при старте serve — единственная точка записи. */
    fun setPassword(pwd: String) {
        lock.withLock {
            password = pwd
        }
    }

    /**
     * Читает пароль из SharedPreferences (если serve уже сохранял его) — для
     * быстрых клиентов (WebView в MainActivity), которые стартуют раньше serve.
     * Если serve ещё не успел сохранить — вернёт null без побочных эффектов.
     */
    fun setPasswordFromPrefs(context: Context) {
        lock.withLock {
            if (password != null) return
            val pwd = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("password", null)
            if (pwd != null) password = pwd
        }
    }

    private const val PREFS_NAME = "opencode_server"

    /** true, когда пароль известен и клиент может слать авторизацию. */
    val available: Boolean
        get() = password != null

    /**
     * Готовый заголовок Authorization для HttpURLConnection:
     * Basic base64("opencode:" + password). Логин по умолчанию opencode
     * (см. middleware.ts и server.ts в opencode-src).
     */
    fun basicHeader(): String? {
        val pwd = password ?: return null
        val raw = "opencode:" + pwd
        return "Basic " + Base64.encodeToString(
            raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
    }
}