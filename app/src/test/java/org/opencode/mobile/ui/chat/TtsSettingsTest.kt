package org.opencode.mobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencode.mobile.tts.TtsConfig
import org.opencode.mobile.tts.TtsEngine

/**
 * Правила держателя настроек озвучки.
 *
 * Главное из них — «включённость не живёт отдельной переменной». Раньше рядом с
 * конфигом лежал независимый `ttsOn`, и после выключения они расходились:
 * `ttsOn=false`, а в конфиге всё ещё `Sherpa`. Поймать это можно было только
 * глазами в панели, поэтому здесь проверяется, что состояние после каждого
 * действия берётся из конфига.
 */
class TtsSettingsTest {
    @Test
    fun `выключение убирает движок из конфига`() {
        val store = FakeStore(base(engine = TtsEngine.Sherpa))
        val tts = TtsSettings(store)

        tts.setEnabled(false)

        assertEquals(false, tts.on)
        assertEquals(TtsEngine.Off, tts.config.engine)
        assertEquals(false, store.narratorEnabled)
    }

    @Test
    fun `включение перечитывает конфиг после нарратора`() {
        val store = FakeStore(base(engine = TtsEngine.Off))
        val tts = TtsSettings(store)

        tts.setEnabled(true)

        // Движок выбрал нарратор (модель на месте -> локальный), а не вызывающий код.
        assertEquals(true, tts.on)
        assertEquals(TtsEngine.Sherpa, tts.config.engine)
        assertEquals(true, store.narratorEnabled)
    }

    @Test
    fun `повторное включение не дёргает нарратор`() {
        val store = FakeStore(base(engine = TtsEngine.Sherpa))
        val tts = TtsSettings(store)

        tts.setEnabled(true)

        assertNull(store.narratorEnabled)
    }

    @Test
    fun `смена движка сохраняется и останавливает нарратор`() {
        val store = FakeStore(base(engine = TtsEngine.Sherpa))
        val tts = TtsSettings(store)

        tts.chooseEngine(TtsEngine.ElevenLabs)

        assertEquals(TtsEngine.ElevenLabs, tts.config.engine)
        assertEquals(TtsEngine.ElevenLabs, store.config.engine)
        assertEquals(1, store.stops)
    }

    @Test
    fun `правка поля сразу уходит в хранилище`() {
        val store = FakeStore(base(engine = TtsEngine.Sherpa))
        val tts = TtsSettings(store)

        tts.update { it.copy(sid = 7, dumpPcm = true) }

        assertEquals(7, tts.config.sid)
        assertEquals(true, store.config.dumpPcm)
        assertTrue(store.saves > 0)
    }

    private fun base(engine: TtsEngine) =
        TtsConfig(
            engine = engine,
            model = "supertonic",
            sid = 0,
            speechRate = 1f,
        )

    /**
     * Хранилище в памяти. `setNarratorEnabled` ведёт себя как настоящий нарратор:
     * сам решает движок и сохраняет конфиг — именно поэтому держатель обязан
     * перечитать его после включения.
     */
    private class FakeStore(
        var config: TtsConfig,
    ) : TtsStore {
        var narratorEnabled: Boolean? = null
        var saves = 0
        var stops = 0

        override fun readConfig(): TtsConfig = config

        override fun saveConfig(config: TtsConfig) {
            this.config = config
            saves++
        }

        override fun setNarratorEnabled(enabled: Boolean) {
            narratorEnabled = enabled
            config = config.copy(engine = if (enabled) TtsEngine.Sherpa else TtsEngine.Off)
        }

        override fun stopNarrator() {
            stops++
        }
    }
}
