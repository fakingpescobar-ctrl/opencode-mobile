package org.opencode.mobile.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.json.JSONObject
import org.opencode.mobile.BuildConfig
import java.io.ByteArrayOutputStream
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
 *  8. если `serve` на новом ядре не поднялся дважды подряд, файл удаляется,
 *     версия запоминается как отбракованная, а запуск возвращается к ядру из
 *     APK ([noteServerDown]). Без этого шага цикл самообновления мог бы сам
 *     себя выключить: агент живёт внутри `serve`, и чинить изнутри нечем.
 *
 * Ядро в APK остаётся резервом: пока скачанного нет (или проверка не прошла),
 * [resolveCore] возвращает путь в nativeLibraryDir.
 */
// Функций в объекте больше стандартного порога detekt (11): транспортировка
// tar-члена и сборка пути — разные обязанности, и склеивать их в одну длинную
// функцию хуже, чем иметь 23 коротких. Исключение точечное, как у OpencodeRuntime.
@Suppress("TooManyFunctions")
object CoreUpdater {
    private const val TAG = "CoreUpdater"

    /** Официальный источник. `%2F` — слэш в имени scoped-пакета. */
    private const val REGISTRY_LATEST = "https://registry.npmjs.org/@opencode%2Fcli-linux-arm64-musl/latest"

    private const val PREFS = "core_updater"
    private const val KEY_VERSION = "core_version"
    private const val KEY_BYTES = "core_bytes"
    private const val KEY_CHECKED_AT = "checked_at"

    /** Подряд падений `serve` на скачанном ядре до приговора «это ядро не годится». */
    private const val KEY_TRIES = "core_tries"

    /** Версия, на которой `serve` не поднялся: её больше не накатываем. */
    private const val KEY_BLOCKED = "core_blocked"

    /** Когда был откат: время нужно, чтобы отличие «ядро плохое» от «сеть упала» не терялось. */
    private const val KEY_ROLLED_AT = "core_rolled_back_at"

    /**
     * Итог последней проверки реестра. Хранится не для красоты: без него экран
     * мог показать только «сейчас ничего не происходит», а вопрос был «почему».
     * Время обязательно: «не новее» без отметки читается как «так и есть
     * сейчас», хотя проверка могла быть вчера.
     */
    private const val KEY_LAST_AT = "last_check_at"
    private const val KEY_LAST_OUTCOME = "last_check_outcome"
    private const val KEY_LAST_LATEST = "last_check_latest"

    /**
     * Два падения подряд — приговор. Одного мало: первый старт после установки
     * бывает неудачным по чужим причинам (нет сети, занят порт, память не
     * успела), и откатывать по нему значило бы никогда не обновиться.
     */
    private const val MAX_TRIES = 2

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

    // Раскладка заголовка tar (ustar), байты от начала блока. Числа — из формата,
    // не «на глаз»: имя 100, размер 12 (восьмеричное ASCII), тип 1, префикс 155.
    private const val TAR_NAME_OFFSET = 0
    private const val TAR_NAME_LEN = 100
    private const val TAR_SIZE_OFFSET = 124
    private const val TAR_SIZE_LEN = 12
    private const val TAR_TYPE_OFFSET = 156
    private const val TAR_PREFIX_OFFSET = 345
    private const val TAR_PREFIX_LEN = 155
    private const val TAR_RADIX = 8

    /** Сколько символов хеша/вывода показывать в логе. */
    private const val PREVIEW_CHARS = 20
    private const val PREVIEW_OUTPUT = 200

    /** Сколько ждать поток-читатель после того, как процесс уже завершился. */
    private const val READER_JOIN_MS = 2_000L

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
        val onDisk = file.isFile && file.length() == prefs.getLong(KEY_BYTES, 0L)
        val blocked = prefs.getString(KEY_BLOCKED, null)
        return if (onDisk && isUsable(downloaded, apkVersion, blocked)) downloaded else apkVersion
    }

    /**
     * Годится ли скачанное ядро к запуску: новее того, что в APK, и не
     * отбраковано падением. Вынесено из [effectiveVersion], чтобы это правило
     * можно было проверить тестом, а не только на телефоне.
     */
    internal fun isUsable(
        downloaded: String,
        apkVersion: String,
        blocked: String?,
    ): Boolean = downloaded != blocked && isNewer(downloaded, apkVersion)

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
     * Сервер ответил по HTTP на скачанном ядре — снимаем счётчик неудач.
     *
     * Это единственное доказательство, что ядро рабочее: пробный запуск
     * `--version` при установке говорит лишь о том, что ELF стартует, а не что
     * из него поднимается `serve`. Поэтому «здоров» подтверждает не установка,
     * а работа.
     */
    fun noteServerUp(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_TRIES, 0) == 0) return
        prefs.edit().putInt(KEY_TRIES, 0).apply()
        Log.i(TAG, "ядро ${effectiveVersion(context)} подняло сервер — счётчик падений сброшен")
    }

    /**
     * `serve` не поднялся на скачанном ядре.
     *
     * Одно падение — только счёт. [MAX_TRIES]-е подряд — откат: файл удаляется,
     * версия запоминается как отбракованная, `resolveCore` начинает отдавать
     * ядро из APK. Без этого шага плохая версия ядра оставляла приложение без
     * сервера навсегда: чинить нечем, потому что агент живёт внутри `serve`.
     *
     * Ядро из APK сюда не попадает намеренно: его падение — не повод что-то
     * откатывать, откатываться некуда, и счётчик тогда врал бы.
     */
    @Suppress("ReturnCount")
    fun noteServerDown(context: Context) {
        val apkVersion = BuildConfig.OPENCODE_VERSION
        val active = effectiveVersion(context)
        if (active == apkVersion) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tries = prefs.getInt(KEY_TRIES, 0) + 1
        if (tries < MAX_TRIES) {
            prefs.edit().putInt(KEY_TRIES, tries).apply()
            Log.w(TAG, "ядро $active не подняло сервер ($tries из $MAX_TRIES)")
            return
        }
        Log.w(TAG, "ядро $active не поднимается $tries раза подряд — откатываюсь на $apkVersion")
        coreFile(context).delete()
        val editor = prefs.edit()
        editor.putString(KEY_BLOCKED, active)
        editor.putLong(KEY_ROLLED_AT, System.currentTimeMillis())
        editor.putInt(KEY_TRIES, 0)
        editor.remove(KEY_VERSION)
        editor.remove(KEY_BYTES)
        editor.apply()
    }

    /**
     * Проверить официальный реестр и, если есть новее, накатить ядро.
     *
     * @param force игнорировать шестичасовой интервал.
     * @return true, если ядро заменено — вызывающий должен перезапустить serve.
     */
    @Suppress("ReturnCount")
    /**
     * Почему проверка закончилась так, а не иначе.
     *
     * Раньше наружу отдавался Boolean, и экран вынужденно угадывал причину
     * одной фразой на три исхода: «версия не новее, либо сеть тарифицируется,
     * либо сверка не прошла». По такой фразе нельзя ни починить, ни успокоиться,
     * а в логе самый частый исход («в реестре ровно наша версия») не оставлял
     * вообще ничего — то есть проверить догадку было нечем. Теперь причина —
     * значение: у каждого исхода своя строка и в UI, и в логе.
     */
    enum class Outcome {
        APPLIED,
        NOT_NEWER,
        BLOCKED,
        MAJOR,
        METERED,
        NOT_DUE,
        REGISTRY,
        DOWNLOAD,
        SMOKE,
        NO_DIR,
        INSTALL,
    }

    /** Итог проверки: что решили, с чем сравнивали и когда смотрели. */
    data class Result(
        val outcome: Outcome,
        val at: Long,
        val latest: String?,
        val current: String,
        val apk: String,
    ) {
        val applied: Boolean get() = outcome == Outcome.APPLIED

        /** Одна строка для экрана: причина и версии, без союза «либо». */
        fun text(): String =
            when (outcome) {
                Outcome.APPLIED -> "ядро $latest скачано, сверено и поставлено"
                Outcome.NOT_NEWER -> "в реестре $latest, работает $current — обновлять нечего"
                Outcome.BLOCKED -> "в реестре $latest — эта версия уже отбракована падением сервера"
                Outcome.MAJOR -> "в реестре $latest, работает $current — мажор другой, сам не накатываю"
                Outcome.METERED -> "в реестре $latest, но сеть тарифицируется — обновлю по Wi-Fi"
                Outcome.NOT_DUE -> "окно 6 часов не истекло — проверю сам позже"
                Outcome.REGISTRY -> "реестр npm не ответил — сеть или блокировка"
                Outcome.DOWNLOAD -> "ядро $latest не скачалось или не сошлось по sha512"
                Outcome.SMOKE -> "ядро $latest не запускается — оставил $current"
                Outcome.NO_DIR -> "нет каталога для ядра во внутренней памяти"
                Outcome.INSTALL -> "не удалось поставить файл на место — оставил $current"
            }
    }

    /**
     * Итог последней проверки — как факт с временем, а не как память экрана.
     * null означает «ещё ни разу не проверяли», и это отличается от «проверили
     * и всё хорошо»: первое — незнание, второе — ответ.
     */
    @Suppress("ReturnCount")
    fun lastCheck(context: Context): Result? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_LAST_AT, 0L)
        if (at <= 0L) return null
        val name = prefs.getString(KEY_LAST_OUTCOME, null) ?: return null
        val outcome = Outcome.values().firstOrNull { it.name == name } ?: return null
        return Result(
            outcome = outcome,
            at = at,
            latest = prefs.getString(KEY_LAST_LATEST, null),
            current = effectiveVersion(context),
            apk = BuildConfig.OPENCODE_VERSION,
        )
    }

    /** Запоминает итог и пишет в лог одну строку — чтобы UI и лог говорили одно. */
    private fun report(
        context: Context,
        result: Result,
    ): Result {
        Log.i(
            TAG,
            "проверка: ${result.outcome.name} — в реестре ${result.latest ?: "?"}, " +
                "работает ${result.current}, в APK ${result.apk}",
        )
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        editor.putLong(KEY_LAST_AT, result.at)
        editor.putString(KEY_LAST_OUTCOME, result.outcome.name)
        val latest = result.latest
        if (latest != null) editor.putString(KEY_LAST_LATEST, latest) else editor.remove(KEY_LAST_LATEST)
        editor.apply()
        return result
    }

    /**
     * Спрашивает реестр и, если есть версия новее, ставит её.
     *
     * Возвращает не Boolean, а [Result]: «ничего не сделал» — это минимум шесть
     * разных ответов (не новее, отбраковано, другой мажор, тарифицируемая сеть,
     * реестр молчит, не прошла сверка). Склеивать их в `false` значит терять
     * ровно то, что нужно человеку, который нажал кнопку.
     */
    @Suppress("ReturnCount")
    fun checkAndApply(
        context: Context,
        force: Boolean = false,
    ): Result {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val current = effectiveVersion(context)
        val apk = BuildConfig.OPENCODE_VERSION
        if (!force && now - prefs.getLong(KEY_CHECKED_AT, 0L) < CHECK_EVERY_MS) {
            // «Тихо» — это тоже исход, и у него есть причина: окно не истекло.
            return Result(Outcome.NOT_DUE, now, null, current, apk)
        }
        prefs.edit().putLong(KEY_CHECKED_AT, now).apply()

        val meta = readLatestOrNull()
            ?: return report(context, Result(Outcome.REGISTRY, now, null, current, apk))
        val refusal = refusal(meta.version, current, prefs.getString(KEY_BLOCKED, null), isMetered(context))
        if (refusal != null) {
            return report(context, Result(refusal, now, meta.version, current, apk))
        }

        val dir = ensureCoreDir(context)
            ?: return report(context, Result(Outcome.NO_DIR, now, meta.version, current, apk))
        val incoming = File(dir, "$FILE_NAME.incoming").apply { delete() }
        if (!fetchVerified(meta, incoming)) {
            incoming.delete()
            return report(context, Result(Outcome.DOWNLOAD, now, meta.version, current, apk))
        }
        if (!smokeTest(context, incoming, meta.version)) {
            incoming.delete()
            return report(context, Result(Outcome.SMOKE, now, meta.version, current, apk))
        }
        if (!install(context, incoming, dir, meta, current)) {
            return report(context, Result(Outcome.INSTALL, now, meta.version, current, apk))
        }
        return report(context, Result(Outcome.APPLIED, now, meta.version, meta.version, apk))
    }

    /** Самая дорогая часть: скачать, сверить подпись пакета, проверить запуск. */
    @Suppress("TooGenericExceptionCaught")
    private fun fetchVerified(
        meta: Latest,
        incoming: File,
    ): Boolean {
        Log.i(TAG, "качаю ядро ${meta.version} из ${meta.tarball}")
        val shaOk = try {
            downloadMember(meta.tarball, incoming, meta.integrity)
        } catch (e: Exception) {
            Log.w(TAG, "скачивание не удалось: ${e.message}")
            false
        }
        if (shaOk) return true
        Log.w(TAG, "ядро ${meta.version} не скачалось или не сошлось по sha512")
        return false
    }

    /**
     * Причина не качать вообще — до сети и до диска. `null` означает «причин
     * нет, качаем».
     *
     * Вынесено в чистую функцию ради тестов: раньше это была ветка с четырьмя
     * `return false`, и проверялась она только руками на телефоне.
     */
    @Suppress("ReturnCount")
    internal fun refusal(
        latest: String,
        current: String,
        blocked: String?,
        metered: Boolean,
    ): Outcome? {
        if (!isNewer(latest, current)) return Outcome.NOT_NEWER
        if (latest == blocked) return Outcome.BLOCKED
        if (!sameMajor(latest, current)) return Outcome.MAJOR
        if (metered) return Outcome.METERED
        return null
    }

    private fun ensureCoreDir(context: Context): File? {
        val dir = File(context.filesDir, DIR)
        val ready = dir.isDirectory || dir.mkdirs()
        if (!ready) Log.w(TAG, "нет каталога ${dir.absolutePath}")
        return if (ready) dir else null
    }

    /** Атомарная замена: прошлое ядро отодвигается и возвращается при неудаче. */
    @Suppress("ReturnCount")
    private fun install(
        context: Context,
        incoming: File,
        dir: File,
        meta: Latest,
        previous: String,
    ): Boolean {
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
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString(KEY_VERSION, meta.version)
        editor.putLong(KEY_BYTES, target.length())
        editor.putInt(KEY_TRIES, 0)
        editor.apply()
        Log.i(TAG, "ядро обновлено: $previous → ${meta.version} (${target.length()} б)")
        return true
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readLatestOrNull(): Latest? =
        try {
            readLatest()
        } catch (e: Exception) {
            Log.w(TAG, "реестр не ответил: ${e.message}")
            null
        }

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
    private fun downloadMember(
        tarball: String,
        target: File,
        integrity: String,
    ): Boolean {
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
                val shown = actual.take(PREVIEW_CHARS)
                Log.w(TAG, "integrity не сошлась: $shown… вместо ${integrity.take(PREVIEW_CHARS)}…")
                return false
            }
            return true
        } finally {
            conn.disconnect()
        }
    }

    /** tar (ustar) поверх уже распакованного потока: нужен ровно один член. */
    @Suppress("ReturnCount")
    private fun extractMember(
        stream: InputStream,
        target: File,
    ): Boolean {
        val header = ByteArray(TAR_BLOCK)
        while (true) {
            if (!readFully(stream, header)) return false
            if (header.all { it.toInt() == 0 }) return false // конец архива

            val name = cString(header, TAR_NAME_OFFSET, TAR_NAME_LEN)
            val prefix = cString(header, TAR_PREFIX_OFFSET, TAR_PREFIX_LEN)
            val full = if (prefix.isEmpty()) name else "$prefix/$name"
            val sizeField = cString(header, TAR_SIZE_OFFSET, TAR_SIZE_LEN).trim { it == ' ' || it == '\u0000' }
            val size = if (sizeField.isEmpty()) 0L else sizeField.toLongOrNull(TAR_RADIX) ?: 0L
            val type = header[TAR_TYPE_OFFSET].toInt().toChar()

            if (isWantedMember(type, full)) {
                target.outputStream().use { out -> copyN(stream, out, size) }
                return true
            }
            skipN(stream, size)
            skipN(stream, (TAR_BLOCK - (size % TAR_BLOCK)) % TAR_BLOCK)
        }
    }

    /** Обычный файл (а не каталог/ссылка/заголовок pax) с нужным именем. */
    private fun isWantedMember(
        type: Char,
        fullPath: String,
    ): Boolean {
        val regular = type == '0' || type == '\u0000'
        return regular && (fullPath == TAR_MEMBER || fullPath == "./$TAR_MEMBER")
    }

    private fun cString(
        buf: ByteArray,
        offset: Int,
        length: Int,
    ): String {
        var end = offset
        val limit = minOf(offset + length, buf.size)
        while (end < limit && buf[end].toInt() != 0) end++
        return String(buf, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun readFully(
        stream: InputStream,
        buf: ByteArray,
    ): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = stream.read(buf, read, buf.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun copyN(
        stream: InputStream,
        out: OutputStream,
        count: Long,
    ) {
        var left = count
        val buf = ByteArray(BUF)
        while (left > 0) {
            val n = stream.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) error("tar: файл кончился раньше заявленного размера")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skipN(
        stream: InputStream,
        count: Long,
    ) {
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
            // тело пустое намеренно: дочитываем поток, чтобы sha512 покрывал
            // весь тарбол, а не только байты до найденного члена
        }
    }

    /**
     * Пробный запуск кандидата: лоадер из APK + бинарь из filesDir, `--version`.
     * Ровно тот способ, которым запускается serve, поэтому успех здесь означает,
     * что и сервер поднимется.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private fun smokeTest(
        context: Context,
        candidate: File,
        version: String,
    ): Boolean {
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
        val collected = ByteArrayOutputStream()
        val reader = Thread({
            runCatching { proc.inputStream.copyTo(collected) }
        }, "core-smoke-reader")
        reader.isDaemon = true
        reader.start()

        if (!proc.waitFor(SMOKE_TIMEOUT_S, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            reader.join(READER_JOIN_MS)
            Log.w(TAG, "пробный запуск завис: ${collected.toString("UTF-8").take(PREVIEW_OUTPUT)}")
            return false
        }
        reader.join(READER_JOIN_MS)
        val output = collected.toString("UTF-8").trim()
        if (proc.exitValue() != 0 || !output.contains("opencode v$version")) {
            val shown = output.take(PREVIEW_OUTPUT)
            Log.w(TAG, "пробный запуск ответил не тем: exit=${proc.exitValue()} «$shown»")
            return false
        }
        return true
    }

    /** Тарифицируемая сеть: 245 МБ без спроса — нельзя. */
    private fun isMetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !isUnmetered(caps)
    }

    /** Wi-Fi и Ethernet считаем бесплатными, всё остальное (LTE, VPN поверх LTE) — нет. */
    private fun isUnmetered(caps: NetworkCapabilities): Boolean =
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

    /**
     * Сравнение версий по числам, а не по строкам: лексикографически `2.10.0`
     * меньше `2.9.0`, и обновление бы не поехало.
     */
    internal fun isNewer(
        candidate: String,
        current: String,
    ): Boolean {
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
    internal fun sameMajor(
        candidate: String,
        current: String,
    ): Boolean = candidate.substringBefore('.').toIntOrNull() == current.substringBefore('.').toIntOrNull()

    private class Latest(
        val version: String,
        val tarball: String,
        val integrity: String,
    )
}
