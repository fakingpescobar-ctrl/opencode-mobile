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
import org.junit.Test

/**
 * Characterization-тесты на все пять ветвей [WhisperTranscribeService.transcribe].
 *
 * ЗАЧЕМ ОНИ: страховка перед рефакторингом transcribe. У функции 5 точек выхода
 * и нет ни одного теста — любой рефакторинг вслепую. Тесты описывают текущее
 * (правильное) поведение, а не желаемое.
 *
 * НАЙДЕННЫЙ ИМИ БАГ (исправлен в transcribe): deferred создаётся как
 * `CompletableDeferred(parent = coroutineContext[Job])`, то есть становится
 * не-completed ребёнком Job вызывающей корутины. На трёх путях, где transcribe
 * уходил без результата воркера (переполнение очереди / запрет запуска
 * сервиса / таймаут), deferred оставался не-завершённым — а значит Job
 * вызывающей корутины не мог завершиться никогда. Это и висело в тестах, и в
 * проде означало утечку scope вызывающего (Activity/ViewModel).
 *
 * Теперь каждый такой путь делает `deferred.cancel()`, и все пять тестов зелёные.
 * Возврат к зелёному — не «тесты починили», а «сломанный инвариант починили».
 *
 * ОСТОРОЖНО, если будешь править transcribe: `deferred.cancel()` на каждом
 * раннем выходе — не украшение, а условие, при котором вызывающий scope
 * вообще способен завершиться. Убери его — и ветки снова зависнут.
 */
class WhisperTranscribeServiceTest {
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        clearQueue()
        // Строгий mock, НЕ relaxed: у Context ~200 методов, relaxed-экземпляр
        // жрёт 2.4 ГБ RAM и минуты CPU на создание.
        ctx = mockk<Context>()
        every { ctx.applicationContext } answers { ctx }
        every { ctx.startForegroundService(any()) } returns mockk()
    }

    @After
    fun tearDown() {
        clearQueue()
    }

    /** Ветка 1: пустые сэмплы — отказ до создания задачи. */
    @Test(timeout = 60_000L)
    fun emptySamplesRejected() =
        runBlocking {
            val result = WhisperTranscribeService.transcribe(ctx, FloatArray(0))

            assertEquals("ОШИБКА WHISPER: пустые сэмплы", result)
            assertEquals("в очередь ничего не должно попасть", 0, queueSize())
        }

    /** Ветка 2: очередь полна — новая задача отклоняется, очередь не растёт. */
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

    /** Ветка 3: Android 12+ запретил старт сервиса — задача откатывается из очереди. */
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

    /** Ветка 5: таймаут — задача снимается, наружу уходит честная ошибка. */
    @Test(timeout = 60_000L)
    fun timeoutRemovesTaskAndReports() =
        runBlocking {
            val result = WhisperTranscribeService.transcribe(ctx, floatArrayOf(0.1f), timeoutMs = 1_000L)

            assertEquals("ОШИБКА WHISPER: таймаут 1с — телефон не даёт CPU", result)
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
