package org.opencode.mobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencode.mobile.tts.ElevenLabsSecret

/**
 * Правила поля ключа ElevenLabs.
 *
 * Ключ — это 32 символа, которые вставляют руками. Главное правило: при сбое
 * сохранения черновик остаётся в поле. Раньше это жило комментарием внутри
 * `clickable`, и достаточно было «причесать» обработчик, чтобы человек потерял
 * вставленный ключ и вводил его заново.
 */
class ElevenKeySettingsTest {
    @Test
    fun `сбой сохранения оставляет ключ в поле`() {
        val store = FakeStore(saveResult = ElevenLabsSecret.SaveResult.Failed("хранилище занято"))
        val key = ElevenKeySettings(store)
        key.edit("sk_test")

        key.save()

        assertEquals("sk_test", key.draft)
        assertEquals("хранилище занято", key.error)
        assertFalse(key.hasKey)
        assertEquals(0, store.stops)
    }

    @Test
    fun `успешное сохранение чистит поле и помечает ключ заданным`() {
        val store = FakeStore()
        val key = ElevenKeySettings(store)
        key.edit("sk_test")

        key.save()

        assertEquals("", key.draft)
        assertTrue(key.hasKey)
        assertNull(key.error)
        assertEquals(1, store.stops)
    }

    @Test
    fun `правка поля снимает старую ошибку`() {
        val store = FakeStore(saveResult = ElevenLabsSecret.SaveResult.Failed("нет места"))
        val key = ElevenKeySettings(store)
        key.edit("sk_test")
        key.save()

        key.edit("sk_test2")

        assertNull(key.error)
        assertEquals("sk_test2", key.draft)
    }

    @Test
    fun `удаление снимает отметку и ошибку`() {
        val store = FakeStore(hasKey = true)
        val key = ElevenKeySettings(store)
        key.edit("sk_test")
        key.save()
        key.edit("sk_другой")
        store.saveResult = ElevenLabsSecret.SaveResult.Failed("нет места")
        key.save()

        key.remove()

        assertFalse(key.hasKey)
        assertNull(key.error)
        assertTrue(store.cleared)
        assertEquals(2, store.stops)
    }

    @Test
    fun `пустой черновик не сохраняется`() {
        val store = FakeStore()
        val key = ElevenKeySettings(store)

        key.save()

        assertEquals(0, store.saves)
        assertFalse(key.hasKey)
    }

    @Test
    fun `отметка читается из хранилища на старте`() {
        val key = ElevenKeySettings(FakeStore(hasKey = true))

        assertTrue(key.hasKey)
    }

    private class FakeStore(
        var hasKey: Boolean = false,
        var saveResult: ElevenLabsSecret.SaveResult = ElevenLabsSecret.SaveResult.Ok,
    ) : ElevenKeyStore {
        var saves = 0
        var stops = 0
        var cleared = false

        override fun hasKey(): Boolean = hasKey

        override fun preview(): String? = if (hasKey) "…test" else null

        override fun save(key: String): ElevenLabsSecret.SaveResult {
            saves++
            if (saveResult is ElevenLabsSecret.SaveResult.Ok) hasKey = true
            return saveResult
        }

        override fun clear() {
            cleared = true
            hasKey = false
        }

        override fun stopNarrator() {
            stops++
        }
    }
}
