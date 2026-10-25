package org.opencode.mobile.ui.chat

import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.opencode.mobile.stt.SttModels

/**
 * Движок распознавания и модель whisper.cpp — настройки голосового ввода.
 *
 * Вынесено из `ChatOverlay` не ради строк: здесь два правила, которые раньше жили
 * в комментариях и проверялись только руками на телефоне.
 *
 * 1. `ncnn` выпилен 14.10.2026 вместе с моделью на 1,2 ГБ (SIGSEGV на энкодере
 *    Adreno, на CPU вчетверо медленнее whisper.cpp; таблицы — docs/VOICE-REPORT.md).
 *    У людей на диске осталось `stt_engine=ncnn`; значение обязано мигрировать
 *    на `whisper`, иначе настройка указывает в движок, которого больше нет.
 * 2. Модель читается из prefs **в момент записи**, а не из снимка на старте
 *    приложения. Копия в `remember` замораживалась: скачал turbo, перевыбрал —
 *    распознавание всё равно шло на base. Здесь единственный писатель — панель,
 *    поэтому значение в держателе и есть текущая настройка.
 *
 * Хранилище за узким [VoiceStore]: тест подставляет карту в памяти и не тащит Android.
 */
@Stable
internal class VoiceSettings(
    private val store: VoiceStore,
) {
    var sttEngine by mutableStateOf(VoiceEngine.normalize(store.readEngine()))
        private set

    var whisperModelId by mutableStateOf(VoiceEngine.knownModel(store.readWhisperModelId()))
        private set

    fun chooseEngine(engine: String) {
        sttEngine = VoiceEngine.normalize(engine)
        store.writeEngine(sttEngine)
    }

    /** Модель для распознавания: ровно то, что выбрано в панели. */
    fun sttModel(): String = whisperModelId

    /**
     * Выбор модели. Неизвестный id (модель убрали из каталога) уводится на дефолт
     * тем же правилом, что и при чтении: иначе распознавание молча шло бы не той
     * моделью, которую человек видит выбранной.
     */
    fun chooseWhisperModel(id: String) {
        whisperModelId = VoiceEngine.knownModel(id)
        store.writeWhisperModel(whisperModelId)
    }

    companion object {
        const val ENGINE_KEY = "stt_engine"
        const val MODEL_KEY = "stt_model_whisper"
    }
}

/** Имена движков распознавания и миграция выпиленных. */
internal object VoiceEngine {
    const val SYSTEM = "system"
    const val WHISPER = "whisper"

    /** Движок, удалённый 14.10.2026; сохранённое значение обязано переехать. */
    private const val RETIRED_NCNN = "ncnn"

    fun normalize(saved: String?): String = if (saved == RETIRED_NCNN) WHISPER else (saved ?: SYSTEM)

    fun knownModel(id: String?): String = SttModels.byId(id ?: SttModels.DEFAULT_ID)?.id ?: SttModels.DEFAULT_ID
}

/** Ровно те операции с prefs, которые нужны [VoiceSettings]. */
internal interface VoiceStore {
    fun readEngine(): String?

    fun writeEngine(engine: String)

    fun readWhisperModelId(): String?

    fun writeWhisperModel(id: String)
}

internal class SharedPreferencesVoiceStore(
    private val prefs: SharedPreferences,
) : VoiceStore {
    override fun readEngine(): String? = prefs.getString(VoiceSettings.ENGINE_KEY, VoiceEngine.SYSTEM)

    override fun writeEngine(engine: String) = prefs.edit().putString(VoiceSettings.ENGINE_KEY, engine).apply()

    override fun readWhisperModelId(): String? = prefs.getString(VoiceSettings.MODEL_KEY, SttModels.DEFAULT_ID)

    override fun writeWhisperModel(id: String) = prefs.edit().putString(VoiceSettings.MODEL_KEY, id).apply()
}
