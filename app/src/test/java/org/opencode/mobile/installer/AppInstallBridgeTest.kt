package org.opencode.mobile.installer

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencode.mobile.media.MediaControlResult
import org.opencode.mobile.media.MediaWire

/**
 * Тесты на провод Android-моста: коды ошибок и формы ответов.
 *
 * Все пять случаев — это регрессы, которые дожили до устройства, потому что жили внутри
 * приватных обработчиков [AppInstallBridge] и проверялись только руками на телефоне.
 * Каждый тест обязан краснеть на откате соответствующего фикса, иначе он декоративный.
 */
class AppInstallBridgeTest {
    @Test
    fun `empty body reads as an empty object`() {
        // GET-запрос тела не имеет вовсе, и это «параметров нет», а не ошибка:
        // раньше пустое тело давало 400 и /v1/media/apps был недостижим.
        assertEquals(0, AppInstallWire.jsonObject("").length())
        assertEquals(0, AppInstallWire.jsonObject("  \r\n\t ").length())
    }

    @Test
    fun `object body is parsed as is`() {
        val body = AppInstallWire.jsonObject("""{"package":"ru.yandex.music"}""")

        assertEquals("ru.yandex.music", body.getString("package"))
    }

    @Test
    fun `non-object body is a client error`() {
        listOf("[]", "\"text\"", "null", "not json", "{").forEach { body ->
            val thrown =
                assertThrows(IllegalArgumentException::class.java) {
                    AppInstallWire.jsonObject(body)
                }

            assertEquals("body must be a JSON object", thrown.message)
            assertEquals(AppInstallWire.BAD_REQUEST, AppInstallWire.httpFailure(thrown).code)
        }
    }

    @Test
    fun `missing required field is a bad request and not a server failure`() {
        // JSONException — потомок RuntimeException, но НЕ IllegalArgumentException:
        // без своей ветки в httpFailure он уезжает в 500, то есть плохой вход
        // выглядит как сбой сервера. Именно так отсутствие package давало 500.
        val missing =
            assertThrows(JSONException::class.java) {
                JSONObject("{}").getString("package")
            }
        val failure = AppInstallWire.httpFailure(missing)

        assertEquals(AppInstallWire.BAD_REQUEST, failure.code)
        assertEquals(missing.message, failure.message)
        assertFalse(
            "JSONException перестал быть отдельным случаем: если он стал IllegalArgumentException, ветка в httpFailure лишняя",
            IllegalArgumentException::class.java.isAssignableFrom(JSONException::class.java),
        )
    }

    @Test
    fun `invalid arguments stay a bad request`() {
        val failure = AppInstallWire.httpFailure(IllegalArgumentException("package is required"))

        assertEquals(AppInstallWire.BAD_REQUEST, failure.code)
        assertEquals("package is required", failure.message)
    }

    @Test
    fun `unexpected failure stays a server failure`() {
        val failure = AppInstallWire.httpFailure(RuntimeException("boom"))

        assertEquals(AppInstallWire.SERVER_ERROR, failure.code)
        assertEquals("boom", failure.message)
    }

    @Test
    fun `failure without message gets a readable default`() {
        // message у исключения может быть null, а агенту нужен текст, а не пустая строка.
        assertEquals("malformed JSON body", AppInstallWire.httpFailure(JSONException(null as String?)).message)
        assertEquals("request failed", AppInstallWire.httpFailure(RuntimeException()).message)
    }

    @Test
    fun `launch response reports accepted intent regardless of liveness`() {
        // ok — литерал true: «intent принят», а не «приложение запустилось». Второе отсюда
        // не видно, а memory.js бросает на !body.ok, поэтому подставлять сюда признак
        // жизни нельзя: заведомо успешный запуск падал бы на каждом вызове.
        val app =
            LaunchedAppSnapshot(
                packageName = "ru.yandex.music",
                label = "Яндекс Музыка",
                componentName = "ru.yandex.music/.MainActivity",
                message = "launch intent accepted",
                backgroundLaunchAllowed = false,
            )

        val response = AppInstallWire.launchResponse(app)

        assertSame(java.lang.Boolean.TRUE, response.get("ok"))
        assertFalse(response.getBoolean("background_launch_allowed"))
        assertTrue(response.getString("hint").contains("BAL_BLOCK"))
    }

    @Test
    fun `launch app json carries the permission flag and no liveness field`() {
        val app =
            LaunchedAppSnapshot(
                packageName = "ru.yandex.music",
                label = "Яндекс Музыка",
                componentName = "ru.yandex.music/.MainActivity",
                message = "launch intent accepted",
                backgroundLaunchAllowed = true,
            )

        val appJson = AppInstallWire.launchResponse(app).getJSONObject("app")

        assertEquals("ru.yandex.music", appJson.getString("package"))
        assertTrue(appJson.getBoolean("background_launch_allowed"))
        assertFalse("живость приложения из моста не видна, поля running тут быть не должно", appJson.has("running"))
    }

    @Test
    fun `media control keeps verified inside media`() {
        // Потребитель читает media.verified. Когда поле лежало в корне, агент видел
        // undefined и докладывал ложное «NOT verified» на успешной команде.
        val result =
            MediaControlResult(
                packageName = "ru.yandex.music",
                label = "Яндекс Музыка",
                transport = "media_session",
                command = "next",
                message = "command sent, playback state unchanged",
                before = null,
                after = null,
                verified = false,
            )

        val response = MediaWire.controlResponse(result)

        assertFalse(response.getBoolean("ok"))
        assertFalse("verified живёт только внутри media", response.has("verified"))
        val media = response.getJSONObject("media")
        assertFalse(media.getBoolean("verified"))
        assertEquals("next", media.getString("command"))
        assertSame(JSONObject.NULL, media.get("before"))
        assertSame(JSONObject.NULL, media.get("after"))
    }
}
