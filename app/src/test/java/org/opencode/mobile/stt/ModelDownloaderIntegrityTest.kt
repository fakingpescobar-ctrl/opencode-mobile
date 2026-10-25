package org.opencode.mobile.stt

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Сверка файла модели с манифестом SHA-256.
 *
 * Это ответ на вопрос «можно ли запускать распознавание этим файлом». Ошибка в
 * любую сторону видна человеку: ложный CORRUPT требует скачать модель заново,
 * ложный VALID отдаёт повреждённый файл в whisper.cpp.
 *
 * Тесты на настоящей файловой системе (временный каталог), без Robolectric:
 * проверяется ровно чтение sidecar и сравнение хешей.
 */
class ModelDownloaderIntegrityTest {
    private val dir: File = Files.createTempDirectory("model-integrity-test").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `файл с верным манифестом — VALID`() {
        val file = model("model-bytes")

        manifest(file, ModelDownloader.sha256Of(file)!!)

        assertEquals(ModelDownloader.ModelIntegrity.VALID, ModelDownloader.checkIntegrity(file))
    }

    @Test
    fun `другой хеш в манифесте — CORRUPT`() {
        val file = model("model-bytes")

        manifest(file, "0".repeat(64))

        assertEquals(ModelDownloader.ModelIntegrity.CORRUPT, ModelDownloader.checkIntegrity(file))
    }

    @Test
    fun `без манифеста — NO_MANIFEST`() {
        val file = model("model-bytes")

        assertEquals(ModelDownloader.ModelIntegrity.NO_MANIFEST, ModelDownloader.checkIntegrity(file))
    }

    @Test
    fun `оборванный манифест не считается повреждением`() {
        val file = model("model-bytes")

        // Так выглядит writeText, который прервали на середине: файл есть, но
        // это не хеш. Блокировать загрузку из-за него нельзя.
        manifest(file, ModelDownloader.sha256Of(file)!!.take(20))

        assertEquals(ModelDownloader.ModelIntegrity.NO_MANIFEST, ModelDownloader.checkIntegrity(file))
    }

    @Test
    fun `пустой файл — NO_MANIFEST, а не повреждение`() {
        val file = model("")

        assertEquals(ModelDownloader.ModelIntegrity.NO_MANIFEST, ModelDownloader.checkIntegrity(file))
    }

    @Test
    fun `манифест в верхнем регистре не считается повреждением`() {
        val file = model("model-bytes")

        // HEX_64 принимает A-F, значит такой манифест валиден по формату. Сравнивать
        // его с хешем (он всегда в нижнем регистре) надо без учёта регистра, иначе
        // валидный манифест превращается в ложный CORRUPT и модель просят скачать
        // заново.
        manifest(file, ModelDownloader.sha256Of(file)!!.uppercase())

        assertEquals(ModelDownloader.ModelIntegrity.VALID, ModelDownloader.checkIntegrity(file))
    }

    private fun model(content: String): File = File(dir, "ggml-small.bin").apply { writeText(content) }

    private fun manifest(
        file: File,
        hex: String,
    ) {
        File(dir, "${file.name}.sha256").writeText(hex)
    }
}
