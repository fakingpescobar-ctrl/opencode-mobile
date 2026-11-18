package org.opencode.mobile.tts

/**
 * Исход последнего куска текста: то, что отдаёт `GET /v1/ui/tts`.
 *
 * Зачем отдельным объектом, а не полем в [TtsNarrator]: причина тишины живёт в
 * трёх местах сразу (движок, фильтр, вывод), и по логу приложения её не достать —
 * `logcat` приложению закрыт. Но и держать это в самом нарраторе оказалось
 * дорого: у него уже десять функций, и одиннадцатая включала detekt
 * `TooManyFunctions`. Счётчик исходов — не работа нарратора, а наблюдение за
 * ним, поэтому он живёт здесь.
 *
 * `note` разделяет исходы, которые раньше сливались в «ничего не слышно»:
 * `queued` — текст ушёл в очередь синтеза, `filtered` — фильтр выбросил все
 * куски, `deferred` — ждёт загрузки движка.
 */
object TtsStats {
    /** Один наблюдённый кусок: что пришло, что с ним стало и когда смотрели. */
    data class Last(
        val at: Long,
        val chars: Int,
        val chunks: Int,
        val spoken: Int,
        val note: String,
        val head: String,
    )

    @Volatile
    private var latest: Last? = null

    /** Что стало с последним текстом; `null` — озвучку ещё не дёргали. */
    fun last(): Last? = latest

    fun record(
        chars: Int,
        chunks: Int,
        spoken: Int,
        note: String,
        head: String,
    ) {
        latest =
            Last(
                at = System.currentTimeMillis(),
                chars = chars,
                chunks = chunks,
                spoken = spoken,
                note = note,
                head = head,
            )
    }
}
