package org.opencode.mobile.server

import org.json.JSONObject

/**
 * Модель сессии: смена без перезапуска агента и чтение того, что сессия
 * обслуживает прямо сейчас.
 *
 * Зачем отдельный файл, если есть [RoutingReadiness]: там проверка «обслуживает
 * ли сервер расписание из конфига» (после рестарта), здесь — про две вещи,
 * которых конфиг не покрывает:
 *
 *  - `POST /api/session/{id}/model` переключает модель ТЕКУЩЕЙ сессии на лету
 *    (в конфиге `agent.<id>.model` на генерацию не влияет — см. KDoc
 *    [planRoutingEntry], измерено на живом сервере);
 *  - `POST /api/session` принимает `model` в теле, поэтому новая сессия может
 *    сразу родиться на выбранной модели, пока сервер ещё держит в памяти старый
 *    корневой `model` из конфига.
 *
 * Обе операции живут в [LocalOpenCodeClient] и блокируют вызывающий — звать
 * только из `Dispatchers.IO`, как остальные обращения к serve.
 */
internal object SessionModel {
    /** Разделитель в форме `provider/model`, которую хранят prefs и каталог. */
    private const val SEPARATOR = "/"

    /** Тело, которым сервер принимает смену модели. */
    internal const val EMPTY_BODY = "{}"

    /**
     * `provider/model` → пара для `Model.Ref`. Без разделителя — null: гадать
     * провайдера нельзя, а `"model": "big-pickle"` сервер трактует как id без
     * провайдера и отвечает отказом (проверено: тело с одним полем `model`
     * игнорируется целиком, ход уходит на модели по умолчанию).
     */
    internal fun ref(model: String): Pair<String, String>? {
        val slash = model.indexOf(SEPARATOR)
        if (slash <= 0 || slash == model.length - 1) return null
        val provider = model.substring(0, slash).trim()
        val id = model.substring(slash + 1).trim()
        return if (provider.isEmpty() || id.isEmpty()) null else provider to id
    }

    /**
     * Тело для `POST /api/session/{id}/model` либо `POST /api/session`.
     * null — когда модель не задана (тогда сервер берёт свою, и подсовывать
     * пустую структуру незачем).
     */
    internal fun body(model: String?): String? {
        val ref = model?.let { ref(it) } ?: return null
        val modelRef =
            JSONObject()
                .put("id", ref.second)
                .put("providerID", ref.first)
        return JSONObject().put("model", modelRef).toString()
    }

    /** Тело создания сессии: с моделью, когда она выбрана, иначе пустой объект. */
    internal fun createBody(model: String?): String = body(model) ?: EMPTY_BODY

    /**
     * Переключает модель сессии. true — сервер принял (2xx), и следующие ходы
     * этой сессии идут на новой модели.
     */
    fun switch(
        port: Int,
        sessionId: String,
        model: String,
    ): Boolean {
        val payload = body(model) ?: return false
        return LocalOpenCodeClient.postAccepted(port, ApiPaths.sessionModel(sessionId), payload)
    }

    /**
     * Модель, которую сессия обслуживает сейчас, в форме `provider/model`.
     * null — сервер не ответил либо в ответе нет модели (нечем считать, что
     * выбор применён).
     */
    fun current(
        port: Int,
        sessionId: String,
    ): String? = refText(rawSession(port, sessionId)?.opt("model"))

    /** Тело `GET /api/session/{id}` либо null, если сервер не ответил. */
    private fun rawSession(
        port: Int,
        sessionId: String,
    ): JSONObject? = LocalOpenCodeClient.get(port, ApiPaths.session(sessionId))?.let(::sessionObject)

    /**
     * Одинаковая ли модель у сессии и в выборе.
     *
     * Сравнивается пара `provider/model` целиком: один и тот же id у разных
     * провайдеров — разные модели (у `opencode` бесплатные, у платных — квоты и
     * свои цены), и назвать их одинаковыми значит врать в самой важной строке
     * карточки. Имя без провайдера не совпадает ни с чем: его нечем проверить.
     */
    internal fun matches(
        served: String,
        model: String,
    ): Boolean = ref(served) == ref(model) && ref(model) != null

    /**
     * Тело `GET /api/session/{id}` в объект сессии.
     *
     * Формы живого 2.0.27 (проверено на устройстве): обёртка `{"data":{…}}` —
     * `data` ЗДЕСЬ объект, а в списке сессий `{"data":[…]}`. Отсюда и разбор
     * обоих видов: иначе на сессии модель не читалась бы никогда и карточка
     * врала бы «не удалось определить модель».
     */
    internal fun sessionObject(raw: String): JSONObject? {
        val root =
            runCatching { JSONObject(raw) }.getOrNull()
                ?: runCatching { org.json.JSONArray(raw).optJSONObject(0) }.getOrNull()
                ?: return null
        return when (val data = root.opt("data")) {
            is JSONObject -> data
            is org.json.JSONArray -> data.optJSONObject(0) ?: root
            else -> root
        }
    }

    /**
     * `Model.Ref` в текстовой форме. Ключ модели в живом ответе — `id`
     * (`{"id":"space-bunny-free","providerID":"opencode","variant":"default"}`),
     * в конфиге — `model`, поэтому берём `model`, а на его месте `id`.
     */
    internal fun refText(value: Any?): String? =
        when (value) {
            is JSONObject -> {
                val provider = value.optString("providerID", "")
                val id = value.optString("model", "").ifBlank { value.optString("id", "") }
                if (provider.isNotBlank() && id.isNotBlank()) "$provider$SEPARATOR$id" else null
            }
            is String -> value.ifBlank { null }
            else -> null
        }
}
