package org.opencode.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Решение по локальным MCP - единственное место, где раньше была тишина.
 *
 * Каждый тест закрывает реальный способ всё испортить, а не абстрактное «работает».
 */
class LocalMcpRecoveryPolicyTest {
    private val alive = LocalMcpProc(started = true, alive = true, recoverable = true)
    private val dead = LocalMcpProc(started = true, alive = false, recoverable = true)
    private val hopeless = LocalMcpProc(started = true, alive = false, recoverable = false)

    /** Обе службы живы, попыток не потрачено. */
    private val healthy = LocalMcpState(
        ynison = alive,
        memory = alive,
        recoveries = 0,
        maxRecoveries = 3,
        alreadyReported = false,
    )

    private val ynisonDead = healthy.copy(ynison = dead)
    private val memoryDead = healthy.copy(memory = dead)

    @Test
    fun `всё живо - ничего не делаем`() {
        assertEquals(LocalMcpAction.Idle, LocalMcpPolicy.decide(healthy))
    }

    @Test
    fun `процесс который не запускался не считается мёртвым`() {
        // Флаг «был нужен» не выставлен - значит и поднимать нечего.
        val state = healthy.copy(ynison = alive.copy(started = false, alive = false))
        assertEquals(LocalMcpAction.Idle, LocalMcpPolicy.decide(state))
    }

    @Test
    fun `живой процесс не перезапускаем второй копией поверх себя`() {
        // Повторный подъём поверх живого сразу упрётся в занятый порт.
        assertEquals(LocalMcpAction.Idle, LocalMcpPolicy.decide(healthy))
    }

    @Test
    fun `первая неудача - первая попытка`() {
        val action = LocalMcpPolicy.decide(ynisonDead) as LocalMcpAction.Retry
        assertEquals(LocalMcpTarget.YNISON, action.target)
        assertEquals(1, action.attempt)
    }

    @Test
    fun `счётчик растёт на каждой неудаче`() {
        // То, что чинили в c9d4487: неудачная попытка обязана увеличивать счётчик,
        // иначе recovery однажды тихо выключается.
        val second = LocalMcpPolicy.decide(ynisonDead.copy(recoveries = 1))
            as LocalMcpAction.Retry
        val third = LocalMcpPolicy.decide(ynisonDead.copy(recoveries = 2))
            as LocalMcpAction.Retry
        assertEquals(2, second.attempt)
        assertEquals(3, third.attempt)
    }

    @Test
    fun `попытки исчерпаны - сдаёмся один раз`() {
        val action = LocalMcpPolicy.decide(ynisonDead.copy(recoveries = 3))
            as LocalMcpAction.GiveUp
        assertEquals(listOf(LocalMcpTarget.YNISON), action.targets)
        assertEquals(false, action.alreadyReported)
    }

    @Test
    fun `повторно об отказе не сообщаем`() {
        // Иначе каждые 3с в лог ложится одно и то же - тот самый шум, из-за которого
        // отказались молочить респавнами.
        val action = LocalMcpPolicy.decide(
            ynisonDead.copy(recoveries = 3, alreadyReported = true),
        ) as LocalMcpAction.GiveUp
        assertTrue(action.alreadyReported)
    }

    @Test
    fun `при лимите ноль сдаёмся сразу не делая попыток`() {
        val action = LocalMcpPolicy.decide(ynisonDead.copy(maxRecoveries = 0))
        assertTrue(action is LocalMcpAction.GiveUp)
    }

    @Test
    fun `мёртвая память восстанавливается как память а не как ynison`() {
        val action = LocalMcpPolicy.decide(memoryDead) as LocalMcpAction.Retry
        assertEquals(LocalMcpTarget.MEMORY, action.target)
    }

    @Test
    fun `исчерпание памяти не обвиняет ynison`() {
        val action = LocalMcpPolicy.decide(memoryDead.copy(recoveries = 3))
            as LocalMcpAction.GiveUp
        assertEquals(listOf(LocalMcpTarget.MEMORY), action.targets)
    }

    @Test
    fun `при смерти обоих сначала поднимаем ynison`() {
        val action = LocalMcpPolicy.decide(ynisonDead.copy(memory = dead))
            as LocalMcpAction.Retry
        assertEquals(LocalMcpTarget.YNISON, action.target)
    }

    @Test
    fun `при смерти обоих и исчерпании перечисляем оба`() {
        val action = LocalMcpPolicy.decide(
            ynisonDead.copy(memory = dead, recoveries = 3),
        ) as LocalMcpAction.GiveUp
        assertEquals(listOf(LocalMcpTarget.YNISON, LocalMcpTarget.MEMORY), action.targets)
    }

    @Test
    fun `мёртв и поднимать нечем - это не повтор а отказ`() {
        val action = LocalMcpPolicy.decide(ynisonDead.copy(ynison = hopeless))
        assertEquals(
            listOf(LocalMcpTarget.YNISON),
            (action as LocalMcpAction.Unrecoverable).targets,
        )
    }

    @Test
    fun `соседа которого можно поднять всё равно поднимаем`() {
        // ynison без токенов - безнадёжен, но память ещё можно вернуть. Сначала память:
        // вернуть к жизни одного из двух полезнее, чем доложить и замереть.
        val action = LocalMcpPolicy.decide(
            ynisonDead.copy(ynison = hopeless, memory = dead),
        ) as LocalMcpAction.Retry
        assertEquals(LocalMcpTarget.MEMORY, action.target)
    }

    @Test
    fun `безнадёжный сосед не съедает попытки`() {
        // Когда поднимать уже нечего, отказ про безнадёжный сосед приходит сразу, а не
        // после вычерпывания лимита на процессы, которые вроде бы можно поднять.
        val action = LocalMcpPolicy.decide(ynisonDead.copy(ynison = hopeless, recoveries = 3))
        assertTrue(action is LocalMcpAction.Unrecoverable)
    }

    @Test
    fun `код берём у того процесса который умер`() {
        // Раньше код читался только у ynison, и при смерти одной памяти в лог уезжал код
        // живого процесса.
        assertEquals(
            137,
            LocalMcpPolicy.exitCodeOf(
                LocalMcpTarget.MEMORY,
                ynisonCode = 0,
                memoryCode = 137,
            ),
        )
        assertEquals(
            139,
            LocalMcpPolicy.exitCodeOf(
                LocalMcpTarget.YNISON,
                ynisonCode = 139,
                memoryCode = 137,
            ),
        )
    }

    @Test
    fun `сообщение называет только то что действительно отвалилось`() {
        // Регрессия на враньё: раньше в сообщении всегда стояло «музыка и память
        // недоступны», даже когда умер только ynison и память работала.
        assertEquals(
            "музыка",
            LocalMcpPolicy.unavailableText(listOf(LocalMcpTarget.YNISON)),
        )
        assertEquals(
            "память",
            LocalMcpPolicy.unavailableText(listOf(LocalMcpTarget.MEMORY)),
        )
        assertEquals(
            "музыка и память",
            LocalMcpPolicy.unavailableText(
                listOf(LocalMcpTarget.YNISON, LocalMcpTarget.MEMORY),
            ),
        )
    }
}
