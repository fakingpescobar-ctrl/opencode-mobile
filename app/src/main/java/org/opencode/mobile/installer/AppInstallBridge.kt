package org.opencode.mobile.installer

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.opencode.mobile.media.MediaAppSnapshot
import org.opencode.mobile.media.MediaAutomationShield
import org.opencode.mobile.media.MediaCapabilities
import org.opencode.mobile.media.MediaControlController
import org.opencode.mobile.media.MediaControlRequestValidator
import org.opencode.mobile.media.MediaControlResult
import org.opencode.mobile.media.MediaLibraryEntry
import org.opencode.mobile.media.MediaLibraryResult
import org.opencode.mobile.media.MediaLikeResult
import org.opencode.mobile.media.MediaPlaybackSnapshot
import org.opencode.mobile.media.MediaSearchResult
import org.opencode.mobile.media.MediaStatusResult
import org.opencode.mobile.media.MediaUiAction
import org.opencode.mobile.media.MediaUiAutomation
import org.opencode.mobile.media.MediaUiBounds
import org.opencode.mobile.media.MediaUiCandidate
import org.opencode.mobile.media.MediaUiJob
import org.opencode.mobile.media.MediaUiOutcome
import org.opencode.mobile.media.MediaUiTarget
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Небольшой loopback RPC только для локального opencode MCP.
 * Поддерживает установку APK и ограниченный запуск launcher-приложений.
 *
 * Каждый запрос обязан иметь Bearer-токен, выданный текущему процессу приложения.
 * Наружу сокет не слушает; токен передаётся memory.js через environment.
 */
@Suppress("MagicNumber", "TooManyFunctions", "LargeClass")
object AppInstallBridge {
    const val PORT = 4202
    const val ENV_TOKEN = "MOBILE_INSTALL_TOKEN"
    const val ENV_PORT = "MOBILE_INSTALL_PORT"

    private const val TAG = "AppInstallBridge"
    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val MAX_BODY_BYTES = 32 * 1024
    private const val SOCKET_TIMEOUT_MS = 10_000
    private const val DEFAULT_UI_CLICK_TIMEOUT_MS = 8_000L
    private const val UI_CLICK_HARD_TIMEOUT_GRACE_MS = 3_000L

    private data class Request(
        val method: String,
        val path: String,
        val query: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private val lock = Any()
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "app-install-rpc").apply {
            isDaemon = true
        }
    }

    /**
     * Клик по чужому окну ждёт, пока нужное окно появится, - то есть блокирует поток на весь бюджет.
     * На пуле RPC (а он на два потока) это значит «половина моста лежит», поэтому клики получают
     * собственный поток. Побочный, но полезный эффект: клики выстраиваются в очередь, а не
     * отбивают друг у друга занятость.
     */
    private val uiClickExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "media-ui-click").apply {
            isDaemon = true
        }
    }
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var started = false

    @Volatile
    private var token: String = ""

    fun start(context: Context) {
        synchronized(lock) {
            if (started) return
            ApkInstaller.initialize(context)
            InstalledAppController.initialize(context)
            MediaControlController.initialize(context)
            MediaAutomationShield.initialize(context)
            val nextToken = token.ifBlank { UUID.randomUUID().toString() }
            val socket =
                runCatching {
                    ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
                }.getOrElse { error ->
                    Log.e(TAG, "Cannot bind loopback installer bridge", error)
                    return
                }
            serverSocket = socket
            token = nextToken
            started = true
            thread(name = "app-install-accept", isDaemon = true) { acceptLoop(socket) }
            Log.i(TAG, "Installer bridge listening on 127.0.0.1:$PORT")
        }
    }

    fun environment(): Map<String, String> {
        val currentToken =
            token.ifBlank {
                synchronized(lock) {
                    token.ifBlank { UUID.randomUUID().toString().also { token = it } }
                }
            }
        return mapOf(ENV_TOKEN to currentToken, ENV_PORT to PORT.toString())
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed && started) {
            val client =
                runCatching { socket.accept() }.getOrElse {
                    if (started) Log.w(TAG, "Installer bridge accept failed", it)
                    return
                }
            executor.execute { serve(client) }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun serve(client: Socket) {
        client.use {
            client.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val request =
                runCatching { readRequest(input) }.getOrElse { error ->
                    writeJson(output, 400, error(error))
                    return
                }
            if (!authorized(request.headers)) {
                writeJson(output, 401, error("unauthorized"))
                return
            }
            try {
                route(output, request)
            } catch (error: IllegalArgumentException) {
                writeJson(output, 400, error(error.message ?: "invalid request"))
            } catch (error: Exception) {
                Log.w(TAG, "Installer bridge request failed", error)
                writeJson(output, 500, error(error.message ?: "request failed"))
            }
        }
    }

    /** Таблица роутов: добавление endpoint-а не раздувает сложность dispatch-а. */
    private data class Route(
        val method: String,
        val path: String,
        val handler: (BufferedOutputStream, Request) -> Unit,
    )

    private fun route(
        output: BufferedOutputStream,
        request: Request,
    ) {
        val match = routes().firstOrNull { it.method == request.method && it.path == request.path }
        if (match != null) {
            match.handler(output, request)
        } else {
            writeJson(output, 404, error("not found"))
        }
    }

    private fun routes(): List<Route> =
        listOf(
            Route("GET", "/v1/health") { output, _ -> writeHealth(output) },
            Route("GET", "/v1/apps", ::listApps),
            Route("GET", "/v1/apps/status", ::status),
            Route("POST", "/v1/apps/launch", ::launchApp),
            Route("POST", "/v1/apps/install", ::install),
            Route("GET", "/v1/media/apps", ::listMediaApps),
            Route("GET", "/v1/media/status", ::mediaStatus),
            Route("POST", "/v1/media/control", ::controlMedia),
            Route("POST", "/v1/media/play", ::playMedia),
            Route("GET", "/v1/media/capabilities", ::mediaCapabilities),
            Route("POST", "/v1/media/like", ::likeMedia),
            Route("GET", "/v1/media/search", ::searchMedia),
            Route("GET", "/v1/media/library", ::mediaLibrary),
            Route("POST", "/v1/media/ui/click", ::clickMediaUi),
            Route("POST", "/v1/media/ui/text", ::textMediaUi),
            Route("POST", "/v1/media/ui/shield", ::mediaUiShield),
        )

    private fun writeHealth(output: BufferedOutputStream) {
        writeJson(output, 200, JSONObject().put("ok", true).put("service", "android-app-install"))
    }

    private fun listApps(
        output: BufferedOutputStream,
        request: Request,
    ) {
        val spec =
            AppControlRequestValidator.list(
                query = parameter(request.query, "query"),
                limit = optionalIntParameter(request.query, "limit"),
            )
        val apps = InstalledAppController.listApps(spec)
        val items =
            JSONArray().apply {
                apps.forEach { app -> put(app.toJson()) }
            }
        writeJson(
            output,
            200,
            JSONObject().put("ok", true).put("count", apps.size).put("apps", items),
        )
    }

    private fun launchApp(
        output: BufferedOutputStream,
        request: Request,
    ) {
        val body = jsonObject(request)
        val spec = AppControlRequestValidator.launch(body.getString("package"))
        val app = InstalledAppController.launchApp(spec)
        writeJson(output, 200, JSONObject().put("ok", true).put("app", app.toJson()))
    }

    private fun status(
        output: BufferedOutputStream,
        request: Request,
    ) {
        val id = parameter(request.query, "id")
        require(!id.isNullOrBlank()) { "id is required" }
        val status = requireNotNull(ApkInstaller.status(id)) { "unknown install job" }
        writeJson(output, 200, JSONObject().put("ok", true).put("job", status.toJson()))
    }

    private fun listMediaApps(
        output: BufferedOutputStream,
        request: Request,
    ) {
        val spec =
            MediaControlRequestValidator.list(
                query = parameter(request.query, "query"),
                limit = optionalIntParameter(request.query, "limit"),
            )
        val apps = MediaControlController.listApps(spec)
        val items =
            JSONArray().apply {
                apps.forEach { app -> put(app.toJson()) }
            }
        writeJson(
            output,
            200,
            JSONObject().put("ok", true).put("count", apps.size).put("apps", items),
        )
    }

    private fun mediaStatus(
        output: BufferedOutputStream,
        request: Request,
    ) {
        val packageName = MediaControlRequestValidator.status(parameter(request.query, "package"))
        val status = MediaControlController.status(packageName)
        writeJson(output, 200, JSONObject().put("ok", true).put("media", status.toJson()))
    }

    private fun controlMedia(
        output: BufferedOutputStream,
        request: Request,
    ) {
        val body = jsonObject(request)
        val spec =
            MediaControlRequestValidator.control(
                action = body.getString("action"),
                packageName = body.optionalString("package"),
            )
        val result = MediaControlController.control(spec)
        writeJson(output, 200, JSONObject().put("ok", true).put("media", result.toJson()))
    }

    private fun searchMedia(
        output: BufferedOutputStream,
        request: Request,
    ) {
        val spec =
            MediaControlRequestValidator.search(
                query = parameter(request.query, "query"),
                limit = optionalIntParameter(request.query, "limit"),
            )
        val result = MediaControlController.search(spec)
        writeJson(output, 200, JSONObject().put("ok", true).put("search", result.toJson()))
    }

    private fun mediaLibrary(
        output: BufferedOutputStream,
        request: Request,
    ) {
        val spec =
            MediaControlRequestValidator.library(
                packageName = parameter(request.query, "package"),
                node = parameter(request.query, "node"),
                query = parameter(request.query, "query"),
                limit = optionalIntParameter(request.query, "limit"),
            )
        val result = MediaControlController.library(spec)
        writeJson(output, 200, JSONObject().put("ok", true).put("library", result.toJson()))
    }

    private fun clickMediaUi(
        output: BufferedOutputStream,
        request: Request,
    ) {
        val body = jsonObject(request)
        performUiAction(output, body, MediaUiAction.Click)
    }

    private fun textMediaUi(
        output: BufferedOutputStream,
        request: Request,
    ) {
        val body = jsonObject(request)
        // По умолчанию именно set_text: агент зовёт ручку, чтобы напечатать запрос, а не чтобы
        // стереть поле, и пустой action не должен молча стирать.
        val kind = body.optionalString("action") ?: MediaUiAction.SET_TEXT
        performUiAction(output, body, MediaUiAction.parse(kind, body.optionalString("text")))
    }

    private fun performUiAction(
        output: BufferedOutputStream,
        body: JSONObject,
        action: MediaUiAction,
    ) {
        val target =
            MediaUiTarget(
                packageName = body.optionalString("package").orEmpty(),
                textContains = body.optionalStringList("text_contains"),
                contentDescriptions = body.optionalStringList("content_description"),
                resourceIds = body.optionalStringList("resource_id"),
                bounds = body.optionalBounds("bounds"),
                requireClickable = body.optBoolean("require_clickable", true),
            )
        // Бюджет проверяем здесь, а не внутри задачи: иначе агент получил бы 200 с «ok=false»
        // вместо внятного 400 на плохой аргумент.
        val timeoutMs = body.optionalLong("timeout_ms", DEFAULT_UI_CLICK_TIMEOUT_MS)
        require(timeoutMs in MediaUiJob.MIN_TIMEOUT_MS..MediaUiJob.MAX_TIMEOUT_MS) {
            "timeout_ms must be between ${MediaUiJob.MIN_TIMEOUT_MS} and ${MediaUiJob.MAX_TIMEOUT_MS}"
        }
        val outcome =
            clickOffRpcPool {
                MediaUiAutomation.perform(target = target, action = action, timeoutMs = timeoutMs)
            }
        writeJson(
            output,
            200,
            JSONObject()
                .put("ok", outcome is MediaUiOutcome.Performed)
                .put("ui", outcome.toJson()),
        )
    }

    /**
     * Уводим работу с чужим окном с пула RPC: сам он ждёт accessibility-сервис, а тот - чужое окно.
     *
     * Страховка по времени нужна, чтобы мост не завис, даже если accessibility-сервис вообще
     * не ответит: поток вернёт неотговорённый результат, но RPC-ответ уйдёт вовремя.
     */
    private fun clickOffRpcPool(action: () -> MediaUiOutcome): MediaUiOutcome {
        val settled = CompletableFuture<MediaUiOutcome>()
        uiClickExecutor.execute {
            val outcome =
                runCatching { action() }.getOrElse { error ->
                    MediaUiOutcome.Failed(error.message ?: "ui job failed")
                }
            settled.complete(outcome)
        }
        return runCatching {
            settled.get(
                MediaUiJob.MAX_TIMEOUT_MS + UI_CLICK_HARD_TIMEOUT_GRACE_MS,
                TimeUnit.MILLISECONDS,
            )
        }.getOrElse { MediaUiOutcome.Failed("ui job did not answer inside its budget") }
    }

    private fun mediaUiShield(
        output: BufferedOutputStream,
        request: Request,
    ) {
        val body = jsonObject(request)
        val placed =
            if (body.optBoolean("show", true)) {
                MediaAutomationShield.show(body.optionalLong("lifetime_ms", 2_000L))
            } else {
                MediaAutomationShield.hide()
                true
            }
        writeJson(
            output,
            200,
            JSONObject()
                .put("ok", placed)
                .put(
                    "shield",
                    JSONObject()
                        .put("showing", MediaAutomationShield.isShowing())
                        .put("can_draw_overlays", MediaAutomationShield.canDraw())
                        .put("reason", MediaAutomationShield.lastReason() ?: JSONObject.NULL),
                ),
        )
    }

    private fun likeMedia(
        output: BufferedOutputStream,
        request: Request,
    ) {
        val body = jsonObject(request)
        val spec =
            MediaControlRequestValidator.like(
                action = body.optionalString("action"),
                packageName = body.optionalString("package"),
            )
        val result = MediaControlController.like(spec)
        writeJson(output, 200, JSONObject().put("ok", result.ok).put("media", result.toJson()))
    }

    private fun mediaCapabilities(
        output: BufferedOutputStream,
        request: Request,
    ) {
        val packageName = MediaControlRequestValidator.status(parameter(request.query, "package"))
        val capabilities = MediaControlController.capabilities(packageName)
        writeJson(output, 200, JSONObject().put("ok", true).put("capabilities", capabilities.toJson()))
    }

    private fun playMedia(
        output: BufferedOutputStream,
        request: Request,
    ) {
        val body = jsonObject(request)
        val spec =
            MediaControlRequestValidator.play(
                packageName = body.optionalString("package"),
                mediaId = body.optionalString("media_id"),
                uri = body.optionalString("uri"),
                title = body.optionalString("title"),
            )
        val result = MediaControlController.play(spec)
        writeJson(output, 200, JSONObject().put("ok", true).put("media", result.toJson()))
    }

    private fun install(
        output: BufferedOutputStream,
        request: Request,
    ) {
        val body = jsonObject(request)
        val action = body.optString("action")
        require(action == "play" || action == "apk" || action == "local") {
            "action must be play, apk, or local"
        }
        val status =
            when (action) {
                "play" -> playRequest(body)
                "apk" -> apkRequest(body)
                else -> localApkRequest(body)
            }
        val code = if (action == "play") 200 else 202
        writeJson(output, code, JSONObject().put("ok", true).put("job", status.toJson()))
    }

    private fun playRequest(body: JSONObject): InstallJobSnapshot =
        ApkInstaller.openPlay(
            AppInstallRequestValidator.play(
                packageName = body.optionalString("package"),
                query = body.optionalString("query"),
            ),
        )

    private fun apkRequest(body: JSONObject): InstallJobSnapshot {
        val sizeBytes =
            if (body.has("size_bytes") && !body.isNull("size_bytes")) {
                body.getLong("size_bytes")
            } else {
                null
            }
        return ApkInstaller.submit(
            AppInstallRequestValidator.apk(
                url = body.getString("url"),
                sha256 = body.getString("sha256"),
                sizeBytes = sizeBytes,
                expectedPackageName = body.optionalString("package"),
                source = body.optionalString("source"),
                signingCertificateSha256 = body.optionalString("signing_certificate_sha256"),
            ),
        )
    }

    private fun localApkRequest(body: JSONObject): InstallJobSnapshot =
        ApkInstaller.submitLocal(
            AppInstallRequestValidator.local(
                path = body.getString("path"),
                sha256 = body.getString("sha256"),
                sizeBytes = body.getLong("size_bytes"),
                expectedPackageName = body.optionalString("package"),
                signingCertificateSha256 = body.optionalString("signing_certificate_sha256"),
            ),
        )

    private fun authorized(headers: Map<String, String>): Boolean {
        val provided = headers["authorization"]?.removePrefix("Bearer ").orEmpty()
        val expected = token
        val providedBytes = provided.toByteArray(StandardCharsets.UTF_8)
        val expectedBytes = expected.toByteArray(StandardCharsets.UTF_8)
        return expected.isNotBlank() &&
            provided.length == expected.length &&
            MessageDigest.isEqual(providedBytes, expectedBytes)
    }

    private fun readRequest(input: InputStream): Request {
        val requestLine = readLine(input, MAX_HEADER_BYTES) ?: throw IllegalArgumentException("empty request")
        val parts = requestLine.split(' ', limit = 3)
        require(parts.size == 3) { "malformed request line" }
        val target = URI(parts[1])
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input, MAX_HEADER_BYTES) ?: throw IllegalArgumentException("truncated headers")
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            require(separator > 0) { "malformed header" }
            headers[line.substring(0, separator).lowercase(Locale.ROOT)] = line.substring(separator + 1).trim()
        }
        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
        require(contentLength in 0L..MAX_BODY_BYTES.toLong()) { "request body is too large" }
        return Request(
            parts[0],
            target.path.orEmpty(),
            target.rawQuery.orEmpty(),
            headers,
            readBody(input, contentLength.toInt()),
        )
    }

    private fun readLine(
        input: InputStream,
        maxBytes: Int,
    ): String? {
        val output = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (output.size() == 0) null else output.toString(StandardCharsets.ISO_8859_1.name())
            if (value == '\n'.code) {
                val bytes = output.toByteArray()
                val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, end, StandardCharsets.ISO_8859_1)
            }
            require(output.size() < maxBytes) { "HTTP header is too large" }
            output.write(value)
        }
    }

    private fun readBody(
        input: InputStream,
        size: Int,
    ): String {
        val body = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(body, offset, size - offset)
            require(count >= 0) { "truncated request body" }
            offset += count
        }
        return String(body, StandardCharsets.UTF_8)
    }

    private fun parameter(
        query: String,
        name: String,
    ): String? =
        query.split('&').firstNotNullOfOrNull { item ->
            val parts = item.split('=', limit = 2)
            if (parts[0] == name) {
                URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8.name())
            } else {
                null
            }
        }

    private fun optionalIntParameter(
        query: String,
        name: String,
    ): Int? {
        val raw = parameter(query, name) ?: return null
        return raw.toIntOrNull() ?: throw IllegalArgumentException("$name must be an integer")
    }

    private fun jsonObject(request: Request): JSONObject =
        runCatching { JSONObject(request.body) }.getOrElse {
            throw IllegalArgumentException("body must be a JSON object")
        }

    private fun JSONObject.optionalString(name: String): String? =
        if (has(name) && !isNull(name)) getString(name).takeIf { it.isNotBlank() } else null

    private fun JSONObject.optionalLong(
        name: String,
        fallback: Long,
    ): Long = if (has(name) && !isNull(name)) getLong(name) else fallback

    private fun JSONObject.optionalStringList(name: String): List<String> {
        if (!has(name) || isNull(name)) return emptyList()
        val array = optJSONArray(name) ?: throw IllegalArgumentException("$name must be an array of strings")
        return (0 until array.length()).map { index ->
            array.optString(index).trim().takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("$name[$index] must be a non empty string")
        }
    }

    /** Прямоугольник приходит массивом [left,top,right,bottom] - ровно так, как его отдаёт not_found. */
    private fun JSONObject.optionalBounds(name: String): MediaUiBounds? {
        if (!has(name) || isNull(name)) return null
        val array = optJSONArray(name) ?: throw IllegalArgumentException("$name must be an array of 4 numbers")
        require(array.length() == 4) { "$name must have 4 numbers, got ${array.length()}" }
        val numbers = (0 until 4).map { index ->
            array.optDouble(index, Double.NaN).takeIf { it.isFinite() }?.toInt()
                ?: throw IllegalArgumentException("$name[$index] must be a number")
        }
        require(numbers.all { kotlin.math.abs(it) <= MediaUiBounds.LIMIT }) { "$name is off screen" }
        return MediaUiBounds(numbers[0], numbers[1], numbers[2], numbers[3])
    }

    private fun MediaUiBounds.toJson(): JSONArray =
        JSONArray()
            .put(left)
            .put(top)
            .put(right)
            .put(bottom)

    private fun MediaUiCandidate.toJson(): JSONObject =
        JSONObject()
            .put("label", label ?: JSONObject.NULL)
            .put("bounds", bounds?.toJson() ?: JSONObject.NULL)
            .put("clickable", clickable)
            .put("editable", editable)

    private fun MediaUiOutcome.toJson(): JSONObject =
        when (this) {
            is MediaUiOutcome.Performed ->
                JSONObject()
                    .put("state", "performed")
                    .put("action", action.kind)
                    .put("label", label)
                    .put("window_focused", windowFocused)
                    .put("gesture", gestureUsed)

            is MediaUiOutcome.Rejected ->
                JSONObject()
                    .put("state", "rejected")
                    .put("label", label)
                    .put("reason", reason)

            is MediaUiOutcome.NotFound ->
                JSONObject()
                    .put("state", "not_found")
                    .put("candidates", JSONArray().apply { candidates.forEach { put(it.toJson()) } })

            is MediaUiOutcome.Failed ->
                JSONObject()
                    .put("state", "failed")
                    .put("reason", reason)
        }

    private fun LaunchableAppSnapshot.toJson(): JSONObject =
        JSONObject()
            .put("package", packageName)
            .put("label", label)
            .put("component", componentName)
            .put("version", versionName)

    private fun LaunchedAppSnapshot.toJson(): JSONObject =
        JSONObject()
            .put("package", packageName)
            .put("label", label)
            .put("component", componentName)
            .put("message", message)

    private fun MediaAppSnapshot.toJson(): JSONObject =
        JSONObject()
            .put("package", packageName)
            .put("label", label)
            .put("session_service", sessionServices.firstOrNull() ?: JSONObject.NULL)
            .put("session_services", JSONArray(sessionServices))
            .put("media_button_receiver", mediaButtonReceiver ?: JSONObject.NULL)
            .put("hidden_session_services", hiddenSessionServices)
            .put("controlable", controlable)

    private fun MediaSearchResult.toJson(): JSONObject =
        JSONObject()
            .put("query", query)
            .put("artist", artist?.toJson() ?: JSONObject.NULL)
            .put(
                "tracks",
                JSONArray().apply {
                    tracks.forEach { track ->
                        put(
                            JSONObject()
                                .put("id", track.id)
                                .put("title", track.title)
                                .put("artist", track.artist)
                                .put("album", track.album)
                                .put("duration_ms", track.durationMs)
                                .put("available", track.available)
                                .put("uri", track.deepLink),
                        )
                    }
                },
            )

    private fun MediaLibraryResult.toJson(): JSONObject =
        JSONObject()
            .put("root", root?.toJson() ?: JSONObject.NULL)
            .put("state", message)
            .put("result_code", resultCode ?: JSONObject.NULL)
            .put(
                "entries",
                JSONArray().apply { entries.forEach { put(it.toJson()) } },
            )

    private fun MediaLibraryEntry.toJson(): JSONObject =
        JSONObject()
            .put("media_id", mediaId)
            .put("title", title)
            .put("subtitle", subtitle)
            .put("browsable", browsable)
            .put("playable", playable)
            .put("uri", uri ?: JSONObject.NULL)

    private fun MediaLikeResult.toJson(): JSONObject =
        JSONObject()
            .put("package", packageName)
            .put("label", label)
            .put("action", action)
            .put("ok", ok)
            .put("detail", detail ?: JSONObject.NULL)
            .put("playback", playback.toJson())

    private fun MediaCapabilities.toJson(): JSONObject =
        JSONObject()
            .put("player_commands", JSONArray(playerCommands))
            .put("session_commands", JSONArray(sessionCommands))
            .put("supports_set_media_item", supportsSetMediaItem)
            .put("player_error", playerError ?: JSONObject.NULL)

    private fun MediaPlaybackSnapshot.toJson(): JSONObject =
        JSONObject()
            .put("state", state)
            .put("is_playing", isPlaying)
            .put("title", title ?: JSONObject.NULL)
            .put("artist", artist ?: JSONObject.NULL)
            .put("album", album ?: JSONObject.NULL)
            .put("duration_ms", durationMs ?: JSONObject.NULL)
            .put("position_ms", positionMs ?: JSONObject.NULL)

    private fun MediaControlResult.toJson(): JSONObject =
        JSONObject()
            .put("package", packageName)
            .put("label", label)
            .put("transport", transport)
            .put("command", command)
            .put("delivered", true)
            .put("verified", verified)
            .put("message", message)
            .put("before", before?.toJson() ?: JSONObject.NULL)
            .put("after", after?.toJson() ?: JSONObject.NULL)

    private fun MediaStatusResult.toJson(): JSONObject =
        JSONObject()
            .put("package", packageName)
            .put("label", label)
            .put("transport", transport)
            .put("message", message)
            .put("playback", playback.toJson())

    private fun InstallJobSnapshot.toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("state", state.wireName)
            .put("source", source)
            .put("package", packageName ?: JSONObject.NULL)
            .put("message", message)
            .put("updated_at", updatedAt)
            .put("signing_certificate_sha256", signingCertificateSha256 ?: JSONObject.NULL)

    private fun error(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)

    private fun writeJson(
        output: BufferedOutputStream,
        statusCode: Int,
        body: JSONObject,
    ) {
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        val statusText =
            when (statusCode) {
                200 -> "OK"
                202 -> "Accepted"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                404 -> "Not Found"
                500 -> "Internal Server Error"
                else -> "Error"
            }
        val headers =
            "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-store\r\n" +
                "X-Content-Type-Options: nosniff\r\n\r\n"
        output.write(headers.toByteArray(StandardCharsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }
}
