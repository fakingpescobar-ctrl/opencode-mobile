package org.opencode.mobile.ui

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.opencode.mobile.server.LocalOpenCodeClient
import java.time.Instant
import java.time.ZoneOffset

/**
 * Счётчик расхода Zen-квоты (модель `opencode/big-pickle`) за текущие UTC-сутки.
 *
 * ГДЕ БЕРЁМ ЦИФРУ. Сервер opencode квоту не отдаёт: ни одного эндпоинта про
 * usage/limits/billing в его API нет (проверено по схеме `/doc`). Остаётся одно —
 * посчитать ответы модели в лентах чата. Считаем ровно как оверлей `dev-quota` на ПК
 * (`zen_usage.py`): assistant-сообщения с `providerID=opencode` / `modelID=big-pickle`.
 *
 * ПОЧЕМУ НЕ ИЗ БАЗЫ СЕРВЕРА (проверено на живом сервере). База `opencode.db` лежит в
 * приватной директории приложения, доступ к ней прямой, и чтение seemed очевидным —
 * но она НЕ обновляется: при активной работе сервера последняя запись в `message`
 * оставалась от 29 сентября, тогда как сервер отвечал на запросы и отдавал свежую
 * ленту через API. Держит данные сервер в памяти и на диск сбрасывает редко. Счётчик
 * по такой базе показывал бы «0» при реальном расходе — то есть врал бы в ту же
 * сторону, что и вечный спиннер в самом начале этой задачи. Поэтому единственный
 * честный источник — API лент.
 *
 * ГРАНИЦА СУТОК — UTC-полночь, как у сервера Zen. У пользователя UTC+5, поэтому
 * новый счётчик начинается в 03:00 по местному времени. Это не баг: день в квоте
 * считается по UTC, и по местному времени счётчик врал бы 5 часов в сутки.
 *
 * ЧЕСТНЫЕ ОГРАНИЧЕНИЯ (пользователю показываем, а не прячем):
 *  - Считаются сессии, обновлённые сегодня. Если сервер подзабудет старую сессию,
 *    чьи ответы ушли сегодня, её расход не попадёт в цифру.
 *  - Счётчик — «сколько сожгли мы», а не «сколько осталось у провайдера»: других
 *    клиентов (например, ПК) он не видит.
 *  - [DAILY_LIMIT] — наблюдаемая величина, а не константа с сервера.
 */
internal object ZenQuota {
    private const val TAG = "ZenQuota"

    /** Провайдер бесплатного Zen-тарифа (opencode Zen, не Ollama Cloud). */
    const val PROVIDER = "opencode"

    /** Модель, расход которой показываем в шапке. */
    const val MODEL = "big-pickle"

    /**
     * Наблюдаемый дневной лимит запросов. Сервер его не публикует — значение взято
     * из замеров пользователя (доходило до ~2261 req/день), округлено вверх.
     * Поэтому шкала показывает «порядок величины», а не точную квоту.
     */
    const val DAILY_LIMIT = 2500

    /** Сколько минут держим замер в кэше до следующего полного обхода сессий. */
    const val REFRESH_MINUTES = 60

    /**
     * Последний успешный замер и флаг его свежести.
     *
     * Кэш нужен по двум причинам. Первая — обход сессий стоит HTTP-запросов, а чаты
     * поллинятся 2.5 раза в секунду. Вторая, важнее: при неудачном обходе мы обязаны
     * показать ПРОШЛОЕ значение, а не 0. Иначе шкала дёргалась бы вниз при первом же
     * сетевом сбое и читалась как «квота восстановилась».
     */
    @Volatile
    private var lastUsed: Int = 0

    @Volatile
    private var lastReadOk: Boolean = false

    /**
     * Начало текущих UTC-суток в миллисекундах.
     *
     * По UTC, потому что по UTC же живёт дневная квота. Локальное время не
     * используется: при UTC+5 смена суток по местному времени сдвинула бы счётчик
     * на 5 часов относительно серверного.
     */
    fun dayStartMs(nowMs: Long): Long =
        Instant
            .ofEpochMilli(nowMs)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

    /**
     * Расход за UTC-день, содержащий [nowMs], посчитанный в одной ленте.
     *
     * Чистая функция (без сети и без Context) — её удобно покрыть JVM-тестом,
     * тогда как [refreshAcrossSessions] требует сервера.
     */
    fun countInLane(
        rawJson: String,
        nowMs: Long,
    ): Int {
        val since = dayStartMs(nowMs)
        val arr = parseArray(rawJson) ?: return 0
        var used = 0
        for (i in 0 until arr.length()) {
            val info = infoOf(arr, i)
            if (info != null && isZenAnswer(info, since)) used++
        }
        return used
    }

    /** Разбор массива сообщений; `null` при негодном JSON — считаем как ноль. */
    private fun parseArray(rawJson: String): JSONArray? =
        try {
            JSONArray(rawJson)
        } catch (e: JSONException) {
            Log.d(TAG, "json unparsable: ${e.message}")
            null
        }

    /** Блок `info` сообщения по индексу; `null`, если элемент не объект или битый. */
    private fun infoOf(
        arr: JSONArray,
        index: Int,
    ): JSONObject? =
        try {
            arr.getJSONObject(index).optJSONObject("info")
        } catch (e: JSONException) {
            Log.d(TAG, "message[$index] bad: ${e.message}")
            null
        }

    /**
     * Один assistant-ответ модели Zen из блока `info` сообщения.
     *
     * Условия ровно те же, что в `dev-quota/zen_usage.py`: роль assistant,
     * провайдер opencode, модель big-pickle, `created` внутри текущих UTC-суток.
     *
     * Завершённость (`time.completed`) НЕ проверяем — намеренно, чтобы цифра
     * совпадала с оверлеем на ПК. `zen_usage.py` тоже считает незаконченные
     * assistant-сообщения: строка в базе появляется в момент создания запроса.
     * Разница в один «полуживой» запрос не окупает расхождения с оверлеем.
     */
    fun isZenAnswer(
        info: JSONObject,
        sinceMs: Long,
    ): Boolean {
        val isZenModel =
            info.optString("providerID", "") == PROVIDER &&
                info.optString("modelID", "") == MODEL
        val created = info.optJSONObject("time")?.optLong("created", 0L) ?: 0L
        return info.optString("role", "") == "assistant" && isZenModel && created >= sinceMs
    }

    /**
     * Полный перебор: считаем расход по всем сессиям, которых касались сегодня.
     *
     * Идём по `GET /session`, берём те, чей `time.updated` попадает в текущие UTC-сутки,
     * и для каждой добираем ленту. Сессии, не трогатыеся сегодня, квоту не жгли, а
     * тянуть их ленты — самый дорогой шаг при полном переборе.
     *
     * Возвращает `null`, если обход не удался — тогда показываем прошлый замер.
     */
    fun refreshAcrossSessions(
        port: Int,
        nowMs: Long,
    ): Int? {
        val sessionsRaw = LocalOpenCodeClient.get(port, "/session")
        val arr = sessionsRaw?.let { parseArray(it) }
        if (arr == null) {
            Log.d(TAG, "sessions fetch failed or bad json, keep last=$lastUsed")
            return null
        }
        val since = dayStartMs(nowMs)
        var total = 0
        for (i in 0 until arr.length()) {
            val lane = laneOfToday(port, arr.getJSONObjectOrNull(i), since)
            if (lane != null) total += countInLane(lane, nowMs)
        }
        lastUsed = total
        lastReadOk = true
        Log.d(TAG, "zen used today = $total / $DAILY_LIMIT (since $since)")
        return total
    }

    /**
     * Лента сессии, только если она сегодня обновлялась; иначе `null`.
     *
     * Лента может не прийти по сети — тогда `null`, и обход её пропустит. Лучше
     * показать чуть меньшую цифру, чем упасть или подвисать на обходе.
     */
    private fun laneOfToday(
        port: Int,
        session: JSONObject?,
        since: Long,
    ): String? {
        val id = session?.optString("id", "").orEmpty()
        val updated = session?.optJSONObject("time")?.optLong("updated", 0L) ?: 0L
        val touchedToday = id.isNotBlank() && updated >= since
        return if (touchedToday) LocalOpenCodeClient.get(port, "/session/$id/message") else null
    }

    /** Элемент массива как объект; `null`, если это не объект (строка/число). */
    private fun JSONArray.getJSONObjectOrNull(index: Int): JSONObject? =
        try {
            optJSONObject(index)
        } catch (e: JSONException) {
            Log.d(TAG, "session[$index] bad: ${e.message}")
            null
        }

    /**
     * Доля израсходованного за сутки: `0f` (пусто) … `1f` (лимит выбран полностью).
     *
     * НЕ ограничиваем единицей: перерасход — полезная информация, и полоса должна
     * показывать «переполнение» ярче, чем «впритык». Ограничение на уровне UI.
     */
    fun ratio(
        used: Int,
        limit: Int = DAILY_LIMIT,
    ): Float {
        if (limit <= 0) return 0f
        return used.toFloat() / limit.toFloat()
    }
}
