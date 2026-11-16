package org.opencode.mobile.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.json.JSONObject
import org.opencode.mobile.BuildConfig
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Автообновление ядра opencode прямо из официального источника, без пересборки APK.
 *
 * Откуда берётся ядро: npm-пакет `@opencode/cli-linux-arm64-musl` — это
 * официальный дистрибутив opencode для Linux arm64/musl (GitHub-релизов у
 * opencode 2.x нет, установщик тоже ничего не качает). Скрипт
 * `tools/fetch-opencode-binary.ps1` тянет оттуда же для сборки APK, так что
 * приложение и CI ходят за одним и тем же артефактом.
 *
 * Почему это вообще возможно без установки APK: musl-лоадер `libldmusl.so` из
 * APK запускается первым аргументом и mmap-ит цель, поэтому бинарь в filesDir
 * исполняется без exec-права (на /sdcard такое не работает — там noexec:
 * проверено 16.11, лоадер отвечает «Not a valid dynamic program»).
 *
 * Порядок (каждый шаг может отказать и не ломает приложение):
 *  1. `registry.npmjs.org/@opencode%2Fcli-linux-arm64-musl/latest` → версия,
 *     URL тарбола, `dist.integrity` (sha512) и `dist.shasum` (sha1);
 *  2. версия новее текущей и в той же мажорной ветке (3.x не накатываем сами:
 *     app говорит с сервером по v2 API, смена мажора — это другой контракт);
 *  3. сеть не тарифицируется (245 МБ молча — нельзя);
 *  4. тарбол качается ПОТОКОМ: gzip → tar → нужный член `package/bin/opencode`
 *     сразу пишется на диск, sha512 считается по тем же байтам; отдельные
 *     118 МБ на тарбол не тратятся;
 *  5. sha512 сверяется с `dist.integrity` (официальная подпись пакета);
 *  6. пробный запуск: лоадер + кандидат `--version` должен напечатать
 *     `opencode v<версия>` — иначе «обновление» оставило бы приложение без
 *     сервера, а откатиться уже нечем;
 *  7. только теперь файл переезжает на `filesDir/core/libopencode.so`, версия
 *     пишется в prefs, вызывающий перезапускает serve.
 *
 * Ядро в APK остаётся резервом: пока скачанного нет (или проверка не прошла),
 * [resolveCore] возвращает путь в nativeLibraryDir.
 */
object CoreUpdater {
    private const val TAG = "CoreUpdater"

    /** Официальный источник. `%2F` — слэш в имени scoped-пакета. */
    private const val REGISTRY_LATEST =
        "https://registry.npmjs.org/@opencode%2Fcli-linux-arm64-musl/latest"

    private const val PREFS = "core_updater"
    private const val KEY_VERSION = "core_version"
    private const val KEY_BYTES = "core_bytes"
    private const val KEY_CHECKED_AT = "checked_at"

    private const val HOUR_MS = 3_600_000L
    private const val CHECK_EVERY_MS = 6 * HOUR_MS

    private const val DIR = "core"
    private const val FILE_NAME = "libopencode.so"
    private const val APK_FILE_NAME = "libopencode.so"
    private const val LOADER_NAME = "libldmusl.so"

    /** Член npm-тарбола с бинарём. */
    private const val TAR_MEMBER = "package/bin/opencode"

    private const val CONNECT_MS = 15_000
    private const val READ_MS = 30_000
    private const val SMOKE_TIMEOUT_S = 40L
    private const val TAR_BLOCK = 512
    private const val BUF = 128 * 1024

    /** Куда скачивается ядро. Только внутренняя память: /sdcard смонтирован noexec. */
    fun coreFile(context: Context): File = File(File(context.filesDir, DIR), FILE_NAME)

    /**
     * Версия ядра, которая реально будет запущена.
     *
     * Скачанное считается действующим только если оно новее того, что в APK:
     * после обновления приложения APK-ядро может оказаться свежее, и
     * «залипшее» скачанное не должно его перебивать.
     */
    fun effectiveVersion(context: Context): String {
        val apkVersion = BuildConfig.OPENCODE_VERSION
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val downloaded = prefs.getString(KEY_VERSION, null) ?: return apkVersion
        val file = coreFile(context)
        if (!file.isFile || file.length() != prefs.getLong(KEY_BYTES, 0L)) return apkVersion
        return if (isNewer(downloaded, apkVersion)) downloaded else apkVersion
    }

    /**
     * Путь к ядру для запуска: скачанное, если оно новее, иначе — из APK.
     * Ничего не бросает и не меняет: приложение обязано запускаться всегда.
     */
    fun resolveCore(context: Context): File {
        val apkCore = File(context.applicationInfo.nativeLibraryDir, APK_FILE_NAME)
        if (effectiveVersion(context) == BuildConfig.OPENCODE_VERSION) return apkCore
        val downloaded = coreFile(context)
        return if (downloaded.isFile && downloaded.length() > 0L) downloaded else apkCore
    }

    /**
     * Проверить официальный реестр и, если есть новее, накатить ядро.
     *
     * @param force игнорировать шестичасовой интервал.
     * @return true, если ядро заменено — вызывающий должен перезапустить serve.
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught", "LongMethod")
    fun checkAndApply(context: Context, force: Boolean = false): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_CHECKED_AT, 0L) < CHECK_EVERY_MS) return false
        prefs.edit().putLong(KEY_CHECKED_AT, now).apply()

        val meta = try {
            readLatest()
        } catch (e: Exception) {
            Log.w(TAG, "реестр не ответил: ${e.message}")
            return false
        }
        val current = effectiveVersion(context)
        if (!isNewer(meta.version, current)) return false
        if (!sameMajor(meta.version, current)) {
            Log.i(TAG, "в реестре ${meta.version}, у нас $current: мажор другой, сам не накатываю")
            return false
        }
        if (isMetered(context)) {
            Log.i(TAG, "ядро ${meta.version} есть, но сеть тарифицируется — обновлю позже")
            return false
        }

        val dir = File(context.filesDir, DIR)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.w(TAG, "нет каталога ${dir.absolutePath}")
            return false
        }
        val incoming = File(dir, "$FILE_NAME.incoming")
        incoming.delete()

        Log.i(TAG, "качаю ядро ${meta.version} из ${meta.tarball}")
        val shaOk = try {
            downloadMember(meta.tarball, incoming, meta.integrity)
        } catch (e: Exception) {
            Log.w(TAG, "скачивание не удалось: ${e.message}")
            false
        }
        if (!shaOk) {
            incoming.delete()
            return false
        }
        if (!smokeTest(context, incoming, meta.version)) {
            Log.w(TAG, "ядро ${meta.version} не запускается — оставляю $current")
            incoming.delete()
            return false
        }

        val target = File(dir, FILE_NAME)
        val backup = File(dir, "$FILE_NAME.previous")
        backup.delete()
        if (target.exists() && !target.renameTo(backup)) {
            Log.w(TAG, "не смог отодвинуть прошлое ядро — не рискую")
            incoming.delete()
            return false
        }
        if (!incoming.renameTo(target)) {
            Log.w(TAG, "не смог поставить новое ядро на место")
            backup.renameTo(target)
            incoming.delete()
            return false
        }
        backup.delete()
        prefs.edit()
            .putString(KEY_VERSION, meta.version)
            .putLong(KEY_BYTES, target.length())
            .apply()
        Log.i(TAG, "ядро обновлено: $current → ${meta.version} (${target.length()} б)")
        return true
    }

    private class Latest(
        val version: String,
        val tarball: String,
        val integrity: String,
    )

    @Suppress("TooGenericExceptionCaught")
    private fun readLatest(): Latest {
        val conn = (URL(REGISTRY_LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
        }
        val body = try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val json = JSONObject(body)
        val version = json.optString("version").trim()
        val dist = json.optJSONObject("dist") ?: error("в ответе нет dist")
        val tarball = dist.optString("tarball").trim()
        val integrity = dist.optString("integrity").trim()
        if (version.isEmpty() || tarball.isEmpty() || integrity.isEmpty()) {
            error("неполный ответ реестра: version='$version'")
        }
        return Latest(version, tarball, integrity)
    }

    /**
     * Скачивает тарбол потоком и вытаскивает из него [TAR_MEMBER], попутно считая
     * sha512 сжатых байтов (это и есть `dist.integrity` npm: `sha512-<base64>`).
     *
     * Поток дочитывается до конца даже после находки: иначе хеш считался бы по
     * половине файла и сверка была бы бессмысленной.
     */
    @Suppress("ReturnCount")
    private fun downloadMember(tarball: String, target: File, integrity: String): Boolean {
        val conn = (URL(tarball).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "тарбол: HTTP ${conn.responseCode}")
                return false
            }
            val digest = MessageDigest.getInstance("SHA-512")
            val counted = DigestInputStream(conn.inputStream.buffered(BUF), digest)
            val found = GZIPInputStream(counted, BUF).use { gz ->
                val ok = extractMember(gz, target)
                drain(gz)
                ok
            }
            if (!found) {
                Log.w(TAG, "в тарболе нет $TAR_MEMBER")
                return false
            }
            val actual = "sha512-" + Base64.getEncoder().encodeToString(digest.digest())
            if (actual != integrity) {
                Log.w(TAG, "integrity не сошлась: ${actual.take(20)}… вместо ${integrity.take(20)}…")
                return false
            }
            return true
        } finally {
            conn.disconnect()
        }
    }

    /** tar (ustar) поверх уже распакованного потока: нужен ровно один член. */
    @Suppress("ReturnCount")
    private fun extractMember(stream: InputStream, target: File): Boolean {
        val header = ByteArray(TAR_BLOCK)
        while (true) {
            if (!readFully(stream, header)) return false
            if (header.all { it.toInt() == 0 }) return false // конец архива

            val name = cString(header, 0, 100)
            val prefix = cString(header, 345, 155)
            val full = if (prefix.isEmpty()) name else "$prefix/$name"
            val sizeField = cString(header, 124, 12).trim { it == ' ' || it == '\u0000' }
            val size = if (sizeField.isEmpty()) 0L else sizeField.toLongOrNull(8) ?: 0L
            val type = header[156].toInt().toChar()

            if ((type == '0' || type == '\u0000') &&
                (full == TAR_MEMBER || full == "./$TAR_MEMBER")
            ) {
                target.outputStream().use { out -> copyN(stream, out, size) }
                return true
            }
            skipN(stream, size)
            skipN(stream, (TAR_BLOCK - (size % TAR_BLOCK)) % TAR_BLOCK)
        }
    }

    private fun cString(buf: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = minOf(offset + length, buf.size)
        while (end < limit && buf[end].toInt() != 0) end++
        return String(buf, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun readFully(stream: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = stream.read(buf, read, buf.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun copyN(stream: InputStream, out: OutputStream, count: Long) {
        var left = count
        val buf = ByteArray(BUF)
        while (left > 0) {
            val n = stream.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) error("tar: файл кончился раньше заявленного размера")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skipN(stream: InputStream, count: Long) {
        var left = count
        val buf = ByteArray(BUF)
        while (left > 0) {
            val n = stream.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) return
            left -= n
        }
    }

    private fun drain(stream: InputStream) {
        val buf = ByteArray(BUF)
        while (stream.read(buf) >= 0) {
            // дочитываем, чтобы sha512 покрывал весь тарбол
        }
    }

    /**
     * Пробный запуск кандидата: лоадер из APK + бинарь из filesDir, `--version`.
     * Ровно тот способ, которым запускается serve, поэтому успех здесь означает,
     * что и сервер поднимется.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private fun smokeTest(context: Context, candidate: File, version: String): Boolean {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val loader = File(nativeDir, LOADER_NAME)
        if (!loader.isFile) {
            Log.w(TAG, "нет лоадера $LOADER_NAME")
            return false
        }
        val muslDir = try {
            OpencodeRuntime.ensureMuslLibs(context)
        } catch (e: Exception) {
            Log.w(TAG, "musl-libs не готовы: ${e.message}")
            return false
        }
        val pb = ProcessBuilder(loader.absolutePath, candidate.absolutePath, "--version")
        pb.environment()["LD_LIBRARY_PATH"] = "${muslDir.absolutePath}:$nativeDir"
        pb.environment()["HOME"] = File(context.filesDir, "core-smoke").apply { mkdirs() }.absolutePath
        pb.environment()["NO_COLOR"] = "1"
        pb.redirectErrorStream(true)
        val proc = try {
            pb.start()
        } catch (e: Exception) {
            Log.w(TAG, "пробный запуск не стартовал: ${e.message}")
            return false
        }
        // Вывод читаем В ОТДЕЛЬНОМ потоке, а не readText() в этом: readText ждёт
        // EOF, а зависший кандидат EOF не даст — таймаут ниже просто не успел бы
        // сработать, и приложение повисло бы на «проверке» навсегда.
        val collected = java.io.ByteArrayOutputStream()
        val reader = Thread({
            runCatching { proc.inputStream.copyTo(collected) }
        }, "core-smoke-reader").apply { isDaemon = true; start() }

        if (!proc.waitFor(SMOKE_TIMEOUT_S, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            reader.join(2_000)
            Log.w(TAG, "пробный запуск завис: ${collected.toString("UTF-8").take(200)}")
            return false
        }
        reader.join(2_000)
        val output = collected.toString("UTF-8").trim()
        if (proc.exitValue() != 0 || !output.contains("opencode v$version")) {
            Log.w(TAG, "пробный запуск ответил не тем: exit=${proc.exitValue()} «${output.take(200)}»")
            return false
        }
        return true
    }

    /** Тарифицируемая сеть: 245 МБ без спроса — нельзя. */
    private fun isMetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        val flat = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return !flat
    }

    /**
     * Сравнение версий по числам, а не по строкам: лексикографически `2.10.0`
     * меньше `2.9.0`, и обновление бы не поехало.
     */
    internal fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.')
        val b = current.split('.')
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i)?.toIntOrNull() ?: 0
            val y = b.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x > y
        }
        return false
    }

    /** 2.x накатываем на 2.x, 3.x — нет: у сервера может смениться API. */
    internal fun sameMajor(candidate: String, current: String): Boolean =
        candidate.substringBefore('.').toIntOrNull() == current.substringBefore('.').toIntOrNull()
}
