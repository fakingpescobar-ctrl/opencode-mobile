package org.opencode.mobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencode.mobile.ui.CTX_PREFIX
import org.opencode.mobile.ui.GaugeBarGap
import org.opencode.mobile.ui.GaugeColorKey
import org.opencode.mobile.ui.GaugePalette
import org.opencode.mobile.ui.GaugeShape
import org.opencode.mobile.ui.ZEN_PREFIX

/**
 * Правила записи настроек внешнего вида в prefs.
 *
 * Проверяется не «значение сохранилось», а **когда** оно сохраняется: ползунок
 * полос меняет значение на каждом кадре перетаскивания, а в файл пишет только по
 * завершении жеста. Это решение по производительности (ввод-вывод в UI-потоке),
 * и оно не видно ни в типах, ни в названиях — только в тесте. Соблазн «писать
 * сразу, так короче» при следующем рефакторинге выглядит безобидно.
 */
class ChatAppearanceTest {
    private val store = FakeStore()
    private val appearance = ChatAppearance(store)

    @Test
    fun `при создании берёт сохранённые значения`() {
        val saved = FakeStore().apply {
            fontKey = "serif"
            colorHex = "00FF00"
            shapes[CTX_PREFIX] = GaugeShape(heightDp = 12, cells = 40, cellGapDp = 1f, lean = 0.2f)
            shapes[ZEN_PREFIX] = GaugeShape(heightDp = 6, cells = 20, cellGapDp = 0f, lean = 1f)
            barGap = 9
            colors[GaugeColorKey.TRACK] = 0xFF010203.toInt()
        }

        val restored = ChatAppearance(saved)

        assertEquals("serif", restored.fontKey)
        assertEquals("00FF00", restored.colorHex)
        assertEquals(GaugeShape(heightDp = 12, cells = 40, cellGapDp = 1f, lean = 0.2f), restored.ctxShape)
        assertEquals(GaugeShape(heightDp = 6, cells = 20, cellGapDp = 0f, lean = 1f), restored.zenShape)
        assertEquals(9, restored.barGap)
        assertEquals(0xFF010203.toInt(), restored.palette.track)
    }

    @Test
    fun `перетаскивание ползунков не пишет в prefs до конца жеста`() {
        appearance.ctxShape = GaugeShape(heightDp = 12, cells = 40, cellGapDp = 1f, lean = 0.2f)
        appearance.zenShape = GaugeShape(heightDp = 6, cells = 20, cellGapDp = 0f, lean = 1f)
        appearance.barGap = 10

        assertTrue("ползунок не должен писать в prefs на каждом кадре", store.shapeWrites.isEmpty())
        assertEquals("отступ тоже пишется только по отпусканию", 0, store.barGapWrites)

        appearance.persistCtxShape()
        appearance.persistZenShape()
        appearance.persistBarGap()

        assertEquals(listOf(CTX_PREFIX, ZEN_PREFIX), store.shapeWrites)
        assertEquals(1, store.barGapWrites)
        assertEquals(10, store.barGap)
    }

    @Test
    fun `смена цвета и шрифта пишется сразу`() {
        appearance.chooseColor("00FF00")
        appearance.chooseFont("serif")

        assertEquals("00FF00", appearance.colorHex)
        assertEquals("00FF00", store.colorHex)
        assertEquals("serif", appearance.fontKey)
        assertEquals("serif", store.fontKey)
    }

    @Test
    fun `клик по палитре пишет только изменённый цвет`() {
        appearance.choosePaletteColor(GaugeColorKey.CTX_HIGH, 0xFF112233.toInt())

        assertEquals(0xFF112233.toInt(), appearance.palette.ctxHigh)
        assertEquals(listOf(GaugeColorKey.CTX_HIGH), store.colorWrites)
        assertEquals(0xFF112233.toInt(), store.colors[GaugeColorKey.CTX_HIGH])
    }

    @Test
    fun `сброс возвращает заводские значения и пишет их все`() {
        appearance.ctxShape = GaugeShape(heightDp = 12, cells = 40, cellGapDp = 1f, lean = 0.2f)
        appearance.zenShape = GaugeShape(heightDp = 6, cells = 20, cellGapDp = 0f, lean = 1f)
        appearance.barGap = 10
        appearance.choosePaletteColor(GaugeColorKey.TRACK, 0xFF112233.toInt())

        appearance.resetGauges()

        assertEquals(GaugeShape.DEFAULT, appearance.ctxShape)
        assertEquals(GaugeShape.DEFAULT, appearance.zenShape)
        assertEquals(GaugeBarGap.DEFAULT, appearance.barGap)
        assertEquals(GaugePalette.DEFAULT, appearance.palette)
        assertEquals(GaugeShape.DEFAULT, store.shapes[CTX_PREFIX])
        assertEquals(GaugeShape.DEFAULT, store.shapes[ZEN_PREFIX])
        assertEquals(GaugeBarGap.DEFAULT, store.barGap)
        GaugeColorKey.entries.forEach { key ->
            assertEquals("цвет $key не сброшен в prefs", key.argb(GaugePalette.DEFAULT), store.colors[key])
        }
    }

    /**
     * Хранилище в памяти: тест не тащит Android, а проверяет именно поведение
     * держателя состояния — что и в каком порядке уходит в prefs.
     */
    private class FakeStore : AppearanceStore {
        var fontKey: String = ChatAppearance.DEFAULT_FONT
        var colorHex: String = ChatAppearance.DEFAULT_COLOR
        val shapes = mutableMapOf<String, GaugeShape>()
        val colors = mutableMapOf<GaugeColorKey, Int>()
        var barGap: Int = GaugeBarGap.DEFAULT
        val shapeWrites = mutableListOf<String>()
        val colorWrites = mutableListOf<GaugeColorKey>()
        var barGapWrites = 0

        override fun readFontKey(): String = fontKey

        override fun writeFontKey(key: String) {
            fontKey = key
        }

        override fun readColorHex(): String = colorHex

        override fun writeColorHex(hex: String) {
            colorHex = hex
        }

        override fun readGaugeShape(prefix: String): GaugeShape = shapes[prefix] ?: GaugeShape.DEFAULT

        override fun writeGaugeShape(
            prefix: String,
            shape: GaugeShape,
        ) {
            shapes[prefix] = shape
            shapeWrites += prefix
        }

        override fun readGaugePalette(): GaugePalette = GaugePalette.from(colors)

        override fun writeGaugeColor(
            key: GaugeColorKey,
            argb: Int,
        ) {
            colors[key] = argb
            colorWrites += key
        }

        override fun readGaugeBarGap(): Int = barGap

        override fun writeGaugeBarGap(dp: Int) {
            barGap = dp
            barGapWrites++
        }
    }
}
