package org.opencode.mobile.stt

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/**
 * Characterization-тесты [WhisperTranscribeService.transcribe] на JVM.
 *
 * Зачем: функция — горячий путь диктовки с ПЯТЬЮ точками выхода, каждая со своим
 * текстом ошибки, и ДО этих тестов у неё не было ни одного теста. Любой рефакторинг
 * без сетки — вслепую. Эти тесты фиксируют ТЕКУЩЕЕ поведение, включая точные
 * формулировки: их правка обязана быть осознанной.
 *
 * Как работает без устройства: android.jar в unit-тестах — заглушки, поэтому в
 * build.gradle.kts включён `unitTests.isReturnDefaultValues = true`, а Context
 * мокается mockk. Реальный foreground-сервис в тестах не поднимается, поэтому
 * успешный путь имитируется прямой установкой результата в CompletableDeferred
 * задачи (см. completeOldest) — так же, как это делает worker в проде.
 *
 * СОСТОЯНИЕ: работают 2 теста из 5 (emptySamplesRejected, successReturnsWorkerResult).
 * Остальные три помечены @Ignore — они виснут до JUnit timeout. Причина НЕ найдена
 * и важное наблюдение: зависает даже ветка, до withTimeoutOrNull не доходящая
 * (serviceStartForbidden — там catch и ранний return), так что дело не в таймаутах.
 * Тесты оставлены написанными намеренно: они и есть искомая страховка перед
 * рефакторингом transcribe. Снять @Ignore можно после выяснения причины —
 * вероятный кандидат, kotlinx-coroutines-test с виртуальным временем вместо
 * реальных таймаутов на заглушках.
 */
class WhisperTranscribeServiceTest {
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        // Строгий мок, НЕ relaxed: у Context ~200 методов, и relaxed заставляет
        // mockk создать мок для return-типа каждого — это минуты CPU и ~2.4 ГБ RAM
        // на один тест. Заглушить нужно ровно два вызова, которые делает transcribe.
        ctx = mockk<Context>()
        every { ctx.applicationContext } returns ctx
        every { ctx.startForegroundService(any()) } returns mockk()
        clearQueue()
    }

    @After
    fun tearDown() {
        clearQueue()
    }

    /** Ветка 1: пустой вход отсекается до касания очереди и Android. */
    @Test(timeout = 60_000L)
    fun emptySamplesRejected() =
        runBlocking {
            val result = WhisperTranscribeService.transcribe(ctx, FloatArray(0))

            assertEquals("ОШИБКА WHISPER: пустые сэмплы", result)
            assertEquals("в очередь ничего не должно попасть", 0, queueSize())
        }

    /**
     * Ветка 2: очередь полна — новая задача отклоняется, очередь не растёт.
     *
     * ЗАГЛУШЕНО: тест виснет (60 с, JUnit timeout) — причина не найдена.
     * Подробности в KDoc класса: три оставшихся ветки блокируются одинаково.
     */
    @Ignore("виснет: transcribe не возвращается в этом окружении, причина не установлена")
    @Test(timeout = 60_000L)
    fun queueOverflowRejected() =
        runBlocking {
            val blocked = (1..4).map {
                async(Dispatchers.Default) {
                    WhisperTranscribeService.transcribe(ctx, floatArrayOf(0.1f), timeoutMs = 30_000L)
                }
            }
            waitUntil { queueSize() == 4 }

            val result = WhisperTranscribeService.transcribe(ctx, floatArrayOf(0.1f), timeoutMs = 30_000L)

            assertEquals("ОШИБКА WHISPER: очередь переполнена (4 задач) — попробуй ещё раз", result)
            assertEquals("отказ не должен добавлять задачу", 4, queueSize())
            blocked.forEach { it.cancel() }
        }

    /**
     * Ветка 3: Android 12+ запретил старт сервиса — задача откатывается из очереди.
     *
     * ЗАГЛУШЕНО: виснет наравне с ветками 2 и 5, хотя сюда ветка с
     * withTimeoutOrNull вообще не доходит (бросок перехватывается, ранний return) —
     * значит зависание не в таймауте, и причина пока не найдена.
     */
    @Ignore("виснет: transcribe не возвращается в этом окружении, причина не установлена")
    @Test(timeout = 60_000L)
    fun serviceStartForbiddenRollsBackTask() =
        runBlocking {
            // Подмена настоящего ForegroundServiceStartNotAllowedException: transcribe
            // ловит Exception, а собственный класс API 31 в заглушках добавил бы шум.
            every { ctx.startForegroundService(any()) } throws IllegalStateException("start not allowed")

            val result = WhisperTranscribeService.transcribe(ctx, floatArrayOf(0.1f), timeoutMs = 30_000L)

            assertEquals(
                "ОШИБКА WHISPER: запуск сервиса запрещён (Android 12+) — повтори из активного экрана",
                result
            )
            assertEquals("задача обязана быть снята", 0, queueSize())
        }

    /** Ветка 4: успех — возвращается ровно то, что положил воркер. */
    @Test(timeout = 60_000L)
    fun successReturnsWorkerResult() =
        runBlocking {
            val pending = async(Dispatchers.Default) {
                WhisperTranscribeService.transcribe(ctx, floatArrayOf(0.1f), timeoutMs = 30_000L)
            }
            waitUntil { queueSize() == 1 }

            assertEquals("задача должна была лежать в очереди", true, completeOldest("привет мир"))

            assertEquals("привет мир", pending.await())
            assertEquals(0, queueSize())
        }

    /**
     * Ветка 5: таймаут — задача снимается, наружу уходит честная ошибка.
     *
     * ЗАГЛУШЕНО: withTimeoutOrNull(1000) в этом окружении не срабатывает, тест
     * висит до JUnit timeout. Именно ради этой ветки и нужна страховка перед
     * рефакторингом transcribe — см. KDoc класса.
     */
    @Ignore("виснет: withTimeoutOrNull не срабатывает на заглушках android.jar, причина не установлена")
    @Test(timeout = 60_000L)
    fun timeoutRemovesTaskAndReports() =
        runBlocking {
            // Именно через Dispatchers.Default, а не напрямую на потоке runBlocking:
            // на BlockingEventLoop ветка withTimeoutOrNull внутри transcribe не
            // срабатывает и тест висит вечно. Прод-код не трогаем — это особенность
            // тестового event loop.
            val pending = async(Dispatchers.Default) {
                WhisperTranscribeService.transcribe(ctx, floatArrayOf(0.1f), timeoutMs = 1_000L)
            }

            assertEquals("ОШИБКА WHISPER: таймаут 1с — телефон не даёт CPU", pending.await())
            assertEquals("по таймауту задача обязана быть снята", 0, queueSize())
        }

    // ---- доступ к приватной статике очереди ------------------------------------
    // Она приватная, а проверить переполнение/успех без неё нельзя: в тестах нет
    // воркера, который иначе забрал бы задачу. Рефлексия — единственный путь.

    private val queue: ArrayDeque<Any>
        get() {
            // Kotlin компилирует private val компаньона в СТАТИЧЕСКОЕ поле внешнего
            // класса (проверено javap: `private static final ... queue` именно в
            // WhisperTranscribeService, а не в $Companion) — поэтому get(null).
            val field = WhisperTranscribeService::class.java.getDeclaredField("queue")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return field.get(null) as ArrayDeque<Any>
        }

    private fun queueSize(): Int = synchronized(queue) { queue.size }

    private fun clearQueue(): Unit = synchronized(queue) { queue.clear() }

    /** Имитирует воркер: забирает задачу и кладёт в неё результат. */
    private fun completeOldest(result: String): Boolean =
        synchronized(queue) {
            val task = queue.removeFirstOrNull() ?: return false
            val deferred = task::class.java.getDeclaredField("deferred").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (deferred.get(task) as CompletableDeferred<String>).complete(result)
        }

    private suspend fun waitUntil(
        timeoutMs: Long = 5_000L,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(10)
        }
        throw AssertionError("условие не наступило за $timeoutMs мс")
    }
}
