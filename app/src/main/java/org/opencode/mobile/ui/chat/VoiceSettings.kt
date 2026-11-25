package org.opencode.mobile.ui.chat

import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.opencode.mobile.stt.SttModels

/**
 * Настройки голосового ввода: движок (system/whisper-кошелёк) и выбранная
 * локальная модель.
 *
 * Важно: `stt_engine` хранит только `system|whisper` для совместимости.
 * Конкретный runtime локальной модели (whisper.cpp ggml или sherpa-onnx)
 * определяется по каталогу [SttModels] в P2.
 *
 * Миграция: старый ключ `stt_model_whisper` сохраняем; новый `stt_model_local`
 * имеет приоритет. Нельзя терять выбранный `small` и нельзя затирать новые
 * валидные id.
 */
@Stable
internal class VoiceSettings(
    private val store: VoiceStore,
) {
    var sttEngine by mutableStateOf(VoiceEngine.normalize(store.readEngine()))
        private set

    var localModelId by mutableStateOf(resolveInitialModel(store))
        private set

    /** Совместимость со старым именем поля, которое использует UI. */
    val whisperModelId: String
        get() = localModelId

    fun chooseEngine(engine: String) {
        sttEngine = VoiceEngine.normalize(engine)
        store.writeEngine(sttEngine)
    }

    /** Модель для локального распознавания. */
    fun sttModel(): String = localModelId

    fun chooseWhisperModel(id: String) {
        localModelId = VoiceEngine.knownModel(id)
        store.writeLocalModel(localModelId)
        val model = SttModels.byId(localModelId)
        if (model?.flatFileName != null) {
            store.writeWhisperModel(localModelId)
        }
    }

    companion object {
        const val ENGINE_KEY = "stt_engine"
        const val MODEL_KEY = "stt_model_whisper"
        const val LOCAL_MODEL_KEY = "stt_model_local"

        private fun resolveInitialModel(store: VoiceStore): String {
            val local = store.readLocalModelId()?.takeIf { SttModels.byId(it) != null }
            if (local != null) return local
            val legacy = store.readWhisperModelId()?.takeIf { SttModels.byId(it) != null }
            if (legacy != null) return legacy
            return SttModels.DEFAULT_ID
        }
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

    fun readLocalModelId(): String?

    fun writeLocalModel(id: String)
}

internal class SharedPreferencesVoiceStore(
    private val prefs: SharedPreferences,
) : VoiceStore {
    override fun readEngine(): String? = prefs.getString(VoiceSettings.ENGINE_KEY, VoiceEngine.SYSTEM)

    override fun writeEngine(engine: String) = prefs.edit().putString(VoiceSettings.ENGINE_KEY, engine).apply()

    override fun readWhisperModelId(): String? = prefs.getString(VoiceSettings.MODEL_KEY, SttModels.DEFAULT_ID)

    override fun writeWhisperModel(id: String) = prefs.edit().putString(VoiceSettings.MODEL_KEY, id).apply()

    override fun readLocalModelId(): String? = prefs.getString(VoiceSettings.LOCAL_MODEL_KEY, null)

    override fun writeLocalModel(id: String) = prefs.edit().putString(VoiceSettings.LOCAL_MODEL_KEY, id).apply()
}
