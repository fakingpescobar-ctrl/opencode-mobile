package org.opencode.mobile

import android.content.Context
import android.util.Log
import org.opencode.mobile.stt.ModelDownloader
import org.opencode.mobile.stt.NcnnModelValidator
import java.io.File

/**
 * Доставка моделей STT в пакет приложения САМИМ тестом.
 *
 * Зачем. Раньше модели заливал скрипт снаружи через `run-as`, и это работало
 * только пока пакет был debuggable. Ради `run-as` в minVerify стояло
 * `isDebuggable = true`, а AGP отключает optimization и obfuscation для
 * debuggable сборок — то есть R8 не запускался вообще, и проверка minVerify
 * была пустой (см. комментарий к build type в app/build.gradle.kts).
 *
 * Теперь `isDebuggable = false` → R8 работает по-настоящему, но `run-as` больше
 * не доступен. Выход: инструментация исполняется в процессе приложения, то есть
 * в его же uid, поэтому копирует модели сама. Источник — `/data/local/tmp`,
 * который доступен всем uid. На `/sdcard` нельзя: FUSE на ColorOS, `run-as` его
 * не видит (и раньше не видел).
 *
 * Скрипт-обёртка делает `adb push` в /data/local/tmp и запускает инструментацию.
 * Копирование идемпотентно: если комплект уже на месте и валиден, ничего не
 * трогаем - повторный прогон стоит ноль секунд на модельную часть.
 *
 * Порядок, который обязан соблюдать скрипт: push в /data/local/tmp ПРОИЗВОЛЬНЫЙ
 * по времени, а вот установка APK - нет. На ColorOS `adb install -r` стирает
 * data-директории пакета, то есть вместе с уже скопированными моделями. Поэтому
 * скрипт ставит APK первым, а модели кладёт в /data/local/tmp после.
 */
object SttModelBootstrap {
    private const val TAG = "STTBOOT"

    /** Куда скрипт кладёт модели через adb push. Читается любым uid. */
    const val STAGE = "/data/local/tmp/ocmodels"

    /** Каталоги нужны оба: турбо (int8+fp32) и fp32-конверт без int8-энкодера. */
    val REQUIRED_DIRS = listOf("ncnn-turbo", "ncnn-bench-fp32")

    /**
     * Копирует недостающие модели из [STAGE] в files/models. Возвращает true,
     * если после вызова всё на месте.
     *
     * Не бросает исключений и не валит тест: если моделей нет ни там, ни там,
     * вызывающий код увидит свой привычный assumeTrue с честным списком
     * недостающего. Бутстрап не должен подменять диагностику, он её готовит.
     */
    fun ensure(context: Context): Boolean {
        val modelsRoot = ModelDownloader.modelsDir(context)
        modelsRoot.mkdirs()
        var allOk = true

        for (name in REQUIRED_DIRS) {
            val dest = File(modelsRoot, name)
            if (NcnnModelValidator.checkModelDir(dest, MODEL_TAG).ok) {
                Log.i(TAG, "ensure: $name уже на месте, пропускаю")
                continue
            }

            val src = File(STAGE, name)
            if (!src.isDirectory) {
                Log.w(TAG, "ensure: нет источника $src - доставьте модели через adb push в $STAGE")
                allOk = false
                continue
            }
            // Источник проверяем ДО копирования: половинчатая копия хуже
            // отсутствия - тест упал бы уже на середине матрицы с невнятной
            // ошибкой, и потерялось бы то, что он измеряет.
            val srcCheck = NcnnModelValidator.checkModelDir(src, MODEL_TAG)
            if (!srcCheck.ok) {
                Log.w(TAG, "ensure: источник $src неполон, missing=${srcCheck.missing}")
                allOk = false
                continue
            }

            copyTree(src, dest)
            val after = NcnnModelValidator.checkModelDir(dest, MODEL_TAG)
            if (after.ok) {
                Log.i(TAG, "ensure: $name скопирован из $STAGE (${dest.length() / 1024 / 1024} МБ)")
            } else {
                Log.w(TAG, "ensure: ПОСЛЕ копирования $name неполон, missing=${after.missing}")
                allOk = false
            }
        }
        return allOk
    }

    /**
     * Копирует CSV-бенч наружу, чтобы хост читал его без `run-as`
     * (isDebuggable=false его больше не даёт).
     *
     * ГЛАВНЫЙ канал - external filesDir, то есть
     * `/sdcard/Android/data/<applicationId>/files/bench`. Каталог принадлежит
     * uid приложения (ext_data_rw), поэтому писать в него может сам тест, а
     * читать может adb shell. Обе стороны делают одно и то же и ни одна не
     * упирается в SELinux. Проверено на устройстве 28.09: `adb shell` создаёт
     * и читает файл в этом каталоге без единого chmod.
     *
     * ПОЧЕМУ НЕ /data/local/tmp, который стоял здесь раньше. Там
     * drwxrwx--x shell:shell, контекст u:object_r:shell_data_file:s0, SELinux
     * Enforcing. В группе "others" у приложения только --x: читать модели
     * можно, создать файл - нет (EACCES). chmod 777 не помогает, режет SELinux.
     * На прогоне 28.09 канал отвалился ровно так, как и предсказывали прежние
     * комментарии, - и вместе с ним умер весь разбор CSV, потому что второго
     * канала, переживающего многострочную выгрузку, не было.
     *
     * Имя выходного файла берётся у [csv], а не задаётся здесь константой.
     * Раньше тут стояло `File(STAGE, "stt-bench.csv")`, и это работало ровно до
     * разделения матрицы на два прогона: int8 и fp32 писали бы в ОДИН файл, и
     * более поздний перезаписывал бы более ранний. Молчащая потеря половины
     * измерений - худший вид поломки, поэтому имя теперь единственный источник
     * правды о том, чей это результат.
     *
     * [STAGE] остаётся вторым каналом: на некоторых прошивках external
     * storage может быть смонтирован иначе, и лишняя попытка ничего не стоит.
     * Возвращает true, если ушёл хотя бы один.
     */
    fun publishCsv(
        context: Context,
        csv: File,
    ): Boolean {
        val targets = mutableListOf<File>()
        externalBenchDir(context)?.let { targets += File(it, csv.name) }
        targets += File(STAGE, csv.name)

        var ok = false
        for (out in targets) {
            try {
                out.parentFile?.mkdirs()
                csv.copyTo(out, overwrite = true)
                Log.i(TAG, "publishCsv: ${csv.absolutePath} -> ${out.absolutePath}")
                ok = true
            } catch (t: Throwable) {
                Log.w(TAG, "publishCsv не удался в ${out.absolutePath}: ${t.message}")
            }
        }
        return ok
    }

    /**
     * Путь на устройстве, откуда хост забирает CSV. Дублирует решение
     * [publishCsv] на стороне хоста: если здесь путь разойдётся с тем, куда
     * реально пишет тест, канал молча вернёт пустой файл - ровно тот класс
     * поломки, который и чинится. Поэтому оба места обязаны читать одну
     * константу.
     */
    fun externalBenchDir(context: Context): File? = context.getExternalFilesDir("bench")

    /** Префикс файлов модели: тот же, что ждёт [NcnnModelValidator]. */
    private const val MODEL_TAG = "whisper_turbo"

    private fun copyTree(
        src: File,
        dest: File,
    ) {
        if (dest.exists()) dest.deleteRecursively()
        dest.mkdirs()
        src.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                copyTree(child, File(dest, child.name))
            } else {
                child.copyTo(File(dest, child.name), overwrite = true)
            }
        }
    }
}
