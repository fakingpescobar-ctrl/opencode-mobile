package org.opencode.mobile.installer

import org.json.JSONException
import org.json.JSONObject

/**
 * Провод Android-моста: разбор тела запроса, коды ошибок и форма ответа на запуск.
 *
 * Выделено не ради красоты, а ради тестируемости. Пока это жило внутри приватных обработчиков
 * [AppInstallBridge], проверить поведение можно было только живым сокетом на телефоне, и два
 * регресса доехали до устройства:
 *
 *  * `ok` в `/v1/apps/launch` означал «приложение запустилось» вместо «intent принят», и
 *    memory.js падал на каждом вызове;
 *  * кривой JSON в теле давал 500, то есть плохой вход выглядел как сбой сервера.
 *
 * Формы медиа-ответов лежат отдельно — [org.opencode.mobile.media.MediaWire].
 */
internal object AppInstallWire {
    /** Код и текст ошибки, которые мост отдаёт вызывающему. */
    data class HttpFailure(
        val code: Int,
        val message: String,
    )

    const val BAD_REQUEST = 400
    const val SERVER_ERROR = 500

    private const val DEFAULT_INVALID_REQUEST = "invalid request"
    private const val DEFAULT_MALFORMED_BODY = "malformed JSON body"
    private const val DEFAULT_SERVER_FAILURE = "request failed"

    /**
     * Тело запроса как объект.
     *
     * GET-запросы тела не имеют вовсе, и это «параметров нет», а не ошибка: раньше пустое тело
     * падало в 400, из-за чего `/v1/media/apps` был недостижим (fetch запрещает body на GET).
     * Не-пустое не-объектное тело — честная ошибка клиента.
     */
    fun jsonObject(body: String): JSONObject {
        if (body.isBlank()) return JSONObject()
        return runCatching { JSONObject(body) }.getOrElse {
            throw IllegalArgumentException("body must be a JSON object")
        }
    }

    /**
     * Какой HTTP-код положен исключению.
     *
     * [JSONException] здесь отдельной веткой намеренно: он потомок `RuntimeException`, но **не**
     * `IllegalArgumentException`, поэтому без своей ветки уезжает в общий случай и превращает
     * отсутствующее обязательное поле в 500. Это плохой вход, а не сбой сервера.
     */
    fun httpFailure(error: Throwable): HttpFailure =
        when (error) {
            is IllegalArgumentException -> HttpFailure(BAD_REQUEST, error.message ?: DEFAULT_INVALID_REQUEST)
            is JSONException -> HttpFailure(BAD_REQUEST, error.message ?: DEFAULT_MALFORMED_BODY)
            else -> HttpFailure(SERVER_ERROR, error.message ?: DEFAULT_SERVER_FAILURE)
        }

    /**
     * Ответ `/v1/apps/launch`.
     *
     * `ok` — литерал `true`: он означает «intent принят», а не «приложение запустилось». Второе
     * отсюда знать нельзя (см. [InstalledAppController]), а подставлять сюда признак жизни нельзя:
     * memory.js бросает на `!body.ok`, и приложение без подтверждения запуска падало бы на каждом
     * вызове, включая заведомо успешные.
     */
    fun launchResponse(app: LaunchedAppSnapshot): JSONObject =
        JSONObject()
            .put("ok", true)
            .put("app", app.toJson())
            .put("background_launch_allowed", app.backgroundLaunchAllowed)
            .put("hint", launchHint(app.backgroundLaunchAllowed))

    private fun launchHint(backgroundLaunchAllowed: Boolean): String =
        if (backgroundLaunchAllowed) {
            "to confirm the app really came up, read its own state (media session)"
        } else {
            "Android may block this start (BAL_BLOCK): grant SYSTEM_ALERT_WINDOW in " +
                "Diagnostics -> Фоновый запуск приложений, then start the app by hand"
        }

    private fun LaunchedAppSnapshot.toJson(): JSONObject =
        JSONObject()
            .put("package", packageName)
            .put("label", label)
            .put("component", componentName)
            .put("message", message)
            .put("background_launch_allowed", backgroundLaunchAllowed)
}
