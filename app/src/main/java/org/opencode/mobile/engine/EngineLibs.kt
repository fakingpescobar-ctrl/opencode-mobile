package org.opencode.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Загрузка нативных движков из каталога данных приложения.
 *
 * System.loadLibrary здесь не годится: он ищет только в распакованных
 * библиотеках APK, а движки лежат в filesDir/engines и в APK их нет.
 * Поэтому грузим System.load с абсолютным путём.
 *
 * Порядок важен и задан в [Engine.files]: файлы перечислены так, что
 * зависимости идут раньше зависимых. Если сначала загрузить
 * libncnnwhisper.so, линкер не найдёт libglslang.so и упадёт с
 * «cannot locate symbol» уже во время dlopen.
 *
 * Повторный вызов безопасен: System.load для уже загруженного soname
 * возвращает уже загруженный обработчик и ничего не делает.
 */
internal object EngineLibs {
    private const val TAG = "EngineLibs"

    /**
     * Готовность нативного кода к загрузке.
     *
     * Движок из APK всегда готов: он лежит в распакованных библиотеках и его
     * находит System.loadLibrary, а не наш каталог. Без этой ветки озвучка на
     * sherpa отвечала бы «движок не установлен - скачайте его», хотя скачивать
     * нечего и кнопки для него нет: ровно то, чего каталог запрещает.
     */
    fun isAvailable(
        context: Context,
        engine: Engine,
    ): Boolean = engine.bundled || EngineStore.isReady(context, engine)

    /**
     * Загружает движок. false - движок не скачан либо линкер его не взял;
     * причина в обоих случаях в логе и возвращается через [reasonOf].
     *
     * Движок из APK идёт через System.loadLibrary: он лежит в самом пакете,
     * путь в filesDir/engines для него бессмысленен.
     */
    fun load(
        context: Context,
        engine: Engine,
    ): Boolean =
        if (engine.bundled) {
            loadBundled(engine)
        } else {
            loadFromStore(EngineStore.dir(context, engine), engine)
        }

    /** Все файлы движка из filesDir, строго в порядке зависимостей. */
    private fun loadFromStore(
        dir: File,
        engine: Engine,
    ): Boolean {
        return try {
            for (file in engine.files) {
                val so = File(dir, file.name)
                if (!so.isFile) {
                    Log.w(TAG, "net fayla ${file.name} v ${dir.absolutePath}")
                    return false
                }
                System.load(so.absolutePath)
            }
            Log.i(TAG, "zagruzen ${engine.name} iz ${dir.absolutePath}")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "ne udalos zagruzit ${engine.name}: ${e.message}")
            false
        }
    }

    /** Движок из APK: System.loadLibrary, без каталога и без скачивания. */
    private fun loadBundled(engine: Engine): Boolean =
        try {
            System.loadLibrary(engine.libName)
            Log.i(TAG, "zagruzen iz APK: ${engine.name}")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "ne udalos zagruzit iz APK ${engine.name}: ${e.message}")
            false
        }

    /** Текст для интерфейса: либо null, либо почему движок не взялся. */
    fun reasonOf(
        context: Context,
        engine: Engine,
    ): String? =
        when {
            !isAvailable(context, engine) ->
                "${engine.label}: движок не установлен - скачайте его в настройках"
            else -> null
        }
}
