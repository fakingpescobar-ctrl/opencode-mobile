package org.opencode.mobile.ui.chat

import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.opencode.mobile.ui.CTX_PREFIX
import org.opencode.mobile.ui.GaugeBarGap
import org.opencode.mobile.ui.GaugeColorKey
import org.opencode.mobile.ui.GaugePalette
import org.opencode.mobile.ui.GaugeShape
import org.opencode.mobile.ui.ZEN_PREFIX
import org.opencode.mobile.ui.readGaugeBarGap
import org.opencode.mobile.ui.readGaugePalette
import org.opencode.mobile.ui.readGaugeShape
import org.opencode.mobile.ui.writeGaugeBarGap
import org.opencode.mobile.ui.writeGaugeColor
import org.opencode.mobile.ui.writeGaugeShape

/**
 * Внешний вид ленты: шрифт и цвет ответов модели плюс форма, палитра и отступ полос
 * расхода. Вынесено из `ChatOverlay` — там эти шесть значений жили среди поллинга,
 * STT и TTS, и проверить правила их записи можно было только руками на устройстве.
 *
 * Главное правило, которое здесь держится и проверяется тестом: значение меняется
 * на каждом кадре перетаскивания ползунка, а в prefs пишется только по завершении
 * жеста ([persistCtxShape], [persistZenShape], [persistBarGap]). Запись в файл на
 * каждое движение — это лишний ввод-вывод в UI-потоке; соблазн «писать сразу»
 * при рефакторинге выглядит безобидно и заметен только по лагам на телефоне.
 *
 * Цвет модели — исключение: пикер отдаёт готовый цвет, промежуточных состояний нет,
 * поэтому [chooseColor] пишет сразу.
 *
 * Хранилище спрятано за [AppearanceStore], чтобы тест был без Android: проверяется
 * поведение (когда и что пишется), а не SharedPreferences.
 */
@Stable
internal class ChatAppearance(
    private val store: AppearanceStore,
) {
    var fontKey by mutableStateOf(store.readFontKey())
        private set

    var colorHex by mutableStateOf(store.readColorHex())
        private set

    var ctxShape by mutableStateOf(store.readGaugeShape(CTX_PREFIX))

    var zenShape by mutableStateOf(store.readGaugeShape(ZEN_PREFIX))

    var palette by mutableStateOf(store.readGaugePalette())
        private set

    var barGap by mutableStateOf(store.readGaugeBarGap())

    fun chooseFont(key: String) {
        fontKey = key
        store.writeFontKey(key)
    }

    fun chooseColor(hex: String) {
        colorHex = hex
        store.writeColorHex(hex)
    }

    /** Вызывать по завершении перетаскивания, а не на каждом кадре. */
    fun persistCtxShape() = store.writeGaugeShape(CTX_PREFIX, ctxShape)

    /** Вызывать по завершении перетаскивания, а не на каждом кадре. */
    fun persistZenShape() = store.writeGaugeShape(ZEN_PREFIX, zenShape)

    /** Вызывать по завершении перетаскивания, а не на каждом кадре. */
    fun persistBarGap() = store.writeGaugeBarGap(barGap)

    /**
     * Цвет полосы меняется по клику в палитре — промежуточного состояния нет,
     * поэтому значение и запись идут вместе.
     */
    fun choosePaletteColor(
        key: GaugeColorKey,
        argb: Int,
    ) {
        palette = palette.copyWith(key, argb)
        store.writeGaugeColor(key, argb)
    }

    /** Сброс полос к заводским значениям: и в памяти, и в prefs — одним действием. */
    fun resetGauges() {
        ctxShape = GaugeShape.DEFAULT
        zenShape = GaugeShape.DEFAULT
        palette = GaugePalette.DEFAULT
        barGap = GaugeBarGap.DEFAULT
        persistCtxShape()
        persistZenShape()
        persistBarGap()
        GaugeColorKey.entries.forEach { store.writeGaugeColor(it, it.argb(GaugePalette.DEFAULT)) }
    }

    companion object {
        const val FONT_KEY = "model_font_key"
        const val DEFAULT_FONT = "mono"
        const val COLOR_KEY = "model_color"
        const val DEFAULT_COLOR = "D97706"
    }
}

/**
 * Ровно те операции с prefs, которые нужны [ChatAppearance]. Узкий порт, а не
 * SharedPreferences целиком: тест подставляет карту в памяти и не тащит Android.
 */
internal interface AppearanceStore {
    fun readFontKey(): String

    fun writeFontKey(key: String)

    fun readColorHex(): String

    fun writeColorHex(hex: String)

    fun readGaugeShape(prefix: String): GaugeShape

    fun writeGaugeShape(
        prefix: String,
        shape: GaugeShape,
    )

    fun readGaugePalette(): GaugePalette

    fun writeGaugeColor(
        key: GaugeColorKey,
        argb: Int,
    )

    fun readGaugeBarGap(): Int

    fun writeGaugeBarGap(dp: Int)
}

internal class SharedPreferencesAppearanceStore(
    private val prefs: SharedPreferences,
) : AppearanceStore {
    override fun readFontKey(): String =
        prefs.getString(ChatAppearance.FONT_KEY, ChatAppearance.DEFAULT_FONT) ?: ChatAppearance.DEFAULT_FONT

    override fun writeFontKey(key: String) = prefs.edit().putString(ChatAppearance.FONT_KEY, key).apply()

    override fun readColorHex(): String =
        prefs.getString(ChatAppearance.COLOR_KEY, ChatAppearance.DEFAULT_COLOR) ?: ChatAppearance.DEFAULT_COLOR

    override fun writeColorHex(hex: String) = prefs.edit().putString(ChatAppearance.COLOR_KEY, hex).apply()

    override fun readGaugeShape(prefix: String): GaugeShape = prefs.readGaugeShape(prefix)

    override fun writeGaugeShape(
        prefix: String,
        shape: GaugeShape,
    ) = prefs.writeGaugeShape(prefix, shape)

    override fun readGaugePalette(): GaugePalette = prefs.readGaugePalette()

    override fun writeGaugeColor(
        key: GaugeColorKey,
        argb: Int,
    ) = prefs.writeGaugeColor(key, argb)

    override fun readGaugeBarGap(): Int = prefs.readGaugeBarGap()

    override fun writeGaugeBarGap(dp: Int) = prefs.writeGaugeBarGap(dp)
}
