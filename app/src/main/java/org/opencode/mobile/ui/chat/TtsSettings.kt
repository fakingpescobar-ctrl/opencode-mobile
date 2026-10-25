package org.opencode.mobile.ui.chat

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.opencode.mobile.tts.TtsConfig
import org.opencode.mobile.tts.TtsEngine
import org.opencode.mobile.tts.TtsNarrator

/**
 * Настройки озвучки ответов: движок, голос, sid, режим дампа PCM и выключатель.
 *
 * Вынесено из `ChatOverlay`, где на каждую правку приходилось писать пару
 * «скопировать конфиг и сохранить» — десять одинаковых пар, в которых легко забыть
 * вторую строку и получить настройку, которая живёт до перезапуска.
 *
 * Включённость здесь **не** отдельная переменная: [TtsConfig.isEnabled] — это
 * `engine != Off`, то есть одно поле уже выражает состояние. Раньше рядом с
 * конфигом лежал независимый `ttsOn`, и после выключения они расходились
 * (`ttsOn=false`, а в конфиге всё ещё `Sherpa`). Теперь [on] читается из [config],
 * а [setEnabled] перечитывает конфиг после решения нарратора: включая озвучку,
 * движок выбирает он (облако остаётся облаком, локальный поднимается только если
 * модель на месте), и prefs — единственный источник правды.
 */
@Stable
internal class TtsSettings(
    private val store: TtsStore,
) {
    var config by mutableStateOf(store.readConfig())
        private set

    val on: Boolean get() = config.isEnabled

    fun setEnabled(enabled: Boolean) {
        if (enabled == config.isEnabled) return
        store.setNarratorEnabled(enabled)
        config = store.readConfig()
    }

    /** Смена движка перезапускает синтез: старый голос не должен дочитывать фразу. */
    fun chooseEngine(engine: TtsEngine) {
        update { it.copy(engine = engine) }
        store.stopNarrator()
    }

    /**
     * Единственный путь записи остальных полей: значение меняется в памяти и сразу
     * уходит в prefs, поэтому «поменял и забыл сохранить» невозможно.
     */
    fun update(transform: (TtsConfig) -> TtsConfig) {
        config = transform(config)
        store.saveConfig(config)
    }
}

/** Ровно те операции, которые нужны [TtsSettings]. */
internal interface TtsStore {
    fun readConfig(): TtsConfig

    fun saveConfig(config: TtsConfig)

    fun setNarratorEnabled(enabled: Boolean)

    fun stopNarrator()
}

internal class AndroidTtsStore(
    private val context: Context,
) : TtsStore {
    override fun readConfig(): TtsConfig = TtsConfig.read(context)

    override fun saveConfig(config: TtsConfig) = TtsConfig.save(context, config)

    override fun setNarratorEnabled(enabled: Boolean) = TtsNarrator.setEnabled(context, enabled)

    override fun stopNarrator() = TtsNarrator.stop()
}
