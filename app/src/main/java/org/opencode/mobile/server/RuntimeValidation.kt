package org.opencode.mobile.server

import android.content.Context
import org.opencode.mobile.OpencodeApp
import org.opencode.mobile.stt.ModelDownloader
import org.opencode.mobile.stt.SttModels

/**
 * Единый контракт проверки готовности runtime-слоя (PR4).
 *
* Один отчёт вместо разрозненных проверок в разных углах UI: нативный бинарь
 * (libopencode.so + musl), Basic-аутентификация serve, живой HTTP/TCP serve,
 * протокольно живой локальный MCP-сервер памяти (GET /mcp → 2xx), готовая модель
 * распознавания из каталога и свободное место на хранилище моделей. Используется диагностикой
 * (секция «Валидация») и будет переиспользован вебхуками/бутстрапом.
 *
 * Все проверки — файловые/TCP, безопасны для Dispatchers.IO.
 */
object RuntimeValidation {
    const val SERVER_PORT = OpencodeApp.ServerConfig.PORT
    const val MEMORY_PORT = OpencodeRuntime.MEMORY_PORT
    const val YNISON_PORT = OpencodeRuntime.YNISON_PORT

/** TCP-таймаут коннекта при проверке порта (мс). */
    private const val CONNECT_TIMEOUT_MS = 1_000

    /** Нижняя граница успешного HTTP-кода (2xx = MCP-протокол жив). */
    private const val HTTP_OK_MIN = 200

    /**
     * Файл-настроек и ключ выбранной модели — те же, что пишет выбор модели
     * в оверлее чата (ChatOverlay -> SttModelPicker). Зеркалим здесь, потому
     * что валидация не должна заходить в UI-слой, а ответ ей нужен тот же:
     * «какая модель будет распознавать речь».
     */
    private const val OVERLAY_PREFS = "chat_overlay"

    /** Ключ выбранной модели распознавания внутри [OVERLAY_PREFS]. */
    private const val SELECTED_MODEL_PREF = "stt_model_whisper"

    /** Верхняя граница успешного HTTP-кода. */
    private const val HTTP_OK_MAX = 299

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
        /**
         * Музыка MCP отвечает протокольно (HTTP 2xx на GET /mcp).
         *
         * Ложь только когда музыка ОЖИДАЛАСЬ (Яндекс подключён, bearer выдан) и не
         * отвечает. Без подключённого Яндекса музыка — опциональная подсистема, и её
         * отсутствие не должно красить весь отчёт; поэтому здесь true.
         */
        val musicMcp: Boolean,
        /**
         * Модель, которой приложение распознаёт речь прямо сейчас, на месте и цела
         * ([SttModels]).
         *
         * Проверяется ВЫБРАННАЯ модель (или дефолтная, если выбора ещё не было), а
         * не весь каталог: в каталоге есть и опциональные модели, которые ставят
         * вручную (Parakeet — 4 файла, без кнопки скачивания), и требование «все
         * модели каталога готовы» красило бы отчёт для тех, кто их просто не ставил.
         */
        val sttModelReady: Boolean,
        /** Свободно байт на хранилище моделей. */
        val storageFreeBytes: Long,
    ) {
        val allOk: Boolean
            get() = nativeRuntime && serverAuth && serverHttp && memoryMcp && musicMcp && sttModelReady
    }

/** Текущий отчёт. Вызывать вне main-потока (файловые проверки). */
    fun run(context: Context): Report =
        Report(
            nativeRuntime = OpencodeRuntime.isAssembled(context),
            serverAuth = ServerAuth.password != null,
            serverHttp = tcpOk(SERVER_PORT),
            memoryMcp = memoryHttpOk(),
            // Музыка проверяется только когда ожидалась (см. KDoc поля): без bearer'а
            // витка проверять нечего, и `true` отражает «опциональна, требований нет».
            musicMcp = YnisonAuth.bearerHeader()?.let { probeMcp(YNISON_PORT, it) } ?: true,
            sttModelReady = selectedModelReady(context),
            storageFreeBytes = ModelDownloader.freeBytes(context),
        )

    /**
     * Готовность модели, которой распознают речь, — выбранной в настройках
     * (или дефолтной, если выбора ещё не было): файл на месте и SHA-256 не
     * битый. Ровно та же проверка, что делала `SttModels.all.all {}`, пока в
     * каталоге была одна модель, — только теперь она смотрит на выбор
     * пользователя, а не требует ставить все модели каталога разом.
     */
    private fun selectedModelReady(context: Context): Boolean {
        val prefs = context.getSharedPreferences(OVERLAY_PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(SELECTED_MODEL_PREF, SttModels.DEFAULT_ID) ?: SttModels.DEFAULT_ID
        val spec = SttModels.byId(stored) ?: SttModels.byId(SttModels.DEFAULT_ID) ?: return false
        return spec.isReady(context) &&
            ModelDownloader.checkIntegrity(spec.fileOf(context)) != ModelDownloader.ModelIntegrity.CORRUPT
    }

    /**
     * Протокольная проверка локальной памяти MCP: GET /mcp должен ответить
     * HTTP 2xx. Только слушающий TCP-порт недостаточен — он может держать
     * чужой процесс; memory.js (Streamable HTTP) отвечает 200 + text/event-stream
     * именно на GET /mcp, так что 2xx — это признак живого MCP-сервера, к которому
     * serve реально может подключиться. Стрим не читаем (SSE бесконечный),
     * рвём соединение сразу после получения заголовков.
     *
     * Заголовок Authorization обязателен: memory.js держит bearer-аутентификацию
     * и без него отвечает 401, из-за чего проверка была красной на живом сервере.
     * Нет токена (память не поднята/остановлена) — false: проверять нечего, и
     * «зелёный» результат был бы враньём.
     */
    @Suppress("SwallowedException") // Диагностика: любой отказ = «память не готова», причина не влияет на вердикт.
    fun memoryHttpOk(): Boolean {
        val authorization = MemoryAuth.bearerHeader() ?: return false
        return probeMcp(MEMORY_PORT, authorization)
    }

    /** GET /mcp с bearer — 2xx значит живой MCP (тело SSE не читаем). */
    @Suppress("SwallowedException") // Любой отказ = «подсистема не готова», причина не влияет на вердикт.
    private fun probeMcp(
        port: Int,
        authorization: String,
    ): Boolean {
        try {
            val conn =
                (java.net.URL("http://127.0.0.1:$port/mcp").openConnection() as java.net.HttpURLConnection)
                    .apply {
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = CONNECT_TIMEOUT_MS
                        setRequestProperty("Accept", "text/event-stream")
                        setRequestProperty("Connection", "close")
                        setRequestProperty("Authorization", authorization)
                    }
            return try {
                val code = conn.responseCode
                code in HTTP_OK_MIN..HTTP_OK_MAX
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
