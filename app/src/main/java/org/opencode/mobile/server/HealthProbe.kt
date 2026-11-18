package org.opencode.mobile.server

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Проверки живости локальных процессов: TCP-порт и HTTP-ответ.
 *
 * Вынесено из RuntimeManager без изменения поведения. Пороги приходят
 * конструктором, чтобы тесты работали с маленькими окнами и не ждали
 * настоящие 10/30 секунд. Состояние рантайма (стоп/рестарт) приходит лямбдой
 * [isStopping]: сам HealthProbe не знает про RuntimeManager.
 */
internal class HealthProbe(
    private val timeoutMs: Int,
    private val startupTimeoutMs: Long,
    private val startupPollMs: Long,
    private val httpAttempts: Int,
    private val httpPollMs: Long,
    private val isStopping: () -> Boolean,
) {
    /**
     * TCP-коннект до localhost:port (память слушает сокет, не HTTP).
     * Сбой коннекта — это и есть «не готов», поэтому исключение не логируем:
     * опрос идёт каждые 500 мс, лог превратился бы в шум.
     */
    fun tcpOk(port: Int): Boolean =
        runCatching {
            Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), timeoutMs) }
        }.isSuccess

    /**
     * Ждём, пока локальный MCP-сервер реально поднимется: процесс жив И открыл
     * TCP на порту. Без этой проверки мгновенно упавший процесс считался бы
     * стартовавшим (P0-3).
     */
    suspend fun waitForPort(
        port: Int,
        isAlive: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + startupTimeoutMs
        var ok = false
        while (!ok && System.currentTimeMillis() < deadline) {
            if (isStopping() || !currentCoroutineContext().isActive) break
            if (isAlive() && tcpOk(port)) {
                ok = true
            } else {
                delay(startupPollMs)
            }
        }
        return ok
    }

    /**
     * Пингует HTTP localhost:port, ждёт пока сервер ответит. Выходит раньше при
     * стопе (running=false) или мягком рестарте (restartRequested).
     */
    suspend fun waitForHttp(port: Int): Boolean {
        var attempt = 0
        var ok = false
        while (!ok && attempt < httpAttempts) {
            if (isStopping() || !currentCoroutineContext().isActive) break
            if (pingOk(port)) {
                ok = true
            } else {
                attempt++
                delay(httpPollMs)
            }
        }
        return ok
    }

    /** HTTP HEAD до localhost:port: 2xx–4xx значит «сервер уже слушает». */
    fun pingOk(port: Int): Boolean =
        runCatching {
            val conn = URL("http://$LOOPBACK:$port/").openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "HEAD"
            try {
                conn.responseCode in HTTP_OK_MIN..HTTP_OK_MAX
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val HTTP_OK_MIN = 200
        const val HTTP_OK_MAX = 499
    }
}
