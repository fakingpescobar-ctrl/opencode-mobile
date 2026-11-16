package org.opencode.mobile.ui.chat

/**
 * Картинки внутри реплики чата.
 *
 * Зачем разметка в тексте, а не отдельный тип сообщения: у модели нет другого
 * канала в ленту, кроме текста. Сервер отдаёт части `text`, приложение рисует их
 * одним куском. Чтобы картинка встала между абзацами (текст — картинка —
 * подпись), её позиция должна быть частью самого текста.
 *
 * Разметка: `[[pic:/абсолютный/путь.png|подпись]]`, подпись необязательна.
 * Это намеренно НЕ markdown: `![](…)` ломается на первом же `)` в имени файла, а
 * отличить картинку от строки кода с таким текстом было бы нельзя.
 *
 * Сломанная разметка (нет пути, относительный путь, нет закрывающих `]]`) НЕ
 * съедается: она остаётся текстом. Пропавшая картинка видна сразу, а
 * проглоченный текст — нет.
 */
internal const val PICTURE_OPEN = "[[pic:"

/** `[[pic:путь]]` или `[[pic:путь|подпись]]`. Путь без `|`, `]` и переводов строк. */
private val PICTURE_RE = Regex("""\[\[pic:([^\]|\n]+)(?:\|([^\]\n]*))?]]""")

/** Одна картинка: абсолютный путь к файлу и, если есть, подпись под ней. */
internal data class ChatPicture(
    val path: String,
    val caption: String? = null,
)

/** Кусок реплики: обычный текст или картинка на своём месте. */
internal sealed interface ChatSegment {
    data class Body(
        val text: String,
    ) : ChatSegment

    data class Picture(
        val picture: ChatPicture,
    ) : ChatSegment
}

/** Что показать в ленте по этому тексту. */
internal fun splitPictures(text: String): List<ChatSegment> {
    val out = ArrayList<ChatSegment>()
    var from = 0
    for (match in PICTURE_RE.findAll(text)) {
        val path = match.groupValues[1].trim()
        if (!looksLikePicturePath(path)) continue
        val caption =
            match
                .groupValues
                .getOrNull(2)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        addBody(out, text.substring(from, match.range.first))
        out.add(ChatSegment.Picture(ChatPicture(path, caption)))
        from = match.range.last + 1
    }
    addBody(out, text.substring(from))
    return out
}

/** Есть ли в реплике картинка (нужно, чтобы выбрать отрисовку без печати по буквам). */
internal fun hasPictures(text: String): Boolean = splitPictures(text).any { it is ChatSegment.Picture }

/**
 * Текст для озвучки: картинка сама по себе не читается, но её подпись — да.
 * Пустая подпись не добавляет ничего, а не произносит путь вслух.
 */
internal fun speechText(text: String): String {
    val spoken =
        splitPictures(text).mapNotNull { segment ->
            when (segment) {
                is ChatSegment.Body -> segment.text.takeIf { it.isNotBlank() }
                is ChatSegment.Picture -> segment.picture.caption
            }
        }
    return spoken.joinToString("\n")
}

/**
 * Годится ли путь в разметку. Здесь только синтаксис: абсолютность и отсутствие
 * `..`. Настоящая проверка (файл существует, лежит в разрешённом корне, это
 * картинка) — в [ChatPicturePolicy], потому что там нужен диск.
 */
internal fun looksLikePicturePath(path: String): Boolean {
    if (!path.startsWith("/") || path.length <= 1) return false
    return ".." !in path.split('/')
}

private fun addBody(
    out: MutableList<ChatSegment>,
    raw: String,
) {
    val body = raw.trim('\n', '\r')
    if (body.isBlank()) return
    out.add(ChatSegment.Body(body))
}
