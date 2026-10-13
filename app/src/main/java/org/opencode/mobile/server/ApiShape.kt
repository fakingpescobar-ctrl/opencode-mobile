package org.opencode.mobile.server

import org.json.JSONArray
import org.json.JSONObject

/**
 * Формы ответов opencode. Пути живут в [ApiPaths], формы — здесь.
 *
 * ЗАЧЕМ ЭТОТ ФАЙЛ. Разница v1 и v2 не в путях, а в теле ответа, и она двоякая:
 *
 * 1. В v2 почти всё обёрнуто в `{data: …}`, а v1 отдавал голый массив или объект.
 * 2. Сообщения в v2 устроены иначе целиком, а не «в конверте». В v1 сообщение —
 *    `{info:{role,time}, parts:[{type:"text"},{type:"tool"},{type:"step-finish"}]}`.
 *    В v2 сообщение — `{id,type,time}` плюс либо `text` (у user), либо
 *    `content:[…]` (у assistant). Частей `step-finish`/`step-start` в v2 нет
 *    вообще, вместо них поля `finish` и `time.completed`.
 *
 * Чтобы не форкать десяток потребителей по версии, v2 приводится НАЗАД к v1:
 * [messages] разворачивает конверт, переворачивает порядок и собирает из
 * сообщения ровно тот конверт, который ждёт существующий код. Всё, что ниже
 * [messages], работает с уже приведёнными данными и о версии не знает.
 *
 * Порядок в v2 обратный: `/message` отдаёт от новых к старым, v1 — от старых
 * к новым. Рендер берёт последние N сообщений, поэтому без разворота он
 * показал бы начало диалога вместо конца. Разворот привязан к конверту `data`,
 * а не к флагу версии: решает форма пришедшего тела, а не наше предположение о
 * нём. С тем же правилом v1-сообщение проходит насквозь нетронутым — у него
 * `role` и `parts` внутри `info`, и нормализация снесла бы и то и другое.
 *
 * Здесь нет сети — только разбор. Походы за недостающими в v2 данными собраны
 * в [ApiStatus], форма `GET /mcp` — в [ApiMcp], а разбор «объект или массив» —
 * в asJsonArray/asJsonObject.
 */
internal object ApiShape {
    /** Режим доставки: вклинить в текущий ход или встать в очередь. */
    internal const val DELIVERY_STEER = "steer"

    /** Поставить в очередь: ход доработает, потом начнёт следующий. */
    internal const val DELIVERY_QUEUE = "queue"

    /**
     * Тело ответа на создание/отправку. В v2 это плоский `{"text":"…"}`.
     *
     * [delivery] по умолчанию не пишется — тогда сервер ведёт себя как прежде,
     * то есть `steer`. Поле нужно там, где сообщение отправляется в разгар чужого
     * хода: по умолчанию оно вплетается в идущий ход и модель отвечает сразу на
     * оба, а с `queue` сообщение ждёт и становится отдельным ходом.
     */
    fun promptBody(
        text: String,
        delivery: String? = null,
    ): JSONObject {
        val body = JSONObject().put("text", text)
        if (!delivery.isNullOrBlank()) body.put("delivery", delivery)
        return body
    }

    /** Список сессий: снимает конверт `{data:[…]}`, если он есть. */
    fun sessions(raw: String?): JSONArray? {
        val obj = asJsonObject(raw) ?: return asJsonArray(raw)
        return obj.optJSONArray("data") ?: asJsonArray(raw)
    }

    /** Одна сессия: в v2 тоже обёрнута в `{data:{…}}`. */
    fun session(raw: String?): JSONObject? {
        val obj = asJsonObject(raw) ?: return null
        return obj.optJSONObject("data") ?: obj
    }

    /**
     * Сообщения в виде, который ждёт существующий код: конверт `info`/`parts`,
     * от старых к новым.
     *
     * Разбор всегда идёт через нормализацию, даже если конверта `{data:…}` не
     * было. Отдельной «ветки для v1» больше нет по двум причинам. Во-первых,
     * v1 в приложении не осталось. Во-вторых, сообщение без обёртки —
     * не «формат v1», а неизвестная форма, и отношение к ней по версии нечестно:
     * разворот порядка пришлось бы включать по флагу, а флаг врёт. Форма здесь
     * честная — её смотрит сама функция, и обход от новых к старым нужен
     * одинаково, чтобы подавить дубли ошибки.
     *
     * `idle`-псевдо-сообщения в ленте не место: это служебная отметка «ход
     * закончен», а не реплика. Но если у него `outcome:"failed"`, его `error` —
     * единственное место, где может оказаться причина, если провалиться случилось
     * ДО создания assistant-сообщения. Поэтому `idle` с ошибкой показываем, а
     * успешный по-прежнему выбрасываем.
     */
    fun messages(raw: String?): JSONArray? {
        val obj = asJsonObject(raw)
        val arr = obj?.optJSONArray("data") ?: asJsonArray(raw) ?: return null
        return normalizedFromV2(arr)
    }

    /**
     * Что показать из одного сообщения v2, либо null, если показывать нечего.
     *
     * [shownError] — текст ошибки, который в ленте ниже по времени уже показан.
     * В v2 причина дублируется в assistant-сообщении и в idle. Обход идёт от
     * старых к новым (сервер отдаёт наоборот), поэтому первым носителем причины
     * оказывается assistant, а дублирующий его idle подавляется. Подавлять умелось
     * только idle — и тогда одна и та же строка «⚠ …» попадала в ленту дважды:
     * один раз из idle, второй раз из assistant. Поэтому подавляется любое
     * сообщение с ТЕМ ЖЕ текстом ошибки, а не по типу сообщения. Сравнение идёт
     * по тексту, а не по факту наличия ошибки: у соседних ходов причины разные,
     * и обе должны быть видны.
     *
     * Отдельная функция не только ради читаемости: все отказы (сообщение не
     * разобралось, дубль ошибки, пустой конверт) иначе требовали трёх `continue`
     * в цикле, а detekt разрешает один.
     */
    private fun showable(
        src: JSONObject?,
        shownError: String?,
    ): JSONObject? =
        src?.let { message ->
            val error = asErrorText(message.optJSONObject("error"))
            // Роль idle: она несёт ровно дубль уже показанного отказа. Показать имеет
            // смысл, только если собственной причины у неё нет.
            val idleDuplicate = message.optString("type") == IDLE_TYPE && error == shownError
            val silentFailure = message.optString("type") == IDLE_TYPE && error == null
            if (idleDuplicate || silentFailure) {
                null
            } else {
                toV1Envelope(message, suppressError = error != null && error == shownError)
                    .takeUnless { isBlankEnvelope(it) }
            }
        }

    private fun normalizedFromV2(arr: JSONArray): JSONArray {
        val out = JSONArray()
        // v2 отдаёт ленту новыми→старыми, а существующий код ждёт хронологическую.
        // Порядок всегда был таким, но проверить его было нечем: ни один тест на
        // порядок не смотрел, и когда рядом чинили дубли, пришлось гадать, не
        // перевернулось ли что-то здесь.
        //
        // Побочный плюс обхода от старых к новым: носителем причины оказывается
        // assistant (у него есть и свой текст, и error), а дублирующий его idle
        // идёт следом и подавляется — вместо служебной строки с причиной и
        // реплики модели рядом.
        var shownError: String? = null
        for (i in arr.length() - 1 downTo 0) {
            val src = arr.optJSONObject(i)
            val envelope = showable(src, shownError)
            if (envelope != null) {
                out.put(envelope)
                // Показанная ошибка относится только к текущему ходу. Сброс здесь
                // неявный: user-сообщение ошибки не несёт, поэтому на нём флаг сам
                // собой обнуляется.
                shownError = asErrorText(src?.optJSONObject("error"))
            }
        }
        return out
    }

    /** Тип части с текстом ошибки. Отдельный от `text` — см. [roleFor]. */
    internal const val ERROR_PART = "error"

    /** Роль сообщения об ошибке. Отдельная от `assistant` — см. [roleFor]. */
    internal const val ERROR_ROLE = "error"

    /** Значение `finish`, которым v2 помечает провалившийся ход. */
    private const val FINISH_ERROR = "error"

    /**
     * Роль для готового конверта.
     *
     * Ошибка подменяет роль на [ERROR_ROLE] ТОЛЬКО когда у хода не осталось своего
     * текста: тогда показать нечего, кроме причины, и она должна быть отдельной
     * служебной строкой.
     *
     * Когда текст есть — например модель ответила и упала на середине, или
     * наговорила «посмотрел конфиги» перед обрывом — роль остаётся `assistant`.
     * Иначе собственная реплика модели переезжает под служебную строку и в чате
     * выглядит так, будто её прислала система, а это ровно то, что пользователь
     * замечал как «то же самое продублировалось под системой».
     *
     * Раньше ошибка всегда переименовывала роль, даже при своём тексте.
     */
    private fun roleFor(
        type: String,
        parts: JSONArray,
    ): String {
        val errored = (0 until parts.length()).any { parts.optJSONObject(it)?.optString("type") == ERROR_PART }
        return when {
            errored && !hasText(parts) -> ERROR_ROLE
            type == "user" || type == "assistant" -> type
            // `system`, `idle` и всё незнакомое рисуются служебной строкой.
            else -> "system"
        }
    }

    private fun hasText(parts: JSONArray): Boolean =
        (0 until parts.length()).any {
            parts.optJSONObject(it)?.let { it.optString("type") == "text" && it.optString("text").isNotBlank() } == true
        }

    /** Приводит одно сообщение v2 к конверту v1. Виден из [v1Passthrough]. */
    internal fun toV1Envelope(
        msg: JSONObject,
        suppressError: Boolean = false,
    ): JSONObject {
        val type = msg.optString("type")
        val error = asErrorText(msg.optJSONObject("error"))
        val time = msg.optJSONObject("time")
        val model = msg.optJSONObject("model")
        // completed кладём ТОЛЬКО когда ход реально завершён. В v1 ключ
        // появлялся тоже только к этому моменту, и на `has("completed")`
        // завязаны потребители (lastAssistantText в MoltbookTicker) —
        // положим ноль, и они примут недописанный ответ за законченный.
        val completed = time?.optLong("completed") ?: 0L
        val v1Time = JSONObject().put("created", time?.optLong("created") ?: 0L)
        if (completed > 0L) v1Time.put("completed", completed)
        val parts = partsOf(msg, if (suppressError) null else error)
        val info =
            JSONObject()
                .put("role", roleFor(type, parts))
                .put("time", v1Time)
        // В v1 провайдер и модель лежали в info; в v2 они на верхнем уровне
        // сообщения. Квота (ZenQuota) читает именно info.
        info.put("providerID", model?.optString("providerID") ?: "")
        info.put("modelID", model?.optString("id") ?: "")
        return JSONObject().put("info", info).put("parts", parts)
    }

    /**
     * Части v2-сообщения в v1-виде: `content[]` либо одиночный `text`.
     *
     * Ошибка добавляется ВСЕГДА, когда она есть, даже если у сообщения нашёлся
     * свой текст. Так было не сразу и получилось неверно: текст ошибки
     * показывался только у ходов без своего текста, из-за чего флаг «ошибка уже
     * показана» в [messages] вставал в true, ничего не показав, — и дубль той же
     * ошибки в соседнем idle подавлялся. Итог: провалившийся ход с частичным
     * текстом выглядел как обычный успешный ответ.
     */
    private fun partsOf(
        msg: JSONObject,
        error: String?,
    ): JSONArray {
        val out = JSONArray()
        // Тип и завершённость нужны отсечению мысли: пока поток идёт, тег
        // </think> может ещё не прийти, и резать пока нечего. Завершённость
        // считает и «зависший» поток (finishedFor), иначе обрыв ответа был бы
        // не виден никогда.
        val msgType = msg.optString("type")
        val finished = finishedFor(msg)
        val content = msg.optJSONArray("content")
        if (content != null) {
            for (i in 0 until content.length()) {
                val item = content.optJSONObject(i) ?: continue
                when (item.optString("type")) {
                    // Размышление, пришедшее в текстовый канал, отрезается —
                    // см. answerText: иначе в ленте видно английский монолог
                    // модели перед ответом.
                    "text" -> putText(out, answerText(msgType, item.optString("text"), finished))
                    // Имя инструмента в v2 лежит в name, в v1 было в tool.
                    //
                    // id части сохраняем: это идентификатор вызова, и по нему
                    // `/permission` ссылается на инструмент в `source.id`. Без
                    // него нечем сцепить запрос «вопрос задать» с текстом вопроса,
                    // который лежит именно в этой части ленты. Раньше id молча
                    // терялся, потому что потребителям он не был нужен.
                    "tool" ->
                        out.put(
                            JSONObject()
                                .put("type", "tool")
                                .put("id", item.optString("id"))
                                .put("tool", item.optString("name"))
                                .put("state", item.optJSONObject("state") ?: JSONObject()),
                        )
                    else -> out.put(item)
                }
            }
        } else {
            putText(out, answerText(msgType, msg.optString("text"), finished))
        }
        // У v2 нет step-finish: его роль играет непустой finish.
        // finish:"error" — не «реплица завершена», а «ход провалился»: ставить
        // step-finish значило бы объявить успех, которого не было.
        val finish = msg.optString("finish")
        if (finish.isNotBlank() && finish != FINISH_ERROR) {
            out.put(JSONObject().put("type", "step-finish"))
        }
        if (error != null) out.put(JSONObject().put("type", ERROR_PART).put("text", error))
        return out
    }
}
