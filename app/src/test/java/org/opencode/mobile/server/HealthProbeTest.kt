package org.opencode.mobile.server

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class HealthProbeTest {
    private fun probe(
        startupTimeout: Long = STARTUP_TIMEOUT,
        startupPoll: Long = POLL,
        httpAttempts: Int = HTTP_ATTEMPTS,
        stopping: () -> Boolean = { false },
    ) = HealthProbe(
        timeoutMs = TCP_TIMEOUT,
        startupTimeoutMs = startupTimeout,
        startupPollMs = startupPoll,
        httpAttempts = httpAttempts,
        httpPollMs = POLL,
        isStopping = stopping,
    )

    /**
     * Мини-HTTP на голом ServerSocket: `com.sun.net.httpserver` в Android
     * unit-тестах не компилируется (classpath — android.jar, не полный JDK).
     * Отвечает статус-линией и закрывает соединение; для HEAD этого достаточно.
     */
    private fun httpServer(code: Int): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "test-http-$code") {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.getInputStream().bufferedReader().readLine()
                        val response = "HTTP/1.1 $code X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(response.toByteArray())
                            flush()
                        }
                    }
                } catch (_: IOException) {
                    break
                }
            }
        }
        return server
    }

    @Test
    fun `tcpOk видит открытый порт`() {
        ServerSocket(0).use { server ->
            assertTrue(probe().tcpOk(server.localPort))
        }
    }

    @Test
    fun `tcpOk не видит закрытый порт`() {
        val port = ServerSocket(0).use { it.localPort }
        assertFalse(probe().tcpOk(port))
    }

    @Test
    fun `pingOk принимает 2xx`() {
        val server = httpServer(HTTP_OK)
        try {
            assertTrue(probe().pingOk(server.localPort))
        } finally {
            server.close()
        }
    }

    @Test
    fun `pingOk отвергает 5xx`() {
        val server = httpServer(HTTP_ERROR)
        try {
            assertFalse(probe().pingOk(server.localPort))
        } finally {
            server.close()
        }
    }

    @Test
    fun `waitForPort дожидается открытого порта`() =
        runBlocking {
            ServerSocket(0).use { server ->
                assertTrue(probe().waitForPort(server.localPort) { true })
            }
        }

    @Test
    fun `waitForPort возвращает false по таймауту`() =
        runBlocking {
            val port = ServerSocket(0).use { it.localPort }
            assertFalse(
                probe(startupTimeout = SHORT_TIMEOUT, startupPoll = TINY_POLL)
                    .waitForPort(port) { true },
            )
        }

    @Test
    fun `waitForHttp дожидается ответа`() =
        runBlocking {
            val server = httpServer(HTTP_OK)
            try {
                assertTrue(probe().waitForHttp(server.localPort))
            } finally {
                server.close()
            }
        }

    @Test
    fun `waitForHttp выходит при остановке`() =
        runBlocking {
            val server = httpServer(HTTP_OK)
            try {
                assertFalse(
                    probe(stopping = { true }).waitForHttp(server.localPort),
                )
            } finally {
                server.close()
            }
        }

    private companion object {
        const val TCP_TIMEOUT = 500
        const val STARTUP_TIMEOUT = 2_000L
        const val SHORT_TIMEOUT = 50L
        const val POLL = 10L
        const val TINY_POLL = 5L
        const val HTTP_ATTEMPTS = 20
        const val HTTP_OK = 200
        const val HTTP_ERROR = 500
    }
}
