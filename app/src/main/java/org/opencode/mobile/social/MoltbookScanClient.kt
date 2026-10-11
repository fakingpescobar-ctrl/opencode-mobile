package org.opencode.mobile.social

import android.content.Context
import android.util.Log
import org.opencode.mobile.server.MoltbookMcp
import org.opencode.mobile.server.MoltbookToken
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/**
 * Просьба плагину молтбука перечитать ленту, из приложения и без агента.
 *
 * Зачем: снимок панели пишет плагин во время вызова тулов, а вызывает их агент —
 * во время сессии, или будильник, или сторож. Между этими событиями карточка
 * показывает один и тот же снимок: число «67 ждут ответа» может быть верным час
 * назад и неверным сейчас, и отличить одно от другого панель не может — у неё
 * есть только файл. Значит, кто-то должен перечитывать ленту и в простое.
 *
 * Клиент говорит с плагином напрямую, тем же HTTP, что и opencode: `POST /mcp`
 * с `Authorization: Bearer` и обычным JSON-RPC `tools/call`. Никаких новых
 * зависимостей и никакого второго пути к данным: единственный писатель снимка —
 * плагин, мы его только дёргаем.
 *
 * Это НЕ обход агента. Скан и так есть тулом, агент его дёргает когда хочет, и
 * панель ничего не решает за него: она только просит перечитать. Ответы скан не
 * публикует и не комментирует — за это по-прежнему отвечает [MoltbookWake].
 */
internal object MoltbookScanClient {
    /** Идентификаторы запросов у MCP не обязаны быть уникальны, но монотонность дешевле путаницы. */
    private val nextId = AtomicInteger(1)

    /**
     * Тело `tools/call` для [MoltbookMcp.SCAN_TOOL].
     *
     * Отдельная чистая функция, а не строка внутри HTTP-кода: формат запроса -
     * это контракт с плагином, и он должен проверяться обычным тестом. Смена
     * формата без падения тестов означала бы, что тест проверяет не то.
     */
    fun scanBody(id: Int): String =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call",""" +
            """"params":{"name":"${MoltbookMcp.SCAN_TOOL}","arguments":{}}}"""

    /** Заголовок авторизации плагина. */
    fun authorization(token: String): String = "Bearer $token"

    /** Адрес плагина: тот же порт, что и в конфиге opencode. */
    fun endpoint(port: Int): String = "http://127.0.0.1:$port/mcp"

    /**
     * Скан без сети: токена нет, плагин не поднялся, ответ не разобран.
     *
     * Отказ здесь - не авария: панель украшение, у неё нет экрана ошибки, и бросать
     * на неё ошибку из-за недоступного плагина незачем. Причина всё равно уходит
     * в лог, а карточка продолжает показывать свой прошлый снимок - он честный,
     * просто протухший, и время снимка на ней видно.
     */
    fun refuse(reason: String): ScanOutcome {
        Log.w(TAG, reason)
        return ScanOutcome(refused = reason)
    }

    /** Результат попытки: чем отказались, а не «прошло или нет». */
    internal data class ScanOutcome(
        val refused: String? = null,
    ) {
        val ok: Boolean get() = refused == null
    }

    /**
     * Дёрнуть скан. Вызывается из корутины UI, поэтому сеть - на [Dispatchers.IO].
     *
     * Порядок проверок неслучаен и повторяет правду о том, что нужно вызывающему:
     * без токена идти некуда (плагин не запускался), без ответа плагина - тоже.
     * Оба случая возвращаются отказом с разными словами, потому что чинить их
     * по-разному: первый - плагин ещё не поднялся, второй - упал.
     *
     * Отмена вызывающего обрывает запрос, а не просто перестаёт его ждать.
     * `HttpURLConnection` не отменяемый сам по себе: панель закрыли через секунду
     * после открытия, а скан без отмены дочитал бы ленту на девять запросов и
     * держал очередь вызовов тулов плагина всё это время. То есть отменённый
     * скан стоил бы дороже, чем нужный.
     */
    suspend fun requestScan(context: Context, port: Int = MoltbookMcp.PORT): ScanOutcome =
        withContext(Dispatchers.IO) {
            // Чтение токена - тоже работа с диском, поэтому внутри, а не до
            // `withContext`: иначе файл читался бы на потоке вызывающего.
            val token = MoltbookToken.read(context) ?: return@withContext refuse("токен плагина не запускался, токена нет")
            runCatching {
                val connection = URL(endpoint(port)).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("Authorization", authorization(token))
                    coroutineContext.job.invokeOnCompletion { cause ->
                        if (cause != null) connection.disconnect()
                    }
                    val payload = scanBody(nextId.getAndIncrement()).toByteArray()
                    // Точная длина ДО записи тела: иначе HttpURLConnection ставит
                    // chunked-кодирование, а закрытие outputStream до чтения ответа
                    // отправляет запрос раньше, чем известно, что целиком.
                    connection.setFixedLengthStreamingMode(payload.size)
                    connection.outputStream.use { it.write(payload) }
                    val status = connection.responseCode
                    val body =
                        (
                            if (isHttpOk(status)) connection.inputStream else connection.errorStream
                        )?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                    coroutineContext.ensureActive()
                    if (!isHttpOk(status)) {
                        return@runCatching refuse("плагин молтбука ответил $status: ${body.take(BODY_SNIPPET)}")
                    }
                    if ("\"isError\":true" in body) {
                        return@runCatching refuse("скан отказал: ${body.take(BODY_SNIPPET)}")
                    }
                    ScanOutcome()
                } finally {
                    connection.disconnect()
                }
            }.getOrElse { cause ->
                // Отмена - не отказ. Проглотив её, вернули бы «отказ» вызывающему, который
                // её отменил, и отмена панели выглядела бы как поломка скана в логах.
                if (cause is CancellationException) throw cause
                refuse("скан не отправить: ${cause.message ?: cause.javaClass.simpleName}")
            }
        }

    /**
     * Успех ли это по коду, а не «ответ вообще».
     *
     * Отдельная чистая функция: коды 2xx разбросаны по двум местам, и если бы
     * один из них забыли, то отказ плагина тихо читался бы из `errorStream` как
     * пустой успех - то есть панель решила бы, что скан прошёл.
     */
    fun isHttpOk(status: Int): Boolean = status in HTTP_OK..HTTP_OK_MAX

    private const val TAG = "MoltbookScan"
    private const val CONNECT_TIMEOUT_MS = 3_000

/**
     * Скан ходит в сеть надолго - лента, уведомления и до шести тредов.
     *
     * Должен быть заведомо БОЛЬШЕ бюджета вызова в самом плагине
     * (`MOLTBOOK_CALL_BUDGET_MS`, 120 секунд), а не короче и не равен: плагин
     * имеет право честно отработать все 120 секунд, и тогда ответ придёт
     * последним. Обрыв на минуте был бы ложью «плагин не ответил» - он ответил
     * бы, просто чуть позже. Запас 30 секунд - на разбор и проброс ответа.
     */
    private const val READ_TIMEOUT_MS = 150_000

    private const val HTTP_OK = 200
    private const val HTTP_OK_MAX = 299

    /** Сколько символов ответа попадёт в причину отказа: больше - уже простыня. */
    private const val BODY_SNIPPET = 200
}
