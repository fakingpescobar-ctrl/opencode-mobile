package org.opencode.mobile.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencode.mobile.stt.ModelDownloader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Хранение и скачивание нативных движков.
 *
 * Движки не лежат в APK: apk весит 237 МБ, и половина этого - сервер
 * opencode, а вторая половина - то, что можно докачать один раз. Схема
 * ровно та же, что уже принята для моделей (см. ModelDownloader):
 * в апке лежат только хеши, пользователь жмёт кнопку, файлы приезжают в
 * filesDir/engines.
 *
 * Каталог намеренно НЕ в jniLibs: System.loadLibrary ищет только в
 * распакованных нативных библиотеках приложения, а каталог данных
 * приложения туда не входит. Поэтому движки грузит [EngineLibs] через
 * System.load с абсолютным путём.
 */
internal object EngineStore {
    private const val TAG = "EngineStore"
    private const val REL = "engines-v1"
    private const val BASE = "https://github.com/fakingpescobar-ctrl/opencode-mobile/releases/download/$REL/"
    private const val OK_MARKER = ".ok"

    /** Каталог движка. Создаётся сразу, чтобы вызывающий не проверял null. */
    fun dir(
        context: Context,
        engine: Engine,
    ): File = File(File(context.filesDir, "engines"), engine.dirName).apply { mkdirs() }

    /**
     * Готов ли движок к загрузке нативного кода.
     *
     * Проверяем размеры и маркер `.ok`, который ставится только после
     * успешной сверки sha256. Размер нужен на каждом опросе (экран
     * настроек спрашивает постоянно), хеш - нет: считать 49 МБ хеша при
     * каждом отрисовке нельзя, а подменённый после проверки файл - это
     * уже не наша задача, об этом заботится подпись APK.
     */
    fun isReady(
        context: Context,
        engine: Engine,
    ): Boolean {
        val d = dir(context, engine)
        if (!File(d, OK_MARKER).isFile) return false
        return engine.files.all { f ->
            val target = File(d, f.name)
            target.isFile && target.length() == f.bytes
        }
    }

    /** Человеческое объяснение, почему движок не готов. */
    fun problemOf(
        context: Context,
        engine: Engine,
    ): String? =
        when {
            isReady(context, engine) -> null
            !freeSpaceOk(context, engine) -> "Мало места: нужно ${mb(engine.totalBytes)}"
            else -> "${engine.label}: движок не установлен"
        }

    private fun mb(bytes: Long): String = "%.0f МБ".format(bytes / MB)

    private fun freeSpaceOk(
        context: Context,
        engine: Engine,
    ): Boolean = ModelDownloader.freeBytes(context) > engine.totalBytes

    /**
     * Скачивает все файлы движка по порядку и ставит маркер готовности.
     *
     * Докачка через `.part` и заголовок Range: 76 МБ по мобильному
     * интернету рвётся часто, и начинать заново после каждого обрыва -
     * это мучение пользователя. Если сервер ответил 200 вместо 206,
     * сервер диапазон не поддержал и файл надо писать с нуля.
     */
    suspend fun download(
        context: Context,
        engine: Engine,
        onProgress: (done: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val d = dir(context, engine)
        // Место проверяем ДО старта: оборванная на середине закачка 76 МБ
        // оставляет мусор в .part и ругается IOException уже в середине,
        // когда пользователь ничего не может сделать. Здесь он узнаёт сразу.
        if (!freeSpaceOk(context, engine)) {
            error("Мало свободного места: нужно ${mb(engine.totalBytes)}, скачай в Настройки -> Диагностика после освобождения места")
        }
        var done = 0L
        for (file in engine.files) {
            val target = File(d, file.name)
            if (target.isFile && target.length() == file.bytes) {
                done += file.bytes
                onProgress(done, engine.totalBytes)
                continue
            }
            fetch(file, target) { written -> onProgress(done + written, engine.totalBytes) }
            val got = ModelDownloader.sha256Of(target)
            if (got != file.sha256) {
                target.delete()
                Log.e(TAG, "sha256 ne sovpal: ${file.name}, polucheno ${got ?: "net"}")
                error("Движок повреждён при скачивании: ${file.name}. Попробуйте ещё раз.")
            }
            done += file.bytes
            onProgress(done, engine.totalBytes)
        }
        File(d, OK_MARKER).writeText(engine.name)
        Log.i(TAG, "dvigok gotov: ${engine.name}, ${d.absolutePath}")
    }

    /** Один файл: докачка с `.part`, затем атомарное переименование. */
    private fun fetch(
        file: EngineFile,
        target: File,
        onBytes: (Long) -> Unit,
    ) {
        val part = File(target.parentFile, file.name + ".part")
        val already = if (part.isFile) part.length() else 0L
        val conn = (URL(BASE + file.name).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
            if (already > 0) setRequestProperty("Range", "bytes=$already-")
        }
        try {
            val code = conn.responseCode
            if (code !in HTTP_MIN..HTTP_MAX) error("Сервер движков ответил $code на ${file.name}")
            val resumeFrom = if (code == HttpURLConnection.HTTP_PARTIAL) already else 0L
            if (resumeFrom == 0L) part.delete()
            pump(conn.inputStream, part, resumeFrom, onBytes)
            if (looksLikeHtml(part)) {
                part.delete()
                error("Вместо ${file.name} пришёл HTML - скорее всего, страница 404.")
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            // Файл, скачанный в filesDir, получает права 600 - и System.load его
            // не берёт: dlopen требует бит выполнения, а SELinux на Android и
            // вовсе не даёт исполнять app_data_file без него. Проверено на
            // устройстве: движок, скачанный приложением, лежал -rw-------
            // и не грузился, пока вручную не выставлен 755.
            makeLoadable(target)
        } finally {
            conn.disconnect()
        }
    }

    /** Ставит на [file] права, при которых его можно загрузить через System.load. */
    private fun makeLoadable(file: File) {
        val ok = file.setReadable(true, false) && file.setExecutable(true, false)
        if (!ok) Log.w(TAG, "не выставил права на ${file.name}")
    }

    /**
     * Переливает поток в файл, докладывая размер на каждый чанк.
     *
     * [startAt] — это докачка: в [target] лежит уже скачанный префикс, и его
     * надо ДОПИСАТЬ, а не перезаписать. Поэтому append, а не обычный поток:
     * `target.outputStream()` открывает FileOutputStream без append и ОБРЕЗАЕТ
     * файл, из-за чего докачка писала бы только хвост с сервера, размер не
     * сходился бы и sha256-compare удалял результат. Докачка 76 МБ по мобильному
     * интернету ломалась бы на каждом обрыве — ровно то, чего эта функция
     * задумывалась не допустить.
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
     * «скачается», но окажется HTML, и падение будет совсем в другом
     * месте - внутри линкера, с невнятным сообщением.

     */
    private fun looksLikeHtml(file: File): Boolean {
        val buf = ByteArray(HEAD)
        val read = file.inputStream().use { it.read(buf) }
        if (read <= 0) return false
        val text = String(buf, 0, read, Charsets.ISO_8859_1).lowercase()
        return text.contains("<!doctype") || text.contains("<html")
    }

    private const val HTTP_MIN = 200
    private const val HTTP_MAX = 299
    private const val MB = 1048576.0
    private const val CONNECT_MS = 30_000
    private const val READ_MS = 60_000
    private const val BUF = 64 * 1024
    private const val HEAD = 512
}
