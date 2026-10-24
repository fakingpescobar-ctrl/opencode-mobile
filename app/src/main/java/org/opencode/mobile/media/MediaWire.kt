package org.opencode.mobile.media

import org.json.JSONObject

/**
 * Формы медиа-ответов Android-моста: единственное место, где JSON собирается из снимков сессии.
 *
 * Выделено ради тестируемости. Пока это жило внутри приватных обработчиков
 * `AppInstallBridge`, ни одно поле нельзя было проверить без живого сокета, и регресс доехал
 * до устройства: `verified` лежал в корне ответа, потребитель читал его внутри `media` и видел
 * ложное «NOT verified» на успешной команде.
 */
internal object MediaWire {
    /** Ответ `/v1/media/like`: лайк идёт кастомной командой сессии, поэтому и `ok` — от неё. */
    fun likeResponse(result: MediaLikeResult): JSONObject =
        JSONObject()
            .put("ok", result.ok)
            .put("media", result.toJson())

    /** Ответ `/v1/media/status`: кто это приложение и что сейчас играет. */
    fun statusResponse(result: MediaStatusResult): JSONObject =
        JSONObject()
            .put("ok", true)
            .put("media", result.toJson())

    /**
     * Ответ `/v1/media/control`.
     *
     * `verified` кладётся **внутрь** `media`, рядом с before/after: потребитель читает именно
     * `media.verified`, и в корне ответа он его не находит. В корне `ok` — то же значение, но
     * одного `ok` мало: без before/after не отличить «команда сработала» от «приложение её
     * проигнорировало».
     */
    fun controlResponse(result: MediaControlResult): JSONObject =
        JSONObject()
            .put("ok", result.verified)
            .put("media", result.toJson())

    private fun MediaLikeResult.toJson(): JSONObject =
        JSONObject()
            .put("package", packageName)
            .put("label", label)
            .put("action", action)
            .put("ok", ok)
            .put("detail", detail ?: JSONObject.NULL)
            .put("playback", playback.toJson())

    private fun MediaStatusResult.toJson(): JSONObject =
        JSONObject()
            .put("package", packageName)
            .put("label", label)
            .put("transport", transport)
            .put("message", message)
            .put("playback", playback.toJson())

    private fun MediaControlResult.toJson(): JSONObject =
        JSONObject()
            .put("package", packageName)
            .put("label", label)
            .put("transport", transport)
            .put("command", command)
            .put("message", message)
            .put("verified", verified)
            .put("before", before?.toJson() ?: JSONObject.NULL)
            .put("after", after?.toJson() ?: JSONObject.NULL)

    private fun MediaPlaybackSnapshot.toJson(): JSONObject =
        JSONObject()
            .put("state", state)
            .put("is_playing", isPlaying)
            .put("title", title ?: JSONObject.NULL)
            .put("artist", artist ?: JSONObject.NULL)
            .put("album", album ?: JSONObject.NULL)
            .put("duration_ms", durationMs ?: JSONObject.NULL)
            .put("position_ms", positionMs ?: JSONObject.NULL)
            // null читается как «не знаем, есть ли лайк», а не «лайка нет»: доступно только
            // приложение, которое держит сессию, и у Яндекс Музыки это вторая сторона.
            .put("liked", liked.asJsonFlag())
            .put("disliked", disliked.asJsonFlag())

    /** Трёхзначный флаг: true, false и «сессия не ответила» — это три разных значения. */
    private fun Boolean?.asJsonFlag(): Any = this ?: JSONObject.NULL
}
