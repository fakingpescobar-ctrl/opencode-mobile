package org.opencode.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class RuntimeStateHolderTest {
    private val published = AtomicInteger()
    private val holder = RuntimeStateHolder(listener = { published.incrementAndGet() })

    @Test
    fun `begin поднимает флаг и обнуляет счётчик отказов`() {
        holder.dispatch(RuntimeAction.Update { copy(restartCount = MAX_ATTEMPTS) })

        holder.dispatch(RuntimeAction.Begin)

        assertTrue(holder.isRunning)
        assertFalse(holder.isStopping)
        assertFalse(holder.isRestartPending)
        assertEquals(0, holder.snapshot().restartCount)
    }

    @Test
    fun `begin снимает ранее запрошенный рестарт`() {
        holder.dispatch(RuntimeAction.Begin)
        holder.dispatch(RuntimeAction.Restart)

        holder.dispatch(RuntimeAction.Begin)

        assertFalse(holder.isRestartPending)
        assertFalse(holder.isStopping)
    }

    @Test
    fun `повторный стоп возвращает false`() {
        holder.dispatch(RuntimeAction.Begin)

        assertTrue(holder.dispatch(RuntimeAction.Stop))
        assertFalse(holder.dispatch(RuntimeAction.Stop))
        assertFalse(holder.isRunning)
        assertTrue(holder.isStopping)
    }

    @Test
    fun `рестарт виден как остановка до снятия флага`() {
        holder.dispatch(RuntimeAction.Begin)

        holder.dispatch(RuntimeAction.Restart)

        assertTrue(holder.isRestartPending)
        assertTrue(holder.isStopping)
        assertTrue(holder.isRunning)
        assertTrue(holder.dispatch(RuntimeAction.ConsumeRestart))
        assertFalse(holder.dispatch(RuntimeAction.ConsumeRestart))
        assertFalse(holder.isStopping)
    }

    @Test
    fun `update публикует снимок и зовёт слушателя`() {
        val before = published.get()

        val changed = holder.dispatch(RuntimeAction.Update { copy(stage = RuntimeStage.HEALTHY) })

        assertFalse(changed)
        assertEquals(RuntimeStage.HEALTHY, holder.snapshot().stage)
        assertEquals(RuntimeStage.HEALTHY, holder.state.value.stage)
        assertEquals(before + 1, published.get())
    }

    @Test
    fun `markStopped гасит флаг без публикации`() {
        holder.dispatch(RuntimeAction.Begin)
        val before = published.get()

        holder.dispatch(RuntimeAction.MarkStopped)

        assertFalse(holder.isRunning)
        assertTrue(holder.isStopping)
        assertEquals(before, published.get())
    }

    @Test
    fun `параллельные обновления не теряются`() {
        val start = CountDownLatch(1)
        val threads =
            (0 until THREADS).map { index ->
                Thread {
                    start.await()
                    repeat(ITERATIONS) {
                        holder.dispatch(RuntimeAction.Update { copy(restartCount = restartCount + 1) })
                    }
                }.apply {
                    name = "state-holder-$index"
                    start()
                }
            }

        start.countDown()
        threads.forEach { it.join() }

        assertEquals(THREADS * ITERATIONS, holder.snapshot().restartCount)
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val THREADS = 8
        const val ITERATIONS = 250
    }
}
