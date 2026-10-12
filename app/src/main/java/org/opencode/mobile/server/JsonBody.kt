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
