@file:Suppress("MagicNumber")

package org.opencode.mobile.engine

/**
 * Один файл движка: имя в каталоге, точный размер и sha256.
 *
 * Размер проверяется при каждой готовности, sha256 - только сразу после
 * скачивания. Повторный хеш 49 МБ на телефоне стоит сотни миллисекунд,
 * а готовность спрашивают постоянно; результат проверки хеша закрепляется
 * маркером `.ok`, который [EngineStore.isReady] тоже учитывает.
 *
 * ВАЖНО, из-за чего проверка размера строгая: она же объявляет движок
 * «не установлен». Значит зашитый тут размер обязан совпадать с той
 * сборкой, которую мы реально гоняем на устройстве, а не с той, что была
 * опубликована в релиз при первом раскладе. Меняем движок - правим
 * размер и хеш здесь И перезаливаем файл в релиз `engines-v1`, иначе
 * приложение скачает старую сборку, а покажет «не установлена».
 * Сверяется так: размер и sha256 файла из каталога сборки `obj/arm64-v8a/`
 * внутри `whisperlib/build/intermediates/cxx/Release/`.
 */
internal data class EngineFile(
    val name: String,
    val bytes: Long,
    val sha256: String,
)

/**
 * Движок: что качать, куда класть и в каком порядке загружать.
 *
 * [files] перечислены в порядке загрузки, а не в алфавитном: зависимости
 * должны быть подгружены раньше тех, кто на них ссылается, иначе линкер
 * не найдёт `libglslang.so` для `libncnnwhisper.so`.
 *
 * Версии и хеши зафиксированы здесь, а не берутся из манифеста на сервере:
 * подмена движка = подмена кода, который получает аудио пользователя,
 * поэтому источник истины должен лежать в приложении.
 *
 * libonnxruntime.so в списке нет намеренно: onnxruntime влинкован в
 * libsherpa-onnx-jni.so статически, `readelf -d` не показывает его в
 * NEEDED, и никто в приложении его не грузит. Раньше он занимал в APK
 * 22 МБ впустую.
 */
internal enum class Engine(
    val dirName: String,
    val label: String,
    val files: List<EngineFile>,
    /**
     * true - движок лежит в самом APK и качать его не нужно.
     *
     * Озвучка пока такая: libsherpa-onnx-jni.so остаётся нативом в APK
     * (24 МБ из 228, терпимо), и `System.loadLibrary` его находит сам.
     * Распознавание переехало на скачивание, потому что его цепочка
     * весит 79 МБ. Пока флаг стоит, интерфейс обязан говорить «в
     * комплекте», а не «не установлена»: иначе он просит скачать то,
     * что уже работает, и человек теряет к нему доверие.
     */
    val bundled: Boolean = false,
) {
    /** Распознавание речи: ncnn + Vulkan, GPU-ускоренный путь. */
    STT(
        dirName = "stt",
        label = "Распознавание (ncnn)",
        files = listOf(
            EngineFile("libSPIRV.so", 6_168, "e7cca3626841ad42a9927b0c3efd9f3a70d2ab13746436762a92ca14bcd3f11c"),
            EngineFile("libglslang.so", 33_817_456, "e56ae5af09e16d7325d03e608bf9039fffa4e6347f08d16c132e8f344ba89235"),
            EngineFile("libncnnwhisper.so", 49_972_488, "9a0b4b1c5097abe58e59205871c9753fbb5b765efe5b7c68fbae0951613ca4b4"),
        ),
    ),

    /**
     * Второй движок распознавания: whisper.cpp, собранный с GGML_VULKAN=ON.
     *
     * Он же был собран под Adreno изначально - комментарий в
     * whisperlib/build.gradle:68 так и говорит про "Vulkan-режим для GPU
     * (Adreno GPU)". Мы его не выбрасывали, а просто не давали выбрать:
     * движок никогда не упаковывался в APK.
     *
     * Стоит держать рядом с ncnn, потому что ncnn-Vulkan на этом Adreno
     * падает в SIGSEGV: измерено четыре раза, всегда на энкодере, всегда
     * по одному адресу. ggml идёт другим путём и в fp16, поэтому
     * сломанный int8-Vulkan его не касается.
     *
     * [files] идёт строго в порядке загрузки: сначала то, на что
     * ссылаются следующие. libomp нужен ggml-base, ggml-base нужен
     * всем остальным кускам, и только потом сам libwhisper.so.
     */
    WHISPER(
        dirName = "whisper",
        label = "Распознавание (whisper.cpp)",
        files = listOf(
            EngineFile("libomp.so", 1_229_304, "ea27efff687f8359fabc728af9a4ea45766714399792344af94b09037d52d0b0"),
            EngineFile("libggml-base.so", 6_645_096, "eb9bcc53ba1afd328ec79084b5d2afdb394922731e64b55bcf5bfd5e82915040"),
            EngineFile("libggml-cpu.so", 4_225_832, "e001d6ed5b62518be6599fce14a3b78a18cfa5d8255791fa2987252955e0d2df"),
            EngineFile("libggml-vulkan.so", 71_780_504, "082d1d01ecd41def08fa5317b22db8d78722141dd49d09b1f7b11cdb3dc429bd"),
            EngineFile("libggml.so", 617_400, "90af33ff6d79805cbe55557fba3ca26143c53391dc67abf30d266baa289224b3"),
            EngineFile("libwhisper.so", 6_086_864, "2a67d53df815e30965639515621095ab9b9850ef17c673d842339d033bfd60e3"),
        ),
    ),

    /** Озвучка: sherpa-onnx, работает офлайн, лежит в APK. */
    TTS(
        dirName = "tts",
        label = "Озвучка (sherpa)",
        bundled = true,
        files = listOf(
            EngineFile("libsherpa-onnx-jni.so", 24_169_352, "b4a53d915c19cb6d09b1cdc9a8084be54419b647aab9097ffdfb46f30c3c4dd8"),
        ),
    ),
    ;

    /**
     * Имя библиотеки для `System.loadLibrary`: имя файла без префикса `lib`
     * и суффикса `.so` (`libsherpa-onnx-jni.so` -> `sherpa-onnx-jni`).
     *
     * Выводится из [files], а не хранится отдельно: у встроенного движка он
     * всегда один, иначе появление второго файла тихо сломало бы загрузку.
     * Имеет смысл только при [bundled] — остальные грузятся по абсолютному пути.
     */
    val libName: String
        get() = files
            .single()
            .name
            .removePrefix("lib")
            .removeSuffix(".so")

    /** Суммарный размер: показываем его до начала скачивания. */
    val totalBytes: Long get() = files.sumOf { it.bytes }
}
