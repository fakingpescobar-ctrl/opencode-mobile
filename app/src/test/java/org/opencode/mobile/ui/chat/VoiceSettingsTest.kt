package org.opencode.mobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import org.opencode.mobile.stt.SttModels

/**
 * Правила чтения настроек голосового ввода.
 *
 * Два из них раньше жили только в комментариях и проверялись руками на телефоне:
 * миграция выпиленного движка `ncnn` и отбраковка модели, которой больше нет в
 * каталоге. Первое — про людей, у которых на диске осталось значение от версии
 * до 14.10.2026; второе — про «выбрано turbo, а распознаёт base».
 */
class VoiceSettingsTest {
    private val store = FakeStore()
    private val voice = VoiceSettings(store)

    @Test
    fun `сохранённый ncnn читается как whisper`() {
        val migrated = VoiceSettings(FakeStore(engine = "ncnn"))

        assertEquals(VoiceEngine.WHISPER, migrated.sttEngine)
    }

    @Test
    fun `выбор ncnn в панели не сохраняется как ncnn`() {
        voice.chooseEngine("ncnn")

        assertEquals(VoiceEngine.WHISPER, voice.sttEngine)
        assertEquals(VoiceEngine.WHISPER, store.engine)
    }

    @Test
    fun `выбор движка сохраняется`() {
        voice.chooseEngine(VoiceEngine.SYSTEM)

        assertEquals(VoiceEngine.SYSTEM, voice.sttEngine)
        assertEquals(VoiceEngine.SYSTEM, store.engine)
    }

    @Test
    fun `модель из каталога читается и сохраняется`() {
        voice.chooseWhisperModel(SttModels.PARAKEET_ID)

        assertEquals(SttModels.PARAKEET_ID, voice.whisperModelId)
        assertEquals(SttModels.PARAKEET_ID, store.whisperModelId)
        assertEquals(SttModels.PARAKEET_ID, voice.sttModel())
    }

    @Test
    fun `модель, которой нет в каталоге, уводится на дефолт`() {
        voice.chooseWhisperModel("turbo-которого-нет")

        assertEquals(SttModels.DEFAULT_ID, voice.whisperModelId)
        assertEquals(SttModels.DEFAULT_ID, store.whisperModelId)
    }

    @Test
    fun `сохранённая модель, которой нет в каталоге, читается как дефолт`() {
        val restored = VoiceSettings(FakeStore(whisperModelId = "удалённая-модель"))

        assertEquals(SttModels.DEFAULT_ID, restored.whisperModelId)
    }

    /**
     * Хранилище в памяти: тест не тянет Android и проверяет держатель состояния.
     */
    private class FakeStore(
        var engine: String? = VoiceEngine.SYSTEM,
        var whisperModelId: String? = SttModels.DEFAULT_ID,
    ) : VoiceStore {
        override fun readEngine(): String? = engine

        override fun writeEngine(engine: String) {
            this.engine = engine
        }

        override fun readWhisperModelId(): String? = whisperModelId

        override fun writeWhisperModel(id: String) {
            whisperModelId = id
        }
    }
}
