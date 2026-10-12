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
     * новым. `idle`-псевдо-сообщения v2 выбрасываются — видимого текста у них
     * нет, а как сообщение они выглядели бы пустым ходом ассистента.
     */
    fun messages(raw: String?): JSONArray? {
        val wrapped = asJsonObject(raw)?.optJSONArray("data")
        val arr = wrapped ?: asJsonArray(raw) ?: return null
        val out = JSONArray()
        if (wrapped != null) {
            for (i in arr.length() - 1 downTo 0) {
                toV1Envelope(arr.optJSONObject(i))?.let { out.put(it) }
            }
        } else {
            for (i in 0 until arr.length()) {
                toV1Envelope(arr.optJSONObject(i))?.let { out.put(it) }
            }
        }
        return out
    }

    /**
     * Идёт ли сейчас ход в сессии — то, что в v1 сообщала карта `/session/status`.
     *
     * В v2 отдельного эндпоинта нет, и вместо него в ленту кладётся
     * псевдо-сообщение `type:"idle"`, когда ход закончен. Разбирать его по версии
     * на местах нельзя — сюда и сносят все потребители статуса.
     *
     * null — определить нечем (лента не пришла или не разобралась). Это НЕ «свободна»:
     * вызывающие обязаны трактовать это как «не знаю» и ничего не удалять.
     *
     * Важная деталь, найденная на живом сервере: в начале ленты может лежать
     * сохранённое сообщение `type:"system"` («список инструментов изменился»), и
     * `idle` при этом не появляется ВООБЩЕ. Наивная проверка «новейшее == idle»
     * объявила бы такую сессию вечно занятой. Поэтому идём от новых к старым,
     * системные пропускаем, и решение принимает первый «содержательный» тип.
     * Пустая лента (или состоящая из одних system) — хода не было.
     */
    fun isBusy(rawMessages: String?): Boolean? {
        val arr = asJsonArray(rawMessages) ?: asJsonObject(rawMessages)?.optJSONArray("data")
        return if (arr == null) null else verdictIn(arr)
    }

    /** Ищем от новых к старым первый тип, который что-то значит. Не нашли — хода нет. */
    private fun verdictIn(arr: JSONArray): Boolean {
        for (i in 0 until arr.length()) {
            val verdict = busyVerdict(arr.optJSONObject(i))
            if (verdict != null) return verdict
        }
        return false
    }

    /**
     * Что говорит одно сообщение: true — ход идёт, false — закончен,
     * null — сообщение не показатель (system, мусор), искать дальше.
     */
    private fun busyVerdict(msg: JSONObject?): Boolean? {
        val obj = msg ?: return null
        // v1-сообщение узнаётся по `info`: type лежит не на верхнем уровне.
        val info = obj.optJSONObject("info")
        val type = obj.optString("type").ifBlank { info?.optString("role") ?: "" }
        val time = obj.optJSONObject("time") ?: info?.optJSONObject("time")
        return when (type) {
            "system" -> null
            IDLE -> false
            "assistant" -> (time?.optLong("completed") ?: 0L) <= 0L
            // user или незнакомый тип: ход отправлен, ответа ещё нет.
            else -> true
        }
    }

    /**
     * Карта занятых сессий в том виде, в каком её ждал v1-потребитель:
     * ключ есть ⇔ сессия занята. В v2 синтезируется из [isBusy].
     *
     * null — как и раньше, «не знаю»: сервер не ответил ИЛИ ленту прочитать
     * не удалось. Потребитель в этом случае ничего не чистит.
     */
    fun statusRecord(rawMessages: String?, sessionId: String): String? {
        if (ApiPaths.activeFlavor != ServerFlavor.V2) return rawMessages
        return when (isBusy(rawMessages)) {
            null -> null
            true -> JSONObject().put(sessionId, JSONObject()).toString()
            false -> "{}"
        }
    }

    private const val IDLE = "idle"

    /** v2-сообщение → v1-конверт. null, если показывать нечего. */
    private fun toV1Envelope(msg: JSONObject): JSONObject? {
        // v1-сообщение уже в нужном виде: `role` и `parts` лежат внутри `info`.
        if (msg.optJSONObject("info")?.has("role") == true) return msg
        val type = msg.optString("type")
        val envelope =
            if (type == IDLE) {
                null
            } else {
                val time = msg.optJSONObject("time")
                val model = msg.optJSONObject("model")
                // completed кладём ТОЛЬКО когда ход реально завершён. В v1 ключ
                // появлялся тоже только к этому моменту, и на `has("completed")`
                // завязаны потребители (lastAssistantText в MoltbookTicker) —
                // положим ноль, и они примут недописанный ответ за законченный.
                val v1Time = JSONObject().put("created", time?.optLong("created") ?: 0L)
                if ((time?.optLong("completed") ?: 0L) > 0L) v1Time.put("completed", time?.optLong("completed"))
                val info =
                    JSONObject()
                        .put("role", type)
                        .put("time", v1Time)
                // В v1 провайдер и модель лежали в info; в v2 они на верхнем
                // уровне сообщения. Квота (ZenQuota) читает именно info.
                info.put("providerID", model?.optString("providerID") ?: "")
                info.put("modelID", model?.optString("id") ?: "")
                JSONObject().put("info", info).put("parts", partsOf(msg))
            }
        return envelope
    }

    /** Части v2-сообщения в v1-виде: `content[]` либо одиночный `text`. */
    private fun partsOf(msg: JSONObject): JSONArray {
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
        if (msg.optString("finish").isNotBlank()) out.put(JSONObject().put("type", "step-finish"))
        return out
    }
}
