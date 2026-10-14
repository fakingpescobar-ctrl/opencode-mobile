package org.opencode.mobile.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencode.mobile.net.FileFetcher
import org.opencode.mobile.stt.ModelDownloader
import java.io.File

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

    /** Один файл: докачка с `.part`, затем атомарное переименование — см. [FileFetcher]. */
    private fun fetch(
        file: EngineFile,
        target: File,
        onBytes: (Long) -> Unit,
    ) {
        FileFetcher.fetch(BASE + file.name, target, onBytes)
        // Файл, скачанный в filesDir, получает права 600 - и System.load его
        // не берёт: dlopen требует бит выполнения, а SELinux на Android и
        // вовсе не даёт исполнять app_data_file без него. Проверено на
        // устройстве: движок, скачанный приложением, лежал -rw-------
        // и не грузился, пока вручную не выставлен 755.
        makeLoadable(target)
    }

    /** Ставит на [file] права, при которых его можно загрузить через System.load. */
    private fun makeLoadable(file: File) {
        val ok = file.setReadable(true, false) && file.setExecutable(true, false)
        if (!ok) Log.w(TAG, "не выставил права на ${file.name}")
    }

    private const val MB = 1048576.0
}
