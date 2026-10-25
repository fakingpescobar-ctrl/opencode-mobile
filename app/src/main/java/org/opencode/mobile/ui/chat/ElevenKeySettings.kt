package org.opencode.mobile.ui.chat

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.opencode.mobile.tts.ElevenLabsSecret
import org.opencode.mobile.tts.TtsNarrator

/**
 * Ключ ElevenLabs: черновик поля, отметка «ключ задан» и причина отказа.
 *
 * Вынесено из `ChatOverlay`, где это были три `var` и вложенный `when` на кнопке
 * «Сохранить». Правило, ради которого всё здесь и живёт: **при сбое сохранения
 * черновик не чистится**. Ключ — это 32 символа, которые человек вставляет
 * руками; если хранилище моргнуло и поле опустело, вводить их заново — злость
 * на весь продукт. Обратное тоже верно: после успеха поле чистится, потому что
 * ключ теперь лежит в шифрованном хранилище, а не в памяти UI.
 *
 * Сам ключ в UI не показывается никогда: наружу отдаётся только [preview]
 * (хвост сохранённого ключа) и признак [hasKey].
 */
@Stable
internal class ElevenKeySettings(
    private val store: ElevenKeyStore,
) {
    /** Черновик намеренно НЕ восстанавливается из хранилища: только то, что вводят сейчас. */
    var draft by mutableStateOf("")
        private set

    var hasKey by mutableStateOf(store.hasKey())
        private set

    /** Причина, по которой последнее сохранение не вышло, или null. */
    var error by mutableStateOf<String?>(null)
        private set

    /** Хвост сохранённого ключа для подсказки; сам ключ наружу не уходит. */
    val preview: String? get() = store.preview()

    /** Правка черновика — новая попытка, поэтому старая ошибка больше не про этот текст. */
    fun edit(text: String) {
        draft = text
        error = null
    }

    fun save() {
        if (draft.isBlank()) return
        when (val result = store.save(draft)) {
            is ElevenLabsSecret.SaveResult.Ok -> {
                draft = ""
                hasKey = true
                error = null
                // Ключ сменился — движок надо пересоздать, иначе он продолжит
                // работать со старым.
                store.stopNarrator()
            }
            is ElevenLabsSecret.SaveResult.Failed -> error = result.reason
        }
    }

    fun remove() {
        store.clear()
        hasKey = false
        error = null
        store.stopNarrator()
    }
}

/** Ровно те операции с ключом, которые нужны [ElevenKeySettings]. */
internal interface ElevenKeyStore {
    fun hasKey(): Boolean

    fun preview(): String?

    fun save(key: String): ElevenLabsSecret.SaveResult

    fun clear()

    fun stopNarrator()
}

internal class AndroidElevenKeyStore(
    private val context: Context,
) : ElevenKeyStore {
    override fun hasKey(): Boolean = ElevenLabsSecret.hasKey(context)

    override fun preview(): String? = ElevenLabsSecret.preview(context)

    override fun save(key: String): ElevenLabsSecret.SaveResult = ElevenLabsSecret.save(context, key)

    override fun clear() = ElevenLabsSecret.clear(context)

    override fun stopNarrator() = TtsNarrator.stop()
}
