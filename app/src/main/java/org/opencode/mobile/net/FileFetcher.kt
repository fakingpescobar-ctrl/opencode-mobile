package org.opencode.mobile.net

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Скачивание одного файла с докачкой. Общая часть для всего, что приложение
 * тянет с GitHub-релизов: нативных движков ([org.opencode.mobile.engine.EngineStore])
 * и модели озвучки ([org.opencode.mobile.tts.TtsModelDownloader]).
 *
 * Вынесено отдельно, потому что копия этой логики в EngineStore обслуживала
 * ровно один вызов, а вторая копия разошлась бы от первой при первом же
 * исправлении — так уже было с теми же самыми `.part` и Range.
 *
 * Схема: файл пишется в `имя.part`, при наличии остатка посылается
 * `Range: bytes=N-`; сервер может ответить 206 (дописываем) либо 200 (диапазон
 * не поддержался — пишем с нуля). Готовый файл появляется атомарным
 * переименованием, поэтому прерванная закачка никогда не выглядит готовой.
 */
internal object FileFetcher {
    private const val CONNECT_MS = 30_000
    private const val READ_MS = 60_000
    private const val BUF = 64 * 1024
    private const val HEAD = 512
    private const val HTTP_MIN = 200
    private const val HTTP_MAX = 299

    /**
     * Скачать [url] в [target], вызывая [onBytes] с числом уже скачанных байт
     * файла (включая докачанный префикс). Бросает исключение с понятным
     * текстом, если сервер ответил неудачей или вместо файла прислал HTML.
     */
    fun fetch(
        url: String,
        target: File,
        onBytes: (Long) -> Unit,
    ) {
        val part = File(target.parentFile, target.name + ".part")
        val already = if (part.isFile) part.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
            if (already > 0) setRequestProperty("Range", "bytes=$already-")
        }
        try {
            val code = conn.responseCode
            if (code !in HTTP_MIN..HTTP_MAX) error("Сервер ответил $code на ${target.name}")
            val resumeFrom = if (code == HttpURLConnection.HTTP_PARTIAL) already else 0L
            if (resumeFrom == 0L) part.delete()
            pump(conn.inputStream, part, resumeFrom, onBytes)
            if (looksLikeHtml(part)) {
                part.delete()
                error("Вместо ${target.name} пришёл HTML — скорее всего, страница 404.")
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Переливает поток в файл, докладывая размер на каждый чанк.
     *
     * [startAt] — это докачка: в [target] лежит уже скачанный префикс, и его
     * надо ДОПИСАТЬ, а не перезаписать. Поэтому append, а не обычный поток:
     * `target.outputStream()` открывает FileOutputStream без append и ОБРЕЗАЕТ
     * файл, из-за чего докачка писала бы только хвост с сервера, размер не
     * сходился бы и сверка sha256 удаляла результат. Докачка по мобильному
     * интернету ломается на каждом обрыве — ровно то, чего эта функция не
     * допускает.
     */
    private fun pump(
        input: InputStream,
        target: File,
        startAt: Long,
        onBytes: (Long) -> Unit,
    ) {
        // Поток не закрываем: его закрывает disconnect() в finally. Лишний
        // уровень вложенности ради use{} того не стоит.
        FileOutputStream(target, startAt > 0L).use { out ->
            val buf = ByteArray(BUF)
            var written = startAt
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                written += n
                onBytes(written)
            }
        }
    }

    /**
     * GitHub отдаёт 404 не картинкой, а страницей. Без проверки файл
     * «скачается», но окажется HTML, и падение будет совсем в другом месте —
     * внутри линкера, с невнятным сообщением.
     */
    private fun looksLikeHtml(file: File): Boolean {
        val buf = ByteArray(HEAD)
        val read = file.inputStream().use { it.read(buf) }
        if (read <= 0) return false
        val text = String(buf, 0, read, Charsets.ISO_8859_1).lowercase()
        return text.contains("<!doctype") || text.contains("<html")
    }
}
