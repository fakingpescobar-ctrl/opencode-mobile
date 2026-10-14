package org.opencode.mobile.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Каталог модели озвучки: список файлов против списка, который требует движок.
 *
 * Главное здесь одно: то, что качает [TtsModelDownloader], обязано совпадать
 * с тем, что проверяет движок при загрузке ([TtsModels.SUPERTONIC_FILES]).
 * Пока эти списки жили отдельно, модель можно было скачать «успешно» и всё
 * равно получить тишину: движок требует семь файлов, а загрузчик их качает.
 *
 * Тесты без Robolectric: там нет Android-контекста, а проверять нужно
 * согласованность каталогов, а не файловую систему.
 */
class TtsModelDownloaderTest {
    @Test
    fun `skachivaemye fayly sovpadayut s trebovaniyami dvizhka`() {
        assertEquals(TtsModels.SUPERTONIC_FILES.sorted(), TtsModelDownloader.FILES.map { it.name }.sorted())
    }

    @Test
    fun `u kazhdogo fayla tochnyy razmer i sha256`() {
        for (file in TtsModelDownloader.FILES) {
            assertTrue("${file.name}: нулевой размер", file.bytes > 0L)
            assertEquals("${file.name}: sha256 должен быть 64 hex-символа", 64, file.sha256.length)
            assertTrue("${file.name}: sha256 не hex", file.sha256.all { it in "0123456789abcdef" })
        }
    }

    @Test
    fun `imena faylov unikalny`() {
        assertEquals(
            TtsModelDownloader.FILES.size,
            TtsModelDownloader.FILES
                .map { it.name }
                .toSet()
                .size
        )
    }

    @Test
    fun `summa ravna summme katalogovogo spiska`() {
        val sum = TtsModelDownloader.FILES.sumOf { it.bytes }
        assertEquals(TtsModelDownloader.TOTAL_BYTES, sum)
        // 139 МБ: ровно столько показывает кнопка. Меньше 100 МБ — модель
        // сломанная, больше 200 МБ — в каталог попал лишний файл.
        assertTrue("модель выглядит подозрительно: $sum", sum in 100_000_000L..200_000_000L)
    }

    @Test
    fun `metka razmera chelovekoponjatna`() {
        assertEquals("139 МБ", TtsModelDownloader.sizeLabel())
    }

    @Test
    fun `katalog odin i tot zhe`() {
        // Загрузчик и движок должны смотреть в один каталог, иначе скачанное
        // не найдётся. Имя каталога задаёт движок — загрузчик берёт его оттуда.
        assertEquals(TtsModels.SUPERTONIC, "supertonic-3-tts-int8")
    }
}
