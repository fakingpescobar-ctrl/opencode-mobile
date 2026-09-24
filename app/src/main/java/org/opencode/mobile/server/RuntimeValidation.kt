package org.opencode.mobile.server

import android.content.Context
import org.opencode.mobile.OpencodeApp
import org.opencode.mobile.stt.ModelDownloader
import org.opencode.mobile.stt.NcnnModelValidator

/**
 * Единый контракт проверки готовности runtime-слоя (PR4).
 *
* Один отчёт вместо разрозненных проверок в разных углах UI: нативный бинарь
 * (libopencode.so + musl), Basic-аутентификация serve, живой HTTP/TCP serve,
 * протокольно живой локальный MCP-сервер памяти (GET /mcp → 2xx), полный набор
 * ncnn-модели и свободное место на хранилище моделей. Используется диагностикой
 * (секция «Валидация») и будет переиспользован вебхуками/бутстрапом.
 *
 * Все проверки — файловые/TCP, безопасны для Dispatchers.IO.
 */
object RuntimeValidation {
    const val SERVER_PORT = OpencodeApp.ServerConfig.PORT
    const val MEMORY_PORT = OpencodeRuntime.MEMORY_PORT

    /** TCP-таймаут коннекта при проверке порта (мс). */
    private const val CONNECT_TIMEOUT_MS = 1_000

    /** Итог валидации: boolean-срез каждой подсистемы. */
    data class Report(
        /** libopencode.so / libldmusl.so ассемблированы в nativeLibraryDir. */
        val nativeRuntime: Boolean,
        /** Basic-пароль serve задан (ServerAuth.password != null). */
        val serverAuth: Boolean,
/** opencode serve отвечает по TCP на SERVER_PORT. */
        val serverHttp: Boolean,
        /** Локальная память MCP отвечает протокольно (HTTP 2xx на GET /mcp). */
        val memoryMcp: Boolean,
        /** Полный набор файлов ncnn-turbo на месте (NcnnModelValidator). */
        val ncnnTurbo: Boolean,
        /** Свободно байт на хранилище моделей. */
        val storageFreeBytes: Long,
    ) {
        val allOk: Boolean
            get() = nativeRuntime && serverAuth && serverHttp && memoryMcp && ncnnTurbo
    }

/** Текущий отчёт. Вызывать вне main-потока (файловые проверки). */
    fun run(context: Context): Report =
        Report(
            nativeRuntime = OpencodeRuntime.isAssembled(context),
            serverAuth = ServerAuth.password != null,
            serverHttp = tcpOk(SERVER_PORT),
            memoryMcp = memoryHttpOk(),
            ncnnTurbo = NcnnModelValidator.checkTurbo(context).ok,
            storageFreeBytes = ModelDownloader.freeBytes(context),
        )

    /**
     * Протокольная проверка локальной памяти MCP: GET /mcp должен ответить
     * HTTP 2xx. Только слушающий TCP-порт недостаточен — он может держать
     * чужой процесс; memory.js (Streamable HTTP) отвечает 200 + text/event-stream
     * именно на GET /mcp, так что 2xx — это признак живого MCP-сервера, к которому
     * serve реально может подключиться. Стрим не читаем (SSE бесконечный),
     * рвём соединение сразу после получения заголовков.
     */
    fun memoryHttpOk(): Boolean {
        try {
            val conn =
                (java.net.URL("http://127.0.0.1:$MEMORY_PORT/mcp").openConnection() as java.net.HttpURLConnection)
                    .apply {
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = CONNECT_TIMEOUT_MS
                        setRequestProperty("Accept", "text/event-stream")
                        setRequestProperty("Connection", "close")
                    }
            return try {
                val code = conn.responseCode
                code in 200..299
            } finally {
                conn.disconnect()
            }
        } catch (e: java.io.IOException) {
            return false
        }
    }

/** TCP-коннект до localhost:port (память слушает сокет, не HTTP; serve — HTTP). */
    @Suppress("SwallowedException") // Диагностика: любой отказ коннекта = «подсистема не готова», причина не влияет на вердикт.
    fun tcpOk(port: Int): Boolean {
        try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)
            }
            return true
        } catch (e: java.io.IOException) {
            return false
        }
    }
}
