package org.opencode.mobile.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Каталог моделей — это контракт, а не деталь реализации: от него зависят и
 * выбор в настройках, и кнопки в диагностике, и сообщение об ошибке при
 * попытке распознать речь нескачанной моделью. Тесты держат три вещи, которые
 * уже ломались: вычеркнутые модели не должны вернуться в список, у каждой
 * модели должна быть честная подпись, а дефолт — реально присутствовать в
 * каталоге (иначе чистая установка падает с «неизвестная модель»).
 */
class SttModelsTest {
    @Test
    fun `дефолт есть в каталоге`() {
        assertNotNull("модель по умолчанию обязана быть в каталоге", SttModels.byId(SttModels.DEFAULT_ID))
    }

    @Test
    fun `идентификаторы уникальны`() {
        val ids = SttModels.all.map { it.id }
        assertEquals("дубликат модели в каталоге", ids.size, ids.toSet().size)
    }

    @Test
    fun `у каждой модели есть подпись и размер`() {
        for (m in SttModels.all) {
            assertTrue("пустая подпись у ${m.id}", m.note.isNotBlank())
            assertTrue("пустой размер у ${m.id}", m.sizeLabel.isNotBlank())
            assertTrue("подозрительно мал размер у ${m.id}", m.bytes > 10L * 1024 * 1024)
        }
    }

    @Test
    fun `неизвестная модель не находится`() {
        assertNull(SttModels.byId("nosuchmodel"))
        assertEquals("nosuchmodel", SttModels.labelOf("nosuchmodel"))
    }

    /**
     * tiny и turbo-q5_0 вычеркнуты 14.10.2026 по замерам: на Adreno 840
     * квантованная turbo даёт мусор (rtf 47,64), tiny ошибается в 60 % слов.
     * Возвращать их без новых замеров незачем — тест фиксирует решение.
     */
    @Test
    fun `вычеркнутые модели не вернулись`() {
        val ids = SttModels.all.map { it.id }
        assertTrue("turbo-q5_0 больше не в каталоге", "turbo" !in ids)
        assertTrue("tiny больше не в каталоге", "tiny" !in ids)
    }

    /**
     * Раньше здесь было простое равенство `all.size == fileNames.size` — оно
     * работало, пока каждая модель была одним ggml-файлом в modelsDir. Parakeet
     * разрешён набором из 4 файлов в отдельном каталоге и в filesDir/models
     * не лежит вовсе, поэтому равенство ушло.
     *
     * Смысл проверки прежний: белый список [SttModels.fileNames] должен
     * покрывать ровно те модели, что лежат одиночным файлом в modelsDir, —
     * иначе [ModelDownloader.deleteOtherModels] сотрёт живую модель.
     */
    @Test
    fun `имена файлов совпадают с числом моделей`() {
        val flat = SttModels.all.mapNotNull { m -> m.flatFileName }
        assertEquals(
            "белый список удаления разошёлся с моделями, лежащими файлом в modelsDir",
            flat.toSet(),
            SttModels.fileNames,
        )
    }
}
