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
    /** Тело ответа на создание/отправку: v1 ждёт `parts`, v2 — плоский `text`. */
    fun promptBody(text: String): JSONObject =
        if (ApiPaths.activeFlavor == ServerFlavor.V2) {
            JSONObject().put("text", text)
        } else {
            JSONObject()
                .put(
                    "parts",
                    JSONArray().put(JSONObject().put("type", "text").put("text", text)),
                )
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
     * Сообщения в виде, который ждёт существующий код: v1-конверт, от старых к
     * новым.
     *
     * `idle`-псевдо-сообщения v2 в ленте не место: это служебная отметка «ход
     * закончен», а не реплика. Но если у него `outcome:"failed"`, его `error` —
     * единственное место, где может оказаться причина, если провалиться случилось
     * ДО создания assistant-сообщения. Поэтому `idle` с ошибкой показываем, а
     * успешный по-прежнему выбрасываем.
     */
    fun messages(raw: String?): JSONArray? {
        val wrapped = asJsonObject(raw)?.optJSONArray("data")
        val arr = wrapped ?: asJsonArray(raw) ?: return null
        return if (wrapped != null) normalizedFromV2(arr) else v1Passthrough(arr)
    }

    /**
     * Лента v2: `{data:[…]}`, новые→старые, приводится к конверту v1 и разворачивается.
     *
     * Отдельная функция не ради красоты: вместе с веткой v1 она не влезала в
     * лимит сложности, а заодно держала цикл с двумя `continue`.
     */
    /**
     * Что показать из одного сообщения v2, либо null, если показывать нечего.
     *
     * Отдельная функция не только ради читаемости: все три отказа (сообщение не
     * разобралось, дубль ошибки, пустой конверт) иначе требовали трёх `continue`
     * в цикле, а detekt разрешает один.
     */
    private fun showable(src: JSONObject?, errorShown: Boolean): JSONObject? {
        val error = src?.optJSONObject("error")
        // В v2 текст ошибки дублируется в assistant-сообщении и в idle.
        // Показываем первый (новый) раз и молчим про второй.
        val dupError = src != null && src.optString("type") == IDLE_TYPE && (error == null || errorShown)
        return if (src == null || dupError) null else toV1Envelope(src).takeUnless { isBlankEnvelope(it) }
    }

    private fun normalizedFromV2(arr: JSONArray): JSONArray {
        val out = JSONArray()
        // Идём от новых к старым (v2 отдаёт в этом порядке), поэтому здесь же
        // видно, показывал ли соседний assistant ту же ошибку.
        var errorShown = false
        for (i in arr.length() - 1 downTo 0) {
            val src = arr.optJSONObject(i)
            val envelope = showable(src, errorShown)
            if (envelope != null) {
                out.put(envelope)
                // Показанная ошибка относится только к текущему ходу. Сброс
                // здесь неявный: user-сообщение ошибки не несёт, поэтому на нём
                // флаг сам собой обнуляется. Сделано явно, потому что такой
                // молчаливый сброс легко сломать правкой строки выше — и тогда
                // провал без assistant-сообщения стал бы пустым.
                //
                // Флаг ставится по errorText, а не по наличию поля error: пустой
                // объект error даёт asErrorText == null, часть в ленту не
                // попадает, и по «полю есть» флаг встал бы в true, подавив
                // настоящую причину из соседнего idle. Это ровно тот класс бага,
                // что уже был здесь один раз.
                errorShown = asErrorText(src?.optJSONObject("error")) != null
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
     * Ошибка намеренно НЕ идёт под роль `assistant`. Ответ, который не пришёл,
     * — не реплика модели, и если подставить его под `assistant`, в ловушку
     * попадут все, кто ищет «последний ответ ассистента»: счётчик завершённых
     * ответов, ожидание ответа в молтизме (lastAssistantText берёт только части
     * типа `text`, поэтому туда ошибка не попадёт), экспорт переписки. Текст ошибки
     * ушёл бы наружу как реплика вместо того, чтобы остаться диагностикой.
     */
    private fun roleFor(type: String, parts: JSONArray): String {
        val errored = (0 until parts.length()).any { parts.optJSONObject(it)?.optString("type") == ERROR_PART }
        return when {
            errored -> ERROR_ROLE
            type == "user" || type == "assistant" -> type
            // `system`, `idle` и всё незнакомое рисуются служебной строкой.
            else -> "system"
        }
    }

    /** v2-сообщение → v1-конверт. */
    /** Приводит одно сообщение v2 к конверту v1. Виден из [v1Passthrough]. */
    internal fun toV1Envelope(msg: JSONObject): JSONObject {
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
        val parts = partsOf(msg, error)
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
    private fun partsOf(msg: JSONObject, error: String?): JSONArray {
        val out = JSONArray()
        val content = msg.optJSONArray("content")
        if (content != null) {
            for (i in 0 until content.length()) {
                val item = content.optJSONObject(i) ?: continue
                when (item.optString("type")) {
                    "text" -> out.put(JSONObject().put("type", "text").put("text", item.optString("text")))
                    // Имя инструмента в v2 лежит в name, в v1 было в tool.
                    "tool" ->
                        out.put(
                            JSONObject()
                                .put("type", "tool")
                                .put("tool", item.optString("name"))
                                .put("state", item.optJSONObject("state") ?: JSONObject()),
                        )
                    else -> out.put(item)
                }
            }
        } else {
            val text = msg.optString("text")
            if (text.isNotBlank()) out.put(JSONObject().put("type", "text").put("text", text))
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
