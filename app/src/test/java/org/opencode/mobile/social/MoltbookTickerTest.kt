package org.opencode.mobile.social

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Отбор комментов — единственное место, где тик решает «кого отвечать». Ошибка
 * здесь не видна сразу: лишний ответ выглядит как активность агента, а пропущенный
 * — как «он перестал отвечать». Оба варианта проверяются тут.
 */
class MoltbookTickerTest {
    private fun comment(
        id: String,
        author: String,
        parentId: String? = null,
    ) = MoltbookClient.Comment(id, author, "текст $id", parentId)

    @Test
    fun `берёт только корневые чужие комменты`() {
        val pending =
            MoltbookTicker.pendingComments(
                listOf(
                    comment("c-1", "человек"),
                    comment("c-2", "opencodekz"),
                    comment("c-3", "человек", parentId = "c-1"),
                ),
            )

        assertEquals(listOf("c-1"), pending.map { it.id })
    }

    @Test
    fun `уже отвеченный коммент повторно не берёт`() {
        val pending =
            MoltbookTicker.pendingComments(
                listOf(
                    comment("c-1", "человек"),
                    comment("c-2", "opencodekz", parentId = "c-1"),
                    comment("c-3", "человек"),
                    comment("c-4", "opencodekz", parentId = "c-3"),
                ),
            )

        assertEquals(emptyList<String>(), pending.map { it.id })
    }

    @Test
    fun `наш коммент без parent_id считается корневым и не берётся`() {
        val pending = MoltbookTicker.pendingComments(
                listOf(
                    comment("c-1", "opencodekz"),
                    comment("c-2", "OPENCODEKZ"),
                ),
            )

        assertEquals(emptyList<String>(), pending.map { it.id })
    }

    @Test
    fun `пустая лента не роняет тик`() {
        assertEquals(emptyList<String>(), MoltbookTicker.pendingComments(emptyList()).map { it.id })
    }

    /**
     * Решения агента: ритм визита и апвоуты. Здесь ошибка не «краш», а тихая порча:
     * `NaN` минут останавливает расписание, а лишний апвоут — это фарм, который мы
     * обещали не делать. Поэтому мусор обязан давать fallback, а не угадывание.
     */
    private fun parse(answer: String?, vararg candidates: String) =
        MoltbookTicker.parseHousekeepingAnswer(answer, candidates.toList(), FALLBACK)

    @Test
    fun `берёт апвоуты и ритм из строгих двух строк`() {
        val result = parse("UPVOTE: 1 3\nNEXT: 90", "p-1", "p-2", "p-3")

        assertEquals(listOf("p-1", "p-3"), result.upvotePostIds)
        assertEquals(90, result.nextVisitMinutes)
    }

    @Test
    fun `пустой апвоут - это ноль апвоутов а не весь список`() {
        val result = parse("UPVOTE: 0\nNEXT: 120", "p-1", "p-2")

        assertEquals(emptyList<String>(), result.upvotePostIds)
        assertEquals(120, result.nextVisitMinutes)
    }

    @Test
    fun `номер вне списка кандидатов игнорируется`() {
        val result = parse("UPVOTE: 9\nNEXT: 60", "p-1")

        assertEquals(emptyList<String>(), result.upvotePostIds)
    }

    @Test
    fun `апвоуты ограничены потолком`() {
        val result = parse("UPVOTE: 1 2 3 4 5", "p-1", "p-2", "p-3", "p-4", "p-5")

        assertEquals(MoltbookTicker.MAX_UPVOTES_PER_TICK, result.upvotePostIds.size)
    }

    @Test
    fun `слишком частый ритм зажимается снизу а слишком редкий сверху`() {
        assertEquals(MoltbookTicker.MIN_NEXT_VISIT_MINUTES, parse("NEXT: 1", "p-1").nextVisitMinutes)
        assertEquals(MoltbookTicker.MAX_NEXT_VISIT_MINUTES, parse("NEXT: 99999", "p-1").nextVisitMinutes)
    }

    @Test
    fun `свободный текст вместо формата даёт безопасный fallback`() {
        val result = parse("Думаю, стоит зайти через пару часов", "p-1")

        assertEquals(emptyList<String>(), result.upvotePostIds)
        assertEquals(FALLBACK, result.nextVisitMinutes)
    }

    @Test
    fun `пустой ответ модели не ломает тик`() {
        assertEquals(FALLBACK, parse(null, "p-1").nextVisitMinutes)
        assertEquals(FALLBACK, parse("", "p-1").nextVisitMinutes)
    }

    @Test
    fun `переводы по номерам доезжают до панели`() {
        val result =
            parseGloss(
                "UPVOTE: \nNEXT: 45\nRU 1: Как ты сам себя улучшаешь без сети?\nRU 2: Расскажи про планировщик",
                glossable = 2,
            )

        assertEquals(45, result.nextVisitMinutes)
        assertEquals("Как ты сам себя улучшаешь без сети?", result.glosses[1])
        assertEquals("Расскажи про планировщик", result.glosses[2])
    }

    /**
     * Главный инвариант перевода: номер из ответа модели — это указание, КАКОЙ вопрос
     * переводить. Номер вне показанного списка — выдумка, и подписать ею чужой вопрос
     * хуже, чем не перевести ничего.
     */
    @Test
    fun `номер перевода вне списка отбрасывается`() {
        val result = parseGloss("NEXT: 45\nRU 9: выдуманный вопрос", glossable = 2)

        assertEquals(emptyMap<Int, String>(), result.glosses)
    }

    @Test
    fun `строка без перевода не становится пустым переводом`() {
        val result = parseGloss("NEXT: 45\nRU 2:", glossable = 2)

        assertEquals(emptyMap<Int, String>(), result.glosses)
    }

    @Test
    fun `перевод не ломает ритм и апвоуты`() {
        // Мусор в переводах не должен выбивать остальные директивы: они в том же ответе.
        val result = parseGloss("UPVOTE: 1\nNEXT: 200\nRU xx: мусор", "p-1", glossable = 1)

        assertEquals(listOf("p-1"), result.upvotePostIds)
        assertEquals(200, result.nextVisitMinutes)
        assertEquals(emptyMap<Int, String>(), result.glosses)
    }

    private fun parseGloss(
        answer: String,
        vararg candidates: String,
        glossable: Int,
    ) = MoltbookTicker.parseHousekeepingAnswer(
        answer,
        candidates.toList(),
        FALLBACK,
        glossableCount = glossable,
    )

    private companion object {
        const val FALLBACK = 120
    }
}
