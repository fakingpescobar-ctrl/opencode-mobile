package org.opencode.mobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разметка картинок в реплике: `[[pic:путь|подпись]]`.
 *
 * Главное правило, которое здесь закреплено: разметка не имеет права съесть
 * текст. Всё, что не разобралось (относительный путь, `..`, нет закрывающих
 * `]]`), остаётся текстом — потерянная картинка видна, потерянный текст нет.
 */
class ChatPicturesTest {
    private val png = "/storage/emulated/0/Documents/OpencodeTerminal/chat-images/ref-1.png"

    private fun body(text: String) = ChatSegment.Body(text)

    private fun pic(
        path: String,
        caption: String? = null,
    ) = ChatSegment.Picture(ChatPicture(path, caption))

    @Test
    fun `обычный текст — один кусок`() {
        assertEquals(listOf(body("привет")), splitPictures("привет"))
    }

    @Test
    fun `пустой текст — ни одного куска`() {
        assertTrue(splitPictures("").isEmpty())
    }

    @Test
    fun `картинка с подписью между абзацами`() {
        assertEquals(
            listOf(body("выбери вариант"), pic(png, "Вариант 1"), body("второй ниже")),
            splitPictures("выбери вариант\n[[pic:$png|Вариант 1]]\nвторой ниже"),
        )
    }

    @Test
    fun `картинка без подписи — подписи нет`() {
        assertEquals(listOf(pic(png)), splitPictures("[[pic:$png]]"))
        assertNull((splitPictures("[[pic:$png]]").single() as ChatSegment.Picture).picture.caption)
    }

    @Test
    fun `две картинки подряд`() {
        assertEquals(
            listOf(pic(png, "1"), pic("$png.2.png", "2")),
            splitPictures("[[pic:$png|1]][[pic:$png.2.png|2]]"),
        )
    }

    @Test
    fun `пробелы в пути не ломают разметку`() {
        val spaced = "/storage/emulated/0/Download/ref menu.png"
        assertEquals(listOf(pic(spaced)), splitPictures("[[pic:$spaced]]"))
    }

    @Test
    fun `без закрывающих скобок — остаётся текстом`() {
        val text = "смотри [[pic:$png"
        assertEquals(listOf(body(text)), splitPictures(text))
    }

    @Test
    fun `относительный путь — остаётся текстом`() {
        val text = "[[pic:images/ref.png]]"
        assertEquals(listOf(body(text)), splitPictures(text))
    }

    @Test
    fun `путь с двумя точками — остаётся текстом`() {
        val text = "[[pic:/storage/emulated/0/../../data/secret.png]]"
        assertEquals(listOf(body(text)), splitPictures(text))
    }

    @Test
    fun `пустой путь — остаётся текстом`() {
        assertEquals(listOf(body("[[pic:|подпись]]")), splitPictures("[[pic:|подпись]]"))
    }

    @Test
    fun `вертикальная черта в подписи не режет подпись дважды`() {
        assertEquals(
            listOf(pic(png, "меню | тёмное")),
            splitPictures("[[pic:$png|меню | тёмное]]"),
        )
    }

    @Test
    fun `hasPictures отличает реплику с картинкой от обычной`() {
        assertTrue(hasPictures("текст [[pic:$png]]"))
        assertFalse(hasPictures("текст без картинок"))
    }

    @Test
    fun `в озвучку идёт подпись, а не путь`() {
        assertEquals(
            "выбери вариант\nВариант 1\nвторой ниже",
            speechText("выбери вариант\n[[pic:$png|Вариант 1]]\nвторой ниже"),
        )
    }

    @Test
    fun `картинка без подписи в озвучке молчит`() {
        assertEquals("только текст", speechText("только текст[[pic:$png]]"))
    }

    @Test
    fun `разметка без картинки — весь текст в озвучке`() {
        assertEquals("смотри [[pic:$png", speechText("смотри [[pic:$png"))
    }
}
