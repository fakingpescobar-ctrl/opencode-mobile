package org.opencode.mobile.server

import org.json.JSONArray
import org.json.JSONObject

/**
 * Разбор тела ответа в «объект или массив, иначе ничего».
 *
 * Отдельный файл не ради красоты: три слоя ответа ([ApiShape], [ApiMcp],
 * [ApiStatus]) пользуются одной и той же проверкой, и каждый обязан понимать
 * про неё одно: **неразобранное тело и разобранное — не одно и то же**.
 *
 * Почему нельзя доверять коду ответа. В v2 любой неизвестный путь отдаёт HTTP
 * 200 с HTML SPA-заглушкой, так что «сервер ответил» не значит «ответили тем,
 * что мы просили». `null` здесь — это сигнал «это не то», и потребитель обязан
 * трактовать его как отказ, а не как пустоту: иначе HTML от прокси выглядел бы
 * как «сессий нет».
 */
internal fun asJsonArray(raw: String?): JSONArray? =
    raw
        ?.trim()
        ?.takeIf { it.startsWith("[") }
        ?.let {
            try {
                JSONArray(it)
            } catch (_: Exception) {
                null
            }
        }

internal fun asJsonObject(raw: String?): JSONObject? =
    raw
        ?.trim()
        ?.takeIf { it.startsWith("{") }
        ?.let {
            try {
                JSONObject(it)
            } catch (_: Exception) {
                null
            }
        }

/** Тип служебного сообщения «ход закончился» в v2. */
internal const val IDLE_TYPE = "idle"


/**
 * Что говорит одно сообщение: true — ход идёт, false — закончен,
 * null — сообщение не показатель (`system`, мусор), искать дальше.
 *
 * Живёт здесь, а не в [ApiShape], потому что это разбор одного сообщения, а не
 * приведение ленты к виду v1 — и потому, что объекту [ApiShape] уже тесно в лимите
 * на число функций.
 */
internal fun asBusyVerdict(msg: JSONObject?): Boolean? {
    val obj = msg ?: return null
    // v1-сообщение узнаётся по `info`: type лежит не на верхнем уровне.
    val info = obj.optJSONObject("info")
    val type = obj.optString("type").ifBlank { info?.optString("role") ?: "" }
    val time = obj.optJSONObject("time") ?: info?.optJSONObject("time")
    return when (type) {
        "system" -> null
        IDLE_TYPE -> false
        "assistant" -> (time?.optLong("completed") ?: 0L) <= 0L
        // user или незнакомый тип: ход отправлен, ответа ещё нет.
        else -> true
    }
}

/**
 * Текст ошибки из поля `error`, которым opencode помечает провалившийся ход.
 *
 * Зачем. В v2 ход, который не получился, приходит assistant-сообщением с ПУСТЫМ
 * `content` и `finish:"error"`, а текст причины лежит в `error` (и продублирован
 * в `idle`-псевдо-сообщении). Если этот текст не показать, такой ход неотличим от
 * пустого, а пустой завершённый ход рендер отфильтровывает — пользователь видит
 * тишину вместо «модель устарела». На живом устройстве так и вышло: два отказа
 * подряд и пустой чат.
 *
 * `response` намеренно не разворачивается: там лежит тело ответа провайдера, в
 * котором для 410 повторяется то же самое.
 *
 * null — ошибки нет или нечего показать (пустой объект).
 */
internal fun asErrorText(error: JSONObject?): String? =
    error?.let { err ->
        val message = err.optString("message").ifBlank { err.optString("type") }
        if (message.isBlank()) {
            null
        } else {
            val status = err.optLong("status", 0L).takeIf { it > 0L }?.toString().orEmpty()
            val tail = listOf(err.optString("type"), status).filter { it.isNotBlank() }.joinToString(", ")
            "⚠ $message" + tail.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        }
    }
