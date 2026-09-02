package org.opencode.mobile.server

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Мини-прокси: HTTP CONNECT туннели, IPv4-first.
 *
 * Зачем: бинарь opencode (bun/musl) на Android не может подключиться к
 * https://opencode.ai/zen/go/v1 — DNS возвращает IPv6 (Cloudflare) первым, а на
 * устройстве нет IPv6-маршрута, и bun НЕ делает fallback на IPv4
 * («AI_APICallError: Cannot connect to API») — хотя curl с телефона работает
 * (у него happy-eyeballs).
 *
 * Ставим HTTPS_PROXY=http://127.0.0.1:3128 для процесса serve: на CONNECT
 * прокси резолвит хост ПО IPv4 (только Inet4Address) и туннелирует байты.
 * TLS остаётся end-to-end (бинарь сам делает handshake с opencode.ai),
 * сертификат валиден. Слушаем только 127.0.0.1 — наружу не торчим.
 */
object Ipv4Proxy {

    const val PORT = 3128

    // Вариант A: таймаут простоя туннеля. После CONNECT сокеты больше не должны
    // висеть с soTimeout=0 вечно: если через туннель нет данных столько времени
    // (модель молчит перед первым токеном / зависшая сеть), pump оборвёт соединение,
    // bun получит обрыв и сам переподключится (или клиент увидит ошибку вместо
    // вечного «думает»). 120с >> обычная пауза перед первым токеном SSE (~10-15с).
    private const val PUMP_IDLE_TIMEOUT_MS = 120_000

    private val started = AtomicBoolean(false)
    @Volatile
    private var serverSocket: ServerSocket? = null

    /** Идемпотентно поднимает прокси. Возвращает port или null при ошибке. */
    fun ensureStarted(port: Int = PORT): Int? {
        if (started.get()) {
            return if (serverSocket?.isBound == true) port else null
        }
        synchronized(this) {
            if (started.get()) return port
            return try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("127.0.0.1", port))
                serverSocket = ss
                started.set(true)
                android.util.Log.i("Ipv4Proxy", "CONNECT proxy listening on 127.0.0.1:$port")
                Thread({ acceptLoop(ss) }, "ipv4-proxy-accept").start()
                port
            } catch (e: Exception) {
                android.util.Log.e("Ipv4Proxy", "proxy start failed: ${e.message}")
                null
            }
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (started.get()) {
            val client = try {
                ss.accept()
            } catch (e: IOException) {
                if (started.get()) continue else break
            }
            Thread({ handleConn(client) }, "ipv4-proxy-conn").start()
        }
    }

    private fun handleConn(client: Socket) {
        try {
            client.soTimeout = 30_000
            val request = readUntilHeaders(client)
                ?: return run { client.close() }

            val firstLine = request.split("\r\n", limit = 2).firstOrNull() ?: return
            val parts = firstLine.split(" ")
            if (parts.size < 2 || !parts[0].equals("CONNECT", ignoreCase = true)) {
                client.getOutputStream().write("HTTP/1.1 502 Only CONNECT supported\r\n\r\n".toByteArray())
                client.getOutputStream().flush()
                return
            }

            val target = parts[1]
            val host = target.substringBeforeLast(":")
            val port = target.substringAfterLast(":").toIntOrNull() ?: 443

            val upstream = connectIpv4(host, port) ?: run {
                client.getOutputStream().write("HTTP/1.1 502 Cannot resolve $host\r\n\r\n".toByteArray())
                client.getOutputStream().flush()
                return
            }

            client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            client.getOutputStream().flush()
            // Вариант A: таймаут простоя (было 0 — вечная блокировка на read).
            // По истечении pump выйдет с ошибкой и закроет туннель, дав баг-клиенту
            // (bun/opencode) обрыв вместо бесконечного ожидания.
            client.soTimeout = PUMP_IDLE_TIMEOUT_MS
            upstream.soTimeout = PUMP_IDLE_TIMEOUT_MS
            android.util.Log.i("Ipv4Proxy", "CONNECT $host:$port OK")

            val upIn = upstream.getInputStream()
            val upOut = upstream.getOutputStream()
            val cIn = client.getInputStream()
            val cOut = client.getOutputStream()

            // двунаправленный пайп, каждый конец в своём потоке
            Thread({ pump(cIn, upOut) }, "p-c2u").start()
            pump(upIn, cOut)
        } catch (e: Exception) {
            android.util.Log.w("Ipv4Proxy", "conn error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun readUntilHeaders(client: Socket): String? {
        val buf = ByteArray(8192)
        val sb = StringBuilder()
        while (sb.length < 1_000_000) {
            val n = client.getInputStream().read(buf)
            if (n < 0) return null
            sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
            if (sb.contains("\r\n\r\n")) break
        }
        return sb.toString()
    }

    private fun connectIpv4(host: String, port: Int): Socket? {
        try {
            val candidates = InetAddress.getAllByName(host)
            val ipv4 = candidates.filterIsInstance<Inet4Address>()
            val addrs = if (ipv4.isNotEmpty()) ipv4 else candidates.toList()
            var last: Exception? = null
            for (a in addrs) {
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress(a, port), 15_000)
                    return s
                } catch (e: Exception) {
                    last = e
                }
            }
            android.util.Log.w("Ipv4Proxy", "connect $host:$port failed: ${last?.message}")
        } catch (e: Exception) {
            android.util.Log.w("Ipv4Proxy", "resolve $host failed: ${e.message}")
        }
        return null
    }

    private fun pump(src: InputStream, dst: OutputStream) {
        val buf = ByteArray(65536)
        try {
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { dst.close() } catch (_: Exception) {}
        }
    }
}