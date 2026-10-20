package org.opencode.mobile.server

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Формы ответов v1 и v2 — то, ради чего существует [ApiShape].
 *
 * Каждый тест — реальное тело, снятое с живого сервера, а не выдумка: в v2
 * порядок сообщений обратный, у сообщений нет `parts`, а у карты занятости
 * вообще нет отдельного эндпоинта.
 */
class ApiShapeTest {
    /** Тело v2: конверт `data`, порядок от новых к старым, `content` вместо `parts`. */
    private val v2Messages =
        """
        {"data":[
          {"id":"msg_3","type":"idle","time":{"created":40},"outcome":"succeeded"},
          {"id":"msg_2","type":"assistant","time":{"created":20,"completed":30},
           "model":{"id":"big-pickle","providerID":"opencode","variant":"free"},
           "finish":"stop",
           "content":[{"type":"text","text":"ответ"},
                      {"type":"tool","name":"bash","state":{"status":"completed","output":"ok"}},
                      {"type":"reasoning","text":"думаю"}]},
          {"id":"msg_1","type":"user","time":{"created":10},"text":"привет"}
        ],"cursor":{"previous":null,"next":"abc"}}
        """.trimIndent()

    private fun v2(block: () -> Unit) = block()

    // ---------- messages ----------

    @Test
    fun `v2 messages are unwrapped and reversed to oldest first`() {
        v2 {
            val arr = ApiShape.messages(v2Messages) ?: error("null")
            // Три сообщения на входе, idle выброшен.
            assertEquals(2, arr.length())
            assertEquals("user", arr.getJSONObject(0).getJSONObject("info").getString("role"))
            assertEquals("assistant", arr.getJSONObject(1).getJSONObject("info").getString("role"))
        }
    }

    @Test
    fun `v2 user text becomes a text part`() {
        v2 {
            val arr = ApiShape.messages(v2Messages) ?: error("null")
            val part = arr.getJSONObject(0).getJSONArray("parts").getJSONObject(0)
            assertEquals("text", part.getString("type"))
            assertEquals("привет", part.getString("text"))
        }
    }

    @Test
    fun `v2 assistant content maps onto v1 parts and carries model into info`() {
        v2 {
            val arr = ApiShape.messages(v2Messages) ?: error("null")
            val msg = arr.getJSONObject(1)
            val info = msg.getJSONObject("info")
            val parts = msg.getJSONArray("parts")
            assertEquals("opencode", info.getString("providerID"))
            assertEquals("big-pickle", info.getString("modelID"))
            assertEquals("ответ", parts.getJSONObject(0).getString("text"))
            // Имя инструмента в v2 лежит в name — потребители ждут tool.
            assertEquals("tool", parts.getJSONObject(1).getString("type"))
            assertEquals("bash", parts.getJSONObject(1).getString("tool"))
            assertEquals("ok", parts.getJSONObject(1).getJSONObject("state").getString("output"))
            // finish заменяет ушедший из v2 step-finish.
            assertEquals("step-finish", parts.getJSONObject(parts.length() - 1).getString("type"))
        }
    }

    @Test
    fun `completed appears only for a finished turn`() {
        v2 {
            val streaming =
                ApiShape.messages(
                    """{"data":[{"id":"m","type":"assistant","time":{"created":5},"content":[
                       {"type":"text","text":"<think></think>ещё"}]}]}""",
                ) ?: error("null")
            val info = streaming.getJSONObject(0).getJSONObject("info")
            // Ключа нет — как в v1 до конца хода, иначе приём последнего ответа
            // агента принял бы недописанный ответ за законченный.
            assertFalse(info.getJSONObject("time").has("completed"))
        }
    }

    @Test
    fun `nezavershennyy otvet bez tega v lentu ne popadaet`() {
        v2 {
            // created — «сейчас», иначе сработает страховка finishedFor от
            // зависшего потока (STREAM_STALE_MS) и текст попал бы в ленту.
            val fresh = System.currentTimeMillis()
            val streaming =
                ApiShape.messages(
                    """{"data":[{"id":"m","type":"assistant","time":{"created":$fresh},"content":[
                       {"type":"text","text":"The user asks in Russian"}]}]}""",
                ) ?: error("null")
            // Пока тег </think> не пришёл, непонятно, мысль это или ответ.
            // Показывать наугад нельзя: в ленте всплыл бы английский монолог,
            // а озвучка успела бы произнести его по инкременту.
            assertEquals(0, streaming.length())
        }
    }

    @Test
    fun `zavisshiy potok pokazhdaetsya posle poroga`() {
        v2 {
            // Ни finish, ни completed, а streamed старый: поток замолчал. Молчать
            // было бы хуже, чем показать текст — человек не отличил бы «думает»
            // от «упало».
            val old = System.currentTimeMillis() - 60_000L
            val stuck =
                ApiShape.messages(
                    """{"data":[{"id":"m","type":"assistant","time":{"created":$old,"streamed":$old},"content":[
                       {"type":"text","text":"The user asks in Russian"}]}]}""",
                ) ?: error("null")
            assertEquals(1, stuck.length())
        }
    }

    @Test
    fun `bez streamed potok schitaetsya zhivym`() {
        v2 {
            // Сервер не отдаёт время последнего обновления: данных о жизни потока
            // нет, поэтому показывать нечего — ждём finish. Иначе на таком сервере
            // мысль выползала бы через 15 секунд.
            val old = System.currentTimeMillis() - 120_000L
            val unknown =
                ApiShape.messages(
                    """{"data":[{"id":"m","type":"assistant","time":{"created":$old},"content":[
                       {"type":"text","text":"The user asks in Russian"}]}]}""",
                ) ?: error("null")
            assertEquals(0, unknown.length())
        }
    }

    @Test
    fun `dlinnyy zhyvoy potok ne pokazyvaet mysl do konca`() {
        v2 {
            // Ход идёт две минуты, но поток только что обновился: мысль без тега
            // в ленту попадать не должна, пока не придёт </think>.
            val created = System.currentTimeMillis() - 120_000L
            val streamed = System.currentTimeMillis()
            val live =
                ApiShape.messages(
                    """{"data":[{"id":"m","type":"assistant","time":{"created":$created,"streamed":$streamed},
                       "content":[{"type":"text","text":"The user asks in Russian"}]}]}""",
                ) ?: error("null")
            assertEquals(0, live.length())
        }
    }

    // ---------- ошибки провайдера ----------
    //
    // Тело ниже снято с живого устройства (12.10.2026): ход, который провалился
    // на устаревшей модели. Раньше этот ход не давал в чате НИЧЕГО — assistant с
    // пустым content и finish:error попадал под «пустой завершённый ход, пропустить»,
    // а idle с ошибкой выбрасывался. Пользователь видел тишину.

    /** Настоящий провалившийся ход: idle с ошибкой, assistant без content, user. */
    private val failedTurn =
        """
        {"data":[
          {"id":"msg_1","time":{"created":1791821271834},"type":"idle","outcome":"failed",
           "error":{"type":"provider.invalid-request","message":"Model exo-free has been deprecated.",
                    "status":410,"response":{"body":"{\"type\":\"error\"}"}}},
          {"id":"msg_2","time":{"created":1791821270439,"completed":1791821271831},"type":"assistant",
           "agent":"build","model":{"id":"exo-free","providerID":"opencode"},"content":[],
           "finish":"error","error":{"type":"provider.invalid-request",
                    "message":"Model exo-free has been deprecated.","status":410}},
          {"id":"msg_3","time":{"created":1791821270000},"type":"user","text":"Напиши одним словом: работает"}
        ]}
        """.trimIndent()

    private fun messagesOf(raw: String) = ApiShape.messages(raw) ?: error("null")

    private fun textsOf(
        messages: org.json.JSONArray,
        type: String,
    ): List<String> =
        (0 until messages.length())
            .mapNotNull { i -> messages.getJSONObject(i).optJSONArray("parts") }
            .flatMap { parts ->
                (0 until parts.length()).mapNotNull { j ->
                    parts.optJSONObject(j)?.takeIf { it.optString("type") == type }?.optString("text")
                }
            }

    /** Типы частей одного сообщения — в отличие от textsOf, не смешивает сообщения. */
    private fun partTypesOf(message: org.json.JSONObject): List<String> {
        val parts = message.optJSONArray("parts") ?: return emptyList()
        return (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("type") }
    }

    private fun roleOf(
        messages: org.json.JSONArray,
        index: Int,
    ) = messages.getJSONObject(index).getJSONObject("info").getString("role")

    @Test
    fun `ошибка провайдера больше не молчит`() {
        val messages = messagesOf(failedTurn)
        // Порядок от старых к новым: user, провалившийся assistant. idle выкинут.
        assertEquals(2, messages.length())
        val errors = textsOf(messages, ApiShape.ERROR_PART)
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("Model exo-free has been deprecated."))
        assertTrue(errors.single().contains("provider.invalid-request"))
        assertTrue(errors.single().contains("410"))
    }

    @Test
    fun `ошибка не прикидывается ответом ассистента`() {
        val messages = messagesOf(failedTurn)
        // Роль не assistant: иначе счётчик завершённых ответов и ожидание ответа
        // в молтизме приняли бы диагностику за реплику.
        assertEquals(ApiShape.ERROR_ROLE, roleOf(messages, 1))
        // Части типа text в самом сообщении об ошибке нет — приём последнего
        // ответа агента берёт только их. (У соседнего user-сообщения текстовая
        // часть, разумеется, есть.)
        assertEquals(listOf(ApiShape.ERROR_PART), partTypesOf(messages.getJSONObject(1)))
        // И настоящий текст реплики в ленте остаётся на месте.
        assertEquals(listOf("Напиши одним словом: работает"), textsOf(messages, "text"))
    }

    @Test
    fun `провал после своего текста всё равно показывает причину`() {
        // Регрессия, найденная ревью: раньше текст ошибки добавлялся только когда
        // своего текста не нашлось. Тогда флаг «ошибка уже показана» вставал в
        // true, ничего не показав, и дубль в idle подавлялся — ход выглядел
        // обычным успешным ответом без единого слова о провале.
        val raw =
            """{"data":[
              {"id":"m1","time":{"created":3},"type":"idle","outcome":"failed",
               "error":{"type":"provider.invalid-request","message":"quota exceeded","status":429}},
              {"id":"m2","time":{"created":2,"completed":3},"type":"assistant","finish":"error",
               "content":[{"type":"text","text":"начал отвечать и упал"}],
               "error":{"type":"provider.invalid-request","message":"quota exceeded","status":429}},
              {"id":"m3","time":{"created":1},"type":"user","text":"давай"}]}"""
        val messages = messagesOf(raw)
        val failed = messages.getJSONObject(1)
        // Свой текст на месте И причина видна — обе части.
        assertEquals(
            listOf("text", ApiShape.ERROR_PART),
            partTypesOf(failed),
        )
        assertTrue(textsOf(messages, "text").contains("начал отвечать и упал"))
        assertEquals(1, textsOf(messages, ApiShape.ERROR_PART).size)
        // И дубль из idle не добавился второй раз.
        assertEquals(2, messages.length())
    }

    @Test
    fun `голый массив без обёртки не рисует служебный idle пустой строкой`() {
        // Ветка без `data` обходит toV1Envelope напрямую; раньше она превращала
        // idle в сообщение роли system с пустым parts, то есть в пустую строку.
        val raw = """[{"type":"idle","time":{"created":2},"outcome":"succeeded"}]"""
        assertEquals(0, messagesOf(raw).length())
    }

    @Test
    fun `пустой объект error не подавляет настоящую причину из idle`() {
        // Тот же класс бага, что уже был здесь дважды: флаг «показано» ставился
        // по наличию поля error, а не по факту показа. Пустой error даёт
        // asErrorText == null, часть в ленту не попадает — и настоящая причина
        // из соседнего idle молча пропадала.
        val raw =
            """{"data":[
              {"id":"m2","time":{"created":2},"type":"idle","outcome":"failed",
               "error":{"type":"e.real","message":"настоящая причина","status":503}},
              {"id":"m1","time":{"created":1,"completed":2},"type":"assistant","finish":"error",
               "content":[], "error":{}}]}"""
        val errors = textsOf(messagesOf(raw), ApiShape.ERROR_PART)
        assertEquals(listOf("⚠ настоящая причина (e.real, 503)"), errors)
    }

    @Test
    fun `два провала подряд показывают обе причины`() {
        // Флаг «ошибка уже показана» живёт на ход, а не на всю ленту: между
        // ходами его сбрасывает user-сообщение, ошибки не несущее. Проверяем,
        // что второй провал не выеден первым.
        val raw =
            """{"data":[
              {"id":"m6","time":{"created":6},"type":"idle","outcome":"failed",
               "error":{"type":"e.two","message":"second failed","status":500}},
              {"id":"m5","time":{"created":5,"completed":6},"type":"assistant","finish":"error",
               "content":[], "error":{"type":"e.two","message":"second failed","status":500}},
              {"id":"m4","time":{"created":4},"type":"user","text":"ещё раз"},
              {"id":"m3","time":{"created":3},"type":"idle","outcome":"failed",
               "error":{"type":"e.one","message":"first failed","status":410}},
              {"id":"m2","time":{"created":2,"completed":3},"type":"assistant","finish":"error",
               "content":[], "error":{"type":"e.one","message":"first failed","status":410}},
              {"id":"m1","time":{"created":1},"type":"user","text":"давай"}]}"""
        val errors = textsOf(messagesOf(raw), ApiShape.ERROR_PART)
        // Лента приводится к порядку v1 (старые к новым), поэтому и причины
        // идут от первой ошибки ко второй. Показана ровно одна на ход: дубль
        // из idle подавлен, обе причины не потеряны.
        assertEquals(listOf("⚠ first failed (e.one, 410)", "⚠ second failed (e.two, 500)"), errors)
    }

    @Test
    fun `успешный idle по-прежнему не показывается`() {
        val raw =
            """{"data":[{"type":"idle","time":{"created":3},"outcome":"succeeded"},
               {"type":"assistant","time":{"created":2,"completed":3},"content":[{"type":"text","text":"готово"}]},
               {"type":"user","time":{"created":1},"text":"привет"}]}"""
        val messages = messagesOf(raw)
        assertEquals(2, messages.length())
        assertEquals("assistant", roleOf(messages, 1))
    }

    @Test
    fun `ошибка видна даже когда assistant не был создан`() {
        // Ход мог провалиться ДО появления assistant-сообщения — тогда причину
        // несёт только idle.
        val raw =
            """{"data":[{"type":"idle","time":{"created":2},"outcome":"failed",
               "error":{"type":"api.error","message":"connection reset"}},
               {"type":"user","time":{"created":1},"text":"привет"}]}"""
        val messages = messagesOf(raw)
        assertEquals(2, messages.length())
        assertEquals(1, textsOf(messages, ApiShape.ERROR_PART).size)
        assertEquals(ApiShape.ERROR_ROLE, roleOf(messages, 1))
    }

    @Test
    fun `v2 лента разворачивается в хронологический порядок`() {
        // Порядок НИКОГДА не был предметом проверки — из-за этого уехали и
        // дубли, и лишняя строка «Система». Сервер отдаёт newest→oldest,
        // лента чата обязана быть oldest→newest, иначе переписка выглядит
        // перевёрнутой, а «последний ответ» оказывается первым словом.
        val raw =
            """{"data":[
              {"id":"m3","type":"user","time":{"created":3},"text":"третий"},
              {"id":"m2","type":"assistant","time":{"created":2,"completed":3},"finish":"stop",
               "content":[{"type":"text","text":"второй"}]},
              {"id":"m1","type":"user","time":{"created":1},"text":"первый"}]}"""
        assertEquals(listOf("первый", "второй", "третий"), textsOf(messagesOf(raw), "text"))
    }

    @Test
    fun `причина провала не дублируется в соседнем idle`() {
        // Реальная лента из живого провала: в v2 причина лежит И в assistant, И в
        // idle. Обход идёт от новых к старым, поэтому первым повстречается idle —
        // и раньше именно он показывал причину, а потом её же добавлял assistant,
        // и пользователь видел одну и ту же строку дважды.
        val raw =
            """{"data":[
              {"type":"idle","time":{"created":4},"outcome":"failed",
               "error":{"type":"provider.invalid-request","message":"context overflow","status":400}},
              {"type":"assistant","time":{"created":3},"finish":"error",
               "content":[{"type":"text","text":"посмотрел конфиги"}],
               "error":{"type":"provider.invalid-request","message":"context overflow","status":400}},
              {"type":"user","time":{"created":2},"text":"как настроен роутинг"}]}"""
        val messages = messagesOf(raw)
        // Порядок хронологический, и отдельной служебной строки с причиной нет вовсе:
        // причину несёт сама реплика модели, а дублирующий idle отброшен.
        assertEquals(listOf("как настроен роутинг", "посмотрел конфиги"), textsOf(messages, "text"))
        assertEquals(2, messages.length())
        assertEquals("assistant", roleOf(messages, 1))
        assertEquals(
            listOf("text", ApiShape.ERROR_PART),
            partTypesOf(messages.getJSONObject(1)),
        )
        assertEquals(1, textsOf(messages, ApiShape.ERROR_PART).size)
    }

    @Test
    fun `причина видна даже когда у хода не осталось своего текста`() {
        // Обратный случай: assistant без content несёт только причину. Тогда она
        // обязана занять отдельную служебную строку — больше её показать нечем.
        val raw =
            """{"data":[
              {"type":"idle","time":{"created":3},"outcome":"failed",
               "error":{"type":"provider.invalid-request","message":"model gone","status":410}},
              {"type":"assistant","time":{"created":2},"finish":"error","content":[],
               "error":{"type":"provider.invalid-request","message":"model gone","status":410}},
              {"type":"user","time":{"created":1},"text":"да"}]}"""
        val messages = messagesOf(raw)
        assertEquals(2, messages.length())
        assertEquals("error", roleOf(messages, 1))
        assertEquals(1, textsOf(messages, ApiShape.ERROR_PART).size)
    }

    @Test
    fun `причина из idle доезжает когда у assistant ошибки нет`() {
        // Обратный дубль: assistant пустой и без error, причину несёт только idle.
        // Раньше порядок обхода позволял потерять её как «уже показанную».
        val raw =
            """{"data":[
              {"type":"idle","time":{"created":3},"outcome":"failed",
               "error":{"type":"provider.invalid-request","message":"only here","status":500}},
              {"type":"assistant","time":{"created":2},"finish":"error","content":[]},
              {"type":"user","time":{"created":1},"text":"да"}]}"""
        val messages = messagesOf(raw)
        assertEquals(2, messages.length())
        assertEquals("error", roleOf(messages, 1))
        assertTrue(textsOf(messages, ApiShape.ERROR_PART).single().contains("only here"))
    }

    @Test
    fun `провал со своим текстом остаётся репликой модели`() {
        // Ответ без причины не должен переезжать под служебную строку: собственная
        // реплика модели, пусть и оборванная, остаётся репликой модели. Причина
        // добавляется к ней строкой.
        val raw =
            """{"data":[
              {"type":"idle","time":{"created":3},"outcome":"succeeded"},
              {"type":"assistant","time":{"created":2},"finish":"error",
               "content":[{"type":"text","text":"всё увидел"}],
               "error":{"type":"provider.invalid-request","message":"boom","status":500}},
              {"type":"user","time":{"created":1},"text":"проверь"}]}"""
        val messages = messagesOf(raw)
        assertEquals(2, messages.length())
        assertEquals("assistant", roleOf(messages, 1))
        assertEquals(
            listOf("text", ApiShape.ERROR_PART),
            partTypesOf(messages.getJSONObject(1)),
        )
    }

    @Test
    fun `разные причины у соседних ходов показываются обе`() {
        // Подавление идёт по ТЕКСТУ ошибки, а не по факту её наличия: у двух соседних
        // ходов причины разные, и обе обязаны быть видны.
        val raw =
            """{"data":[
              {"type":"idle","time":{"created":5},"outcome":"failed",
               "error":{"type":"e2","message":"second","status":502}},
              {"type":"assistant","time":{"created":4},"finish":"error",
               "error":{"type":"e2","message":"second","status":502}},
              {"type":"idle","time":{"created":3},"outcome":"failed",
               "error":{"type":"e1","message":"first","status":400}},
              {"type":"assistant","time":{"created":2},"finish":"error",
               "error":{"type":"e1","message":"first","status":400}},
              {"type":"user","time":{"created":1},"text":"давай"}]}"""
        val errors = textsOf(messagesOf(raw), ApiShape.ERROR_PART)
        assertEquals(2, errors.size)
        assertTrue(errors.any { it.contains("first") })
        assertTrue(errors.any { it.contains("second") })
    }

    @Test
    fun `ошибка не мешает нормальному ответу`() {
        // Если у ассистента ЕСТЬ текст, ошибка не затирает его: текст остаётся
        // текстом, а в ленту не добавляется ничего лишнего.
        val raw =
            """{"data":[{"type":"assistant","time":{"created":2,"completed":3},"finish":"stop",
               "content":[{"type":"text","text":"всё нормально"}]}]}"""
        val messages = messagesOf(raw)
        assertEquals(1, messages.length())
        assertEquals("assistant", roleOf(messages, 0))
        assertTrue(textsOf(messages, ApiShape.ERROR_PART).isEmpty())
    }

    @Test
    fun `isBusy по провалившейся ленте говорит что ход закончен`() {
        // Ошибка не должна оставлять сессию «занятой» навсегда.
        assertFalse(busyOf(failedTurn) ?: error("null"))
    }

    // ---------- isBusy ----------

    private fun busyOf(raw: String?): Boolean? = ApiStatus.isBusy(raw)

    @Test
    fun `idle at head means the turn is over`() {
        assertFalse(busyOf("""{"data":[{"type":"idle"}]}""") ?: error("null"))
    }

    @Test
    fun `a pending user turn is busy`() {
        assertTrue(busyOf("""{"data":[{"type":"user","text":"поехали"}]}""") ?: error("null"))
    }

    @Test
    fun `a completed assistant turn is not busy`() {
        val raw = """{"data":[{"type":"assistant","time":{"created":1,"completed":2}}]}"""
        assertFalse(busyOf(raw) ?: error("null"))
    }

    @Test
    fun `an assistant turn without completed is busy`() {
        val raw = """{"data":[{"type":"assistant","time":{"created":1}}]}"""
        assertTrue(busyOf(raw) ?: error("null"))
    }

    @Test
    fun `a leading system message does not mask the idle behind it`() {
        // Найдено на живом сервере: system-сообщение («список инструментов
        // изменился») может лежать первым, и idle при этом не появляется вовсе.
        val raw =
            """
            {"data":[
              {"id":"msg_9","type":"system","time":{"created":9},"text":"The available tools have changed"},
              {"id":"msg_8","type":"assistant","time":{"created":8,"completed":8}}
            ]}
            """.trimIndent()
        assertFalse(busyOf(raw) ?: error("null"))
    }

    @Test
    fun `only system messages mean no turn is running`() {
        assertFalse(busyOf("""{"data":[{"type":"system","text":"x"}]}""") ?: error("null"))
    }

    @Test
    fun `an unreadable feed is unknown, not free`() {
        assertNull(busyOf(null))
        assertNull(busyOf("<html>404</html>"))
    }

    @Test
    fun `v1 feed parses as an array too`() {
        val finished = """[{"info":{"role":"assistant","time":{"created":1,"completed":2}}}]"""
        assertFalse(ApiStatus.isBusy(finished) == true)
        val streaming = """[{"info":{"role":"assistant","time":{"created":1}}}]"""
        assertTrue(ApiStatus.isBusy(streaming) == true)
    }

    // ---------- statusRecord ----------

    @Test
    fun `v2 busy record carries the session key`() {
        v2 {
            val raw = """{"data":[{"type":"assistant","time":{"created":1}}]}"""
            val record = ApiStatus.statusRecord(raw, "ses_1") ?: error("null")
            assertTrue(JSONObject(record).has("ses_1"))
        }
    }

    @Test
    fun `v2 idle record is an empty map`() {
        v2 {
            val record = ApiStatus.statusRecord("""{"data":[{"type":"idle"}]}""", "ses_1") ?: error("null")
            assertEquals(0, JSONObject(record).length())
        }
    }

    @Test
    fun `v2 unknown record is null, not an empty map`() {
        v2 {
            assertNull(ApiStatus.statusRecord("<html>", "ses_1"))
        }
    }

    // ---------- sessions / session ----------

    @Test
    fun `sessions unwrap the v2 data envelope`() {
        val arr = ApiShape.sessions("""{"data":[{"id":"ses_1"}],"cursor":{}}""") ?: error("null")
        assertEquals(1, arr.length())
        assertEquals("ses_1", arr.getJSONObject(0).getString("id"))
    }

    @Test
    fun `sessions still parse a bare v1 array`() {
        val arr = ApiShape.sessions("""[{"id":"ses_1"}]""") ?: error("null")
        assertEquals(1, arr.length())
    }

    @Test
    fun `an object without data is not a session list`() {
        assertNull(ApiShape.sessions("{}"))
        assertNull(ApiShape.sessions("<html>404</html>"))
    }

    @Test
    fun `a single session is wrapped in v2 too`() {
        val s = ApiShape.session("""{"data":{"id":"ses_9"}}""") ?: error("null")
        assertEquals("ses_9", s.getString("id"))
        val bare = ApiShape.session("""{"id":"ses_8"}""") ?: error("null")
        assertEquals("ses_8", bare.getString("id"))
    }

    // ---------- mcp ----------

    @Test
    fun `v2 mcp flattens the nested status object`() {
        val arr =
            ApiMcp.servers(
                """{"location":{"directory":"/"},"data":[{"name":"memory","status":{"status":"connected"}}]}""",
            ) ?: error("null")
        assertEquals(1, arr.length())
        assertEquals("memory", arr.getJSONObject(0).getString("name"))
        assertEquals("connected", arr.getJSONObject(0).getString("status"))
    }

    @Test
    fun `v1 mcp map keyed by name survives`() {
        val arr = ApiMcp.servers("""{"ynison":{"name":"ynison","status":"connected"}}""") ?: error("null")
        assertEquals("ynison", arr.getJSONObject(0).getString("name"))
        assertEquals("connected", arr.getJSONObject(0).getString("status"))
    }

    @Test
    fun `v1 mcp array survives`() {
        val arr = ApiMcp.servers("""[{"name":"moltbook","status":"connected"}]""") ?: error("null")
        assertEquals("moltbook", arr.getJSONObject(0).getString("name"))
    }

    // ---------- prompt body ----------

    @Test
    fun `v2 prompt is a flat text field`() {
        v2 {
            val body = ApiShape.promptBody("привет")
            assertEquals("привет", body.getString("text"))
            assertFalse(body.has("parts"))
        }
    }
}
