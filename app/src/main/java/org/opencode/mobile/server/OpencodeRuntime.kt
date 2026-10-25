package org.opencode.mobile.server

import android.content.Context
import org.opencode.mobile.BuildConfig
import org.opencode.mobile.OpencodeApp
import org.opencode.mobile.installer.AppInstallBridge
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Запуск standalone opencode serve на Android без root.
 *
 * Архитектура (см. исследование 28.08.2026):
 *  - opencode-linux-arm64-musl — ДИНАМИЧЕСКАЯ musl-сборка (bun build --compile),
 *    PT_INTERP=/lib/ld-musl-aarch64.so.1, DT_NEEDED = [libstdc++.so.6,
 *    libc.musl-aarch64.so.1, libgcc_s.so.1].
 *  - Бинарь и musl-лоадер кладутся в nativeLibraryDir (ЕДИНСТВЕННОЕ место,
 *    откуда untrusted_app может exec-нуть ELF на Android 10+, targetSdk>=29).
 *  - Файлы называются lib*.so чтобы PackageManager их извлёк (useLegacyPackaging).
 *  - Лоадер запускается ПЕРВЫМ аргументом (ld-musl libopencode.so serve ...), т.к.
 *    захардкодить PT_INTERP на случайный nativeLibraryDir нельзя (read-only).
 *  - Зависимые .so копируются в filesDir с ПРАВИЛЬНЫМИ именами (по DT_NEEDED),
 *    LD_LIBRARY_PATH указывает туда. dlopen из filesDir разрешён (noexec касается
 *    только execve бинаря, не загрузки .so в существующий процесс).
 */

// Класс перекреёк порога выше лимита detekt, и после плагина
// молтбука он ещё не добавил ещё строк общего класса: логика уеёзет ведёт себя в JS.
@Suppress("TooManyFunctions", "LargeClass")
object OpencodeRuntime {
    private const val LAUNCH_PERMISSION_KEY = "\"mobile_launch_app\""
    private const val BIN_NAME = "libopencode.so" // в nativeLibraryDir
    private const val LOADER_NAME = "libldmusl.so" // в nativeLibraryDir
    private const val BUN_NAME = "libbun-musl.so" // встроенный musl-Bun в nativeLibraryDir
    const val MEMORY_PORT = 4199 // TCP/Streamable-порт локальной памяти
    const val YNISON_PORT = 4200 // TCP/Streamable-порт локального MCP Яндекс.Музыки

    // Хосты Яндекса, чьи IP резолвит Java и передаёт в ynison.js (см. resolveIpv4):
    // скрипт получает готовый IP и шлёт реальный hostname в Host-заголовке.
    //
    // Замер 26.10.2026: musl-Bun на Android DNS РЕЗОЛВИТ — dns.lookup шарда вернул
    // 77.88.48.156. Прежнее «не умеет» было неверным, и на нём стоял баг: state-сокет
    // получал IP фронтенда вместо адреса шарда и молчал 10 с. Эти IP — подстраховка для
    // редиректа, а не единственный способ: адрес шарда скрипт резолвит сам.
    internal const val API_HOST = "api.music.yandex.net"
    internal const val YNISON_HOST = "ynison.music.yandex.ru"

    // Зависимые musl-libs. Источник — nativeLibraryDir (там они лежат под lib*-именами,
    // так их извлекает PackageManager). При старте копируются в filesDir/musl
    // с ИМЕНАМИ, совпадающими с DT_NEEDED opencode, т.к. на эти имена указывает
    // лоадер через LD_LIBRARY_PATH.
    //
    // ВАЖНО: нельзя просто положить их в nativeLibraryDir под правильными именами —
    // PackageManager извлекает только файлы `lib*.so`, а у DT_NEEDED имена вида
    // `libstdc++.so.6` / `libc.musl-aarch64.so.1` (суффикс не `.so`) — не извлекутся.
    // Поэтому: placeholder имена в jniLibs (`libstdcxx.so` и т.п.) + копия в filesDir.
    private val MUSL_LIBS = listOf(
        Triple("libc.musl-aarch64.so.1", "libc_musl.so", true),
        Triple("libstdc++.so.6", "libstdcxx.so", true),
        Triple("libgcc_s.so.1", "libgcc_s.so", true),
    )

    /** Готов ли runtime (бинарь + лоадер на месте). */
    fun isAssembled(context: Context): Boolean {
        val nativeDir = nativeLibraryDir(context)
        return File(nativeDir, BIN_NAME).exists() && File(nativeDir, LOADER_NAME).exists()
    }

    /**
     * Копирует зависимые musl-libs из nativeLibraryDir в filesDir/musl с нужными
     * именами. Идемпотентно. При ошибке выбрасывает Throwable — вызывающий
     * превращает это в ERROR-статус (нельзя продолжать со сломанным runtime).
     */
    @Throws(Exception::class)
    fun ensureMuslLibs(context: Context): File {
        val nativeDir = nativeLibraryDir(context)
        val muslDir = File(context.filesDir, "musl")
        muslDir.mkdirs()
        for ((neededName, srcName, required) in MUSL_LIBS) {
            val dest = File(muslDir, neededName)
            val src = File(nativeDir, srcName)
            if (!src.exists()) {
                if (required) throw IllegalStateException("musl lib $srcName missing in nativeLibraryDir")
                continue
            }
            // Пропуск только при полном совпадении размера: файл, обрезанный
            // убийством процесса посреди copyTo, не должен считаться валидным
            // навсегда (как >0-чек в ModelDownloader до фикса).
            if (dest.exists() && dest.length() == src.length()) continue
            // Атомарная замена: tmp + rename, чтобы обрыв записи не оставил
            // кривой .so по целевому имени.
            val tmp = File(muslDir, "$neededName.tmp")
            src.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                throw IllegalStateException("musl lib $neededName: tmp rename failed")
            }
        }
        // verf: нужные файлы непустые
        for ((neededName, _, required) in MUSL_LIBS) {
            if (!required) continue
            val f = File(muslDir, neededName)
            if (!f.exists() || f.length() == 0L) {
                throw IllegalStateException("musl lib $neededName not materialized")
            }
        }
        return muslDir
    }

    /**
     * Собирает и запускает процесс opencode serve.
     * @return запущенный процесс (или null, если не собрался)
     */
    @Suppress("LongMethod", "ReturnCount")
    fun startServe(
        context: Context,
        additionalArgs: List<String> = emptyList(),
        logFile: File,
        workDir: File? = null,
        extraEnv: Map<String, String> = emptyMap(),
    ): Process? {
        if (!isAssembled(context)) {
            android.util.Log.e("OpencodeRuntime", "runtime not assembled (bin/loader missing)")
            return null
        }
        val nativeDir = nativeLibraryDir(context)
        val loader = File(nativeDir, LOADER_NAME).absolutePath
        val bin = File(nativeDir, BIN_NAME).absolutePath

        val muslDir: String = try {
            ensureMuslLibs(context).absolutePath
        } catch (e: Exception) {
            android.util.Log.e("OpencodeRuntime", "ensureMuslLibs failed: ${e.message}")
            return null
        }

        val cmd = ArrayList<String>()
        cmd.add(loader) // interpreter-first: ld-musl ./opencode ...
        cmd.add(bin)
        cmd.add("serve")
        cmd.add("--port")
        cmd.add(OpencodeApp.ServerConfig.PORT.toString())
        // --hostname по умолчанию 127.0.0.1 — безопасно, наружу не торчим.
        // Важно: эта версия opencode serve НЕ поддерживает --dir (выводит help и
        // выходит). Рабочая директория задаётся через CWD процесса (pb.directory).
        cmd.addAll(additionalArgs)

        android.util.Log.i("OpencodeRuntime", "exec: $cmd")

        // Каталоги приложения, куда opencode пишет (HOME/XDG_*).
        val cfg = OpencodeApp.ServerConfig
        val pb = ProcessBuilder(cmd)
        // IPv4 CONNECT-прокси: opencode (bun) не делает fallback IPv6->IPv4 для
        // моделей.opencode.ai / opencode.ai/zen (Cloudflare отдаёт AAAA первыми, на
        // устройстве нет IPv6-маршрута) — «Transport error / Timeout», как сейчас
        // видно на models.dev без прокси. curl с телефона работает (happy-eyeballs),
        // bun — нет. Прокси резолвит строго по IPv4; TLS остаётся end-to-end.
        val proxyPort = Ipv4Proxy.ensureStarted()
        if (proxyPort != null) {
            val proxy = "http://127.0.0.1:$proxyPort"
            pb.environment()["HTTPS_PROXY"] = proxy
            pb.environment()["HTTP_PROXY"] = proxy
            pb.environment()["NO_PROXY"] = "127.0.0.1,localhost,${cfg.opencodeCache.absolutePath}"
        }
        if (workDir != null) {
            pb.directory(workDir)
        }

        // PATH — где работать оттуда. opencode ищет git/rg; на девайсе их нет,
        // это ожидаемо на этапе MVP.
        pb.environment()["HOME"] = cfg.opencodeHome.absolutePath
        pb.environment()["TMPDIR"] = cfg.opencodeCache.absolutePath
        pb.environment()["XDG_CONFIG_HOME"] = cfg.opencodeConfig.absolutePath
        pb.environment()["XDG_DATA_HOME"] = cfg.opencodeData.absolutePath
        pb.environment()["XDG_CACHE_HOME"] = cfg.opencodeCache.absolutePath
        // muslDir (filesDir/musl с именами DT_NEEDED) в приоритете; nativeDir на всякий случай
        pb.environment()["LD_LIBRARY_PATH"] = "$muslDir:$nativeDir"
        pb.environment()["NO_COLOR"] = "1"
        // Пароль serve: warning «server is unsecured» в логе уходит. Сервер висит на 127.0.0.1,
        // наружу не торчит, но пароль всё равно зададим — формально «secured» и лог чище.
        // Единственная точка создания/чтения — ServerAuth.ensurePassword (load-or-create,
        // зашифрован в prefs ключом Android Keystore): onCreate MainActivity и startServe
        // получают ОДИН пароль, рассинхрон auth невозможен (cold-start edge PR1.3 закрыт).
        val serverPwd = ServerAuth.ensurePassword(context)
        pb.environment()["OPENCODE_SERVER_PASSWORD"] = serverPwd
        // Пустые/безопасные значения чтобы opencode не ныл
        pb.environment()["PATH"] = (pb.environment()["PATH"] ?: "") + ":" + nativeDir
        // Путь к встроенному musl-Bun (libbun-musl.so + лидирующий loader libldmusl.so).
        // Оба в nativeLibraryDir — это ЕДИНСТВЕННОЕ место, откуда untrusted_app может
        // exec-нуть ELF. Дочерние MCP-процессы (запускаемые opencode из конфига)
        // наследуют этот env, поэтому конфиг может юзать $MCP_NATIVE_DIR стабильно,
        // не завися от меняющегося при переустановке пути /data/app/<pkg>-*/.../lib.
        pb.environment()["MCP_NATIVE_DIR"] = nativeDir
        // Где локальная память MCP хранит SQLite (векторы+граф). По умолчанию HOME/.memory.
        pb.environment()["MCP_MEMORY_DIR"] = File(cfg.opencodeHome, ".memory").absolutePath
        pb.environment().putAll(extraEnv)

        // КРИТИЧНО для локальных MCP (stdio transport): НЕЛЬЗЯ редиректить stdout serve
        // в файл. opencode запускает дочерние MCP процессы (например встроенный bun через
        // libldmusl) и ждёт от них JSON-RPC по pipe. Если serve сам редиректит stdout в файл,
        // все дочерние наследуют этот файл вместо pipe, и opencode не читает ответ MCP ->
        // "Operation timed out after 30000ms". Поэтому: stdout/stderr serve -> отдельные pipes,
        // а их содержимое мы в фоне дублируем в logFile для отладки (redirect через pipe не
        // мешает opencode создавать нормальные stdio pipes у своих MCP детей).
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE)

        val proc =
            runCatching { pb.start() }
                .onFailure { e -> android.util.Log.e("OpencodeRuntime", "failed to start opencode: ${e.message}") }
                .getOrNull() ?: return null
        // Лог открывается уже после старта. Если это не удалось (нет места, не создался
        // каталог), ребёнок держит порт 4096, а его stdout никто не читает — он повиснет
        // на заполненной трубе, и следующий старт упрётся в занятый порт. Такой отказ
        // обязан убить процесс.
        val out =
            runCatching {
                if (logFile.parentFile?.exists() != true) logFile.parentFile?.mkdirs()
                FileOutputStream(logFile, true)
            }.onFailure { e ->
                android.util.Log.e("OpencodeRuntime", "failed to open serve log, killing child: ${e.message}")
                proc.destroy()
            }.getOrNull() ?: return null
        // Поток-логгер: читает stdout serve (уже слитый со stderr) и пишет в файл.
        Thread {
            try {
                val buf = ByteArray(8192)
                var n: Int
                proc.inputStream.use { inp ->
                    while (inp.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        out.flush()
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { out.close() }
            }
        }.apply {
            isDaemon = true
            name = "opencode-log"
        }.start()
        return proc
    }

    private fun nativeLibraryDir(context: Context): String = context.applicationInfo.nativeLibraryDir

    /**
     * Копирует memory.js (Streamable HTTP MCP-сервер локальной памяти) из встроенных
     * assets в filesDir/mem/memory.js, откуда его может запустить встроенный musl-Bun.
     * Возвращает путь к скрипту (или null при ошибке).
     *
     * Вместе с ним копируется provenance.js: memory.js импортирует его, и без
     * спутника файл не стартует вообще. Поэтому отсутствие provenance.js — это не
     * «часть памяти недоступна», а «памяти нет»: возвращаем null, чтобы падение было
     * заметным, а не тихой пустой базой без источников.
     */
    fun ensureMemoryScript(context: Context): File? {
        val main = ensureAssetScript(context, MEMORY_ASSET, MEMORY_SCRIPT)
        val companion = ensureAssetScript(context, PROVENANCE_ASSET, PROVENANCE_SCRIPT) != null
        if (!companion) {
            android.util.Log.e(
                "OpencodeRuntime",
                "ensureMemoryScript: ${PROVENANCE_SCRIPT} not installed, memory MCP disabled " +
                    "(memory.js imports ./${PROVENANCE_SCRIPT})",
            )
        }
        return if (companion) main else null
    }

    /**
     * Копирует ynison.js — MCP-мост к Яндекс.Музыке. Тот же путь в filesDir/mem, потому
     * что запуск у них общий: тот же musl-Bun, тот же LD_LIBRARY_PATH, тот же приём.
     */
    fun ensureYnisonScript(context: Context): File? = ensureAssetScript(context, YNISON_ASSET, YNISON_SCRIPT)

    /**
     * Копирует retention.js — обрезку раздутого журнала событий — в filesDir/mem.
     *
     * Запускать его нужно ОТСЮДА, а не из adb shell / run-as: файл базы на sdcard
     * принадлежит uid приложения с правами 0660, и процесс, запущенный через
     * run-as, получает на него EACCES. Внутри приложения доступ есть.
     */
    fun ensureRetentionScript(context: Context): File? = ensureAssetScript(context, RETENTION_ASSET, RETENTION_SCRIPT)

    /**
     * Копирует moltbook.js — MCP-сервер плагина Moltbook — в filesDir/mem.
     *
     * Путь тот же, что у памяти и музыки: запуск у них общий (musl-загрузчик,
     * LD_LIBRARY_PATH, HOME/XDG), и отдельная папка для плагина означала бы ещё одну
     * копию обвязки запуска ради файла, который запускается ровно так же.
     */
    fun ensureMoltbookScript(context: Context): File? = ensureAssetScript(context, MoltbookMcp.ASSET, MoltbookMcp.SCRIPT)

    private fun ensureAssetScript(
        context: Context,
        assetName: String,
        scriptName: String,
    ): File? {
        val dir = File(context.filesDir, SCRIPT_DIR).apply { mkdirs() }
        val dest = File(dir, scriptName)
        return try {
            // assets - источник истины: всегда сверяем, перезаписываем если отличается
            // (install -r сохраняет app data/filesDir, старая копия оставалась и тормозила фиксы).
            val source = context.assets.open(assetName).use { input ->
                input.readBytes()
            }
            val changed = !dest.exists() ||
                dest.length() != source.size.toLong() ||
                !dest.readBytes().contentEquals(source)
            if (changed) {
                val tmp = File(dir, "$scriptName.tmp")
                tmp.writeBytes(source)
                if (!tmp.renameTo(dest)) {
                    android.util.Log.e("OpencodeRuntime", "ensureAssetScript: tmp->dest rename failed for $scriptName")
                    tmp.delete()
                    return null
                }
                android.util.Log.i(
                    "OpencodeRuntime",
                    "ensureAssetScript: wrote ${source.size}B to ${dest.absolutePath}",
                )
            }
            dest
        } catch (e: Exception) {
            android.util.Log.e("OpencodeRuntime", "ensureAssetScript($scriptName) failed: ${e.message}")
            null
        }
    }

    /**
     * Запускает локальную память MCP как ОТДЕЛЬНЫЙ TCP/Streamable HTTP-сервер
     * (порт MEMORY_PORT). Это намеренный обход: local MCP через stdio у этой сборки
     * opencode не работает, т.к. она НЕ создаёт отдельный stdio-pipe своим дочерним
     * MCP-процессам — они наследуют stdin/stdout самого serve, и ответ JSON-RPC
     * уходит не туда (таймаут 30s без ошибок спавна). TCP-Server подключается как
     * remote MCP (url http://127.0.0.1:4199/mcp) — как context7 (SSE/Streamable), который
     * стабильно работает. HTTPS_PROXY не нужен (localhost вынесен в NO_PROXY).
     * @return запущенный процесс (или null)
     */
    @Suppress("ReturnCount")
    fun startMemoryServer(
        context: Context,
        logFile: File,
        workDir: File? = null,
        extraEnv: Map<String, String> = emptyMap(),
    ): Process? =
        ensureMemoryScript(context)?.let { script ->
            startMcpServer(
                context = context,
                logFile = logFile,
                workDir = workDir,
                launch =
                    McpLaunch(
                        script = script,
                        port = MEMORY_PORT,
                        label = MEMORY_LABEL,
                        env =
                            mapOf(
                                "MCP_MEMORY_DIR" to
                                    File(OpencodeApp.ServerConfig.opencodeHome, ".memory").absolutePath,
                                "MCP_TCP_PORT" to MEMORY_PORT.toString(),
                            ) + extraEnv,
                    ),
            )
        }

    /**
     * Поднимает ynison.js как отдельный TCP-сервер на [YNISON_PORT].
     *
     * Тот же приём, что и с памятью, по одной причине: local MCP через stdio у этой
     * сборки opencode не работает (дочерний процесс наследует stdin/stdout serve, и ответ
     * JSON-RPC уходит не туда). Поэтому — процесс, TCP-порт, регистрация как remote MCP.
     *
     * Без токена Яндекса не запускаемся вовсе, а не «запускаемся и падаем»: скрипт без
     * `YNISON_TOKEN` всё равно не поднимет сокет, а мёртвый процесс в UI выглядит как
     * поломка приложения. Отсутствие подключения — это нормальное состояние, а не авария.
     */
    internal fun startYnisonServer(
        context: Context,
        logFile: File,
        workDir: File? = null,
        credentials: YnisonCredentials,
    ): Process? =
        credentials
            .takeIf { it.accessToken.isNotBlank() }
            ?.let { creds ->
                ensureYnisonScript(context)?.let { script ->
                    val hosts =
                        YnisonHosts(
                            apiIp = resolveIpv4(API_HOST),
                            ynisonIp = resolveIpv4(YNISON_HOST),
                        )
                    startMcpServer(
                        context = context,
                        logFile = logFile,
                        workDir = workDir,
                        launch =
                            McpLaunch(
                                script = script,
                                port = YNISON_PORT,
                                label = YNISON_LABEL,
                                env =
                                    buildMap {
                                        put("YNISON_TOKEN", creds.accessToken)
                                        put("MCP_YNISON_TOKEN", creds.mcpToken)
                                        put("YNISON_DEVICE_ID", creds.deviceId)
                                        put("YNISON_TCP_PORT", YNISON_PORT.toString())
                                        // Трассировка в ynison.js есть, но была недостижима:
                                        // YNISON_DEBUG никто не выставлял, так что весь код
                                        // trace() молчал, и всё это расследование шло вслепую -
                                        // ни числа сокетов на операцию, ни момента, когда телефон
                                        // пропал из-за go-away. Только в debug: в release трассировка
                                        // пишет на каждый вызов инструмента.
                                        if (BuildConfig.DEBUG) {
                                            put("YNISON_DEBUG", "1")
                                            put("YNISON_TRACE_FILE", File(workDir ?: context.filesDir, "ynison-trace.log").absolutePath)
                                        }
                                        // Готовый IP как подстраховка: скрипт умеет резолвить
                                        // и сам (замер 26.10.2026), но адрес редиректа приходит
                                        // отсюда вместе с реальным hostname для Host-заголовка.
                                        hosts.apiIp?.let { put("YNISON_API_IP", it) }
                                        hosts.ynisonIp?.let { put("YNISON_WS_IP", it) }
                                        // Мост нужен, чтобы поднять Яндекс.Музыку, когда она не в сети:
                                        // писать в очередь можно только устройству, которое реально играет.
                                        putAll(AppInstallBridge.environment())
                                    },
                            ),
                    )
                }
            }

    /**
     * Системный (Java) резолв хоста — подстраховка для адреса редиректа.
     *
     * Раньше здесь стояло «нативный musl-Bun на Android вообще не резолвит DNS», и на этом
     * утверждении стоял баг 26.10.2026: скрипт получал IP фронтенда и предъявлял шардовый
     * Host, фронтенд не отвечал вовсе (10 с тишины, `state open timeout`). Замер: dns.lookup
     * в том же bun на устройстве резолвит нормально, поэтому адрес шарда скрипт резолвит сам
     * (resolveStateHost в ynison.js), а не получает снаружи.
     */
    private fun resolveIpv4(host: String): String? =
        runCatching {
            InetAddress
                .getAllByName(host)
                .firstOrNull { it is Inet4Address }
                ?.hostAddress
        }.getOrNull()

    /**
     * Общий запуск встроенного musl-Bun над локальным MCP-скриптом.
     *
     * Вынесен, потому что у памяти и музыки совпадает всё, кроме скрипта, порта и пары
     * env-переменных. Держать копию значит со временем получить две расходящиеся копии
     * LD_LIBRARY_PATH-обвязки, причём разъезд заметят только на устройстве — в тестах
     * оба процесса запускаются одинаково успешно.
     *
     * Порт, метка и env сложены в [McpLaunch]: семь позиционных параметров выглядели бы
     * одинаково, а переставить `port` с `logFile` компилятор не поймал бы.
     */
    internal fun startMoltbookServer(
        context: Context,
        logFile: File,
        workDir: File? = null,
        extraEnv: Map<String, String> = emptyMap(),
    ): Process? {
        // Состояние молтбука переносится из приватного каталога наружу ДО старта
        // плагина: иначе обновлённое приложение нашло бы новый путь пустым и
        // объявило `ledger-missing`, хотя журнал лежит рядом, в старом месте.
        val migration = MoltbookPaths.migrateFromPrivate(context)
        if (migration.moved.isNotEmpty()) {
            android.util.Log.i("OpencodeRuntime", "moltbook state moved outside filesDir: ${migration.moved.joinToString()}")
        }
        if (!migration.complete) {
            // Перенос не довёз - и это не повод создавать базу на новом месте.
            android.util.Log.w(
                "OpencodeRuntime",
                "moltbook state NOT fully moved, journal left alone: ${migration.failed.joinToString()}",
            )
        }
        // Схему журнала создаёт Kotlin, а не плагин: moltbook.js не делает ни одного
        // CREATE TABLE и на отсутствующем файле честно отдаёт `ledger-missing`. Так
        // что на чистой установке базы нет, и создавать её больше некому - тикер,
        // который раньше это делал, мёртв, а будильник и сторож базу только читают.
        // Поэтому трогаем её здесь: до старта плагина, чтобы к моменту его старта
        // файл уже существовал и плагин не отказался на пустом месте.
        //
        // Но только если перенос довёз: пустая база по целевому пути закрыла бы
        // перенос навсегда (план пропускает всё, что уже существует), и настоящий
        // журнал из filesDir не переехал бы больше никогда - молча.
        if (migration.complete) {
            ensureMoltbookLedger(context)
        }
        val base =
            mapOf(
                "MCP_MOLTBOOK_DIR" to MoltbookPaths.dir(context).absolutePath,
                "MCP_MOLTBOOK_DB_DIR" to MoltbookPaths.databasesDir(context).absolutePath,
                "MCP_MOLTBOOK_KEY_FILE" to keyFile(context).absolutePath,
                "MCP_MOLTBOOK_PORT" to MoltbookMcp.PORT.toString(),
                // Отчёт ПК-агента. Сюда идёт ТОЛЬКО настоящее внешнее хранилище:
                // [Workspace.resolve] при отсутствии MANAGE_EXTERNAL_STORAGE молча
                // отдаёт песочницу, и плагин написал бы отчёт по пути, который
                // ПК-агент открыть не может, а ответ назвал бы «written». Пустая
                // строка - честный отказ: плагин пишет приватную копию и сам
                // помечает результат «ПК-агент отчёт не увидит».
                "MCP_MOLTBOOK_REPORT_DIR" to moltbookReportDir(context),
            )
        val env = base + proxyEnv() + extraEnv
        val script = ensureMoltbookScript(context) ?: return null
        return startMcpServer(
            context = context,
            logFile = logFile,
            workDir = workDir,
            launch = McpLaunch(script = script, port = MoltbookMcp.PORT, label = MoltbookMcp.LABEL, env = env),
        )
    }

    /**
     * Каталог отчёта для ПК-агента — только если внешнее хранилище действительно доступно.
     *
     * Возвращает пустую строку вместо пути-песочницы намеренно: пустой каталог плагин
     * трактует как «внешней копии не будет» и говорит об этом вслух, а путь внутрь
     * песочницы выглядел бы как успешно записанный отчёт, который никто не увидит.
     */
    private fun moltbookReportDir(context: Context): String {
        if (!Workspace.usingExternal(context)) return ""
        return Workspace.externalRoot()?.absolutePath ?: ""
    }

    /**
     * Создать журнал молтбука, если его ещё нет, и закрыть его сразу.
     *
     * Отдельной функцией, а не строкой в [startMoltbookServer], потому что у вызова
     * есть ровно одна обязанность, которую легко пропустить, глядя на старт плагина:
     * базы может не быть вообще. Молча пропустить это нельзя - плагин на
     * отсутствующем файле отдаёт `ledger-missing`, и карточка молчит.
     *
     * Закрываем сразу же: `readableDatabase` открывает соединение, а держать его
     * ради одной проверки - значит держать файловый дескриптор до перезапуска
     * процесса ради ничего.
     */
    private fun ensureMoltbookLedger(context: Context) {
        when (val ready = openLedgerForPlugin(context)) {
            LedgerReady.Exists -> Unit // Журнал уже есть - трогать нечего.
            is LedgerReady.Created -> {
                // Закрываем сразу же: readableDatabase открывает соединение, а держать
                // его ради одной проверки - значит держать файловый дескриптор до
                // перезапуска процесса ради ничего.
                ready.ledger.close()
                val file = java.io.File(MoltbookPaths.databasesDir(context), MoltbookMcp.LEDGER_NAME)
                android.util.Log.i("OpencodeRuntime", "moltbook journal created at ${file.absolutePath}")
            }
            is LedgerReady.Failed ->
                // Молчание здесь стоило бы истории: плагин стартует, честно отвечает
                // ledger-missing, карточка молчит, и видно это только по симптому
                // в другом месте. Поэтому причина уходит в лог целиком.
                android.util.Log.w("OpencodeRuntime", "moltbook journal NOT created: ${ready.cause}", ready.cause)
        }
    }

    /**
     * Прокси для musl-Bun. Прежнее обоснование «встроенный musl-Bun не резолвит DNS,
     * поэтому его fetch падает с transport-ошибкой (status 0)» оказалось неверным
     * (замер 26.10.2026: `dns.lookup` резолвит). Настоящая причина — в [Ipv4Proxy]:
     * DNS отдаёт IPv6, сеть устройства его не маршрутизирует, а у bun нет отката на
     * IPv4. Тот же приём, что у serve ([startServe]), вынесен отдельно: память
     * и музыки наружу не ходят и прокси не получают.
     */
    private fun proxyEnv(): Map<String, String> {
        val port = Ipv4Proxy.ensureStarted() ?: return emptyMap()
        val proxy = "http://127.0.0.1:$port"
        return mapOf("HTTPS_PROXY" to proxy, "HTTP_PROXY" to proxy)
    }

    @Suppress("ReturnCount")
    private fun startMcpServer(
        context: Context,
        launch: McpLaunch,
        logFile: File,
        workDir: File?,
    ): Process? {
        if (!isAssembled(context)) return null
        val port = launch.port
        val label = launch.label
        val nativeDir = nativeLibraryDir(context)
        val loader = File(nativeDir, LOADER_NAME).absolutePath
        val bun = File(nativeDir, BUN_NAME).absolutePath

        val cmd = ArrayList<String>()
        cmd.add(loader) // ld-musl загрузчик первым
        cmd.add(bun) // сам runtime
        cmd.add(launch.script.absolutePath) // наш MCP-скрипт

        android.util.Log.i("OpencodeRuntime", "starting $label http server: $cmd (port $port)")

        val cfg = OpencodeApp.ServerConfig
        val pb = ProcessBuilder(cmd)
        if (workDir != null) pb.directory(workDir)
        pb.environment()["HOME"] = cfg.opencodeHome.absolutePath
        pb.environment()["TMPDIR"] = cfg.opencodeCache.absolutePath
        pb.environment()["XDG_CONFIG_HOME"] = cfg.opencodeConfig.absolutePath
        pb.environment()["XDG_DATA_HOME"] = cfg.opencodeData.absolutePath
        pb.environment()["XDG_CACHE_HOME"] = cfg.opencodeCache.absolutePath
        val muslDir: String? = try {
            ensureMuslLibs(context).absolutePath
        } catch (e: Exception) {
            // Запускать процесс с кривым LD_LIBRARY_PATH незачем: он гарантированно
            // умрёт на старте (не найдёт libc) и молча оставит сервер нерабочим.
            android.util.Log.e("OpencodeRuntime", "$label: ensureMuslLibs failed: ${e.message}")
            return null
        }
        pb.environment()["LD_LIBRARY_PATH"] = "$muslDir:$nativeDir"
        pb.environment()["NO_COLOR"] = "1"
        pb.environment()["PATH"] = (pb.environment()["PATH"] ?: "") + ":" + nativeDir
        pb.environment()["MCP_NATIVE_DIR"] = nativeDir
        pb.environment().putAll(launch.env)
        pb.environment()["NO_PROXY"] = "127.0.0.1,localhost"

        // stdout/stderr -> общий лог: stdio не нужен, транспорт TCP.
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))

        return runCatching {
            pb.start()
        }.onFailure { e ->
            android.util.Log.e("OpencodeRuntime", "failed to start $label server: ${e.message}")
        }.getOrNull()
    }

    /**
     * Гарантирует, что opencode serve знает про локальную память MCP как
     * remote-сервер (http://127.0.0.1:MEMORY_PORT/mcp) и передаёт bearer-token
     * через {env:MCP_MEMORY_TOKEN}. Без регистрации в конфиге serve память
     * слушает порт, но модель не получает её инструменты; без auth локальный
     * endpoint не должен принимать MCP-запросы.
     *
     * Конфиг serve: $XDG_CONFIG_HOME/opencode/opencode.jsonc (opencode читает
     * Global.Path.config = XDG_CONFIG_HOME + "/opencode", см. opencode-src
     * packages/opencode/src/config/config.ts:379-381). Формат MCP-секции:
     * "mcp": { "<имя>": { "type": "remote", "url": "...", "headers": {...} } }.
     *
     * Старую сгенерированную секцию без заголовка обновляем атомарно; незнакомую
     * ручную секцию не перезаписываем и возвращаем false, чтобы не включить
     * неаутентифицированный доступ. Пишем tmp + rename, чтобы обрыв записи не
     * оставил битый jsonc.
     * @return true когда конфиг гарантированно содержит защищённый memory-MCP.
     */
    fun ensureMcpConfig(
        memoryToken: String,
        ynisonToken: String? = null,
        moltbookReady: Boolean = false,
        routing: ModelRouting? = null,
    ): Boolean =
        runCatching {
            require(memoryToken.isNotBlank()) { "MCP memory token is empty" }
            val cfg = OpencodeApp.ServerConfig
            val configHome = File(cfg.opencodeConfig, "opencode")
            // Мусор от v1 убираем до подготовки конфига: пока node_modules на месте,
            // в нём лежит package.json со строкой "1.18.25", и агент, спрашивающий
            // версию, читает её вместо живого /api/info.
            V1Leftovers.purge(configHome)
            val file = File(configHome, "opencode.jsonc")
            val ok = ensureMcpConfigFile(file, ynisonToken, moltbookReady, routing)
            // Эталон в Documents пишем только после успеха: копия неразобранного
            // конфига хуже отсутствия копии — потом её не отличить от рабочей.
            if (ok) mirrorConfig(file)
            ok
        }.onFailure { error ->
            android.util.Log.e("OpencodeRuntime", "ensureMcpConfig failed: ${error.message}")
        }.getOrDefault(false)

    /**
     * Кладёт копию конфига в общую папку, откуда её читает ПК-агент по adb.
     *
     * Одностороннее зеркало: приватный конфиг - источник истины, внешний -
     * производная копия ТОЛЬКО НА ЧТЕНИЕ. Обратного импорта нет и не должно
     * быть, поэтому при расхождении внешний перезаписывается. Типичная причина
     * расхождения - правка зеркала руками: она будет стёрта, и об этом стоит
     * сказать прямо в лог, а не оставлять догадку.
     *
     * Ошибка молча игнорируется: это удобство для диагностики, а не условие
     * работоспособности. Недоступное внешнее хранилище (нет прав, нет места)
     * не должно мешать серверу стартовать.
     */
    private fun mirrorConfig(configFile: File) {
        val mirror = OpencodeApp.ServerConfig.mirroredConfigFile() ?: return
        runCatching {
            val privateText = configFile.readTextOrEmpty()
            if (mirror.exists()) {
                val mirrorText = mirror.readTextOrEmpty()
                if (mirrorText == privateText) return@runCatching
                android.util.Log.w(
                    "OpencodeRuntime",
                    "config mirror перезаписан приватным: private=${privateText.length} " +
                        "mirror=${mirrorText.length}. Источник истины - приватный конфиг; " +
                        "правки зеркала вручную не сохраняются.",
                )
            }
            mirror.parentFile?.mkdirs()
            mirror.writeText(privateText)
        }.onFailure {
            android.util.Log.w("OpencodeRuntime", "config mirror skipped: ${it.message}")
        }
    }

    private fun ensureMcpConfigFile(
        file: File,
        ynisonToken: String?,
        moltbookReady: Boolean,
        routing: ModelRouting?,
    ): Boolean {
        val memoryReady = ensureMemorySection(file)
        // Синхронизаторы идут по очереди и каждый читает файл заново: предыдущий мог его переписать.
        val afterMusic = memoryReady && syncMusicEntry(file, file.readTextOrEmpty(), ynisonToken)
        // Плагин идет последним и читает файл после музыки. Отказ любого шага - отказ целого файла:
        // наполовину применённый конфиг хуже неверного, потому что агент увидит память без плагина и решит, что так и задумано.
        val afterMoltbook = afterMusic && syncMoltbookEntry(file, file.readTextOrEmpty(), moltbookReady)
        // Модель закрепляется после всех, когда файл точно существует. Это НЕ часть готовности:
        // без закреплённого дефолта сервер всё равно поднимается (opencode выберет модель сам),
        // поэтому отказ здесь означает «оставим выбор opencode», а не «не запускаемся».
        syncModelEntry(file, file.readTextOrEmpty())
        // Ключ question — не часть готовности по той же причине: без него сервер
        // поднимается, вопросы просто не доходят до карточки. Отказ здесь означает
        // «карточка останется немой», а не «не запускаемся».
        syncQuestionPermission(file, file.readTextOrEmpty())
        // Маршрутизация — тоже не часть готовности: без неё сервер поднимается и
        // работает на одном дефолте. Отказ означает «оставим один общий дефолт».
        syncRoutingEntry(file, file.readTextOrEmpty(), routing)
        return afterMoltbook
    }

/**
     * Применяет выбранную пользователем маршрутизацию к управляемому конфигу.
     *
     * Зовётся из UI после сохранения пикера — чтобы не ждать следующего старта
     * сервера. null-маршрутизация (пользователь её не включал) — ничего не делаем.
     *
     * Возвращает не Boolean, а [RoutingApply]: «файл изменился» и «файл уже был
     * таким» — разные ситуации для UI (см. [RoutingApply]). Но и [RoutingApply]
     * ещё не ответ на вопрос «а работает ли агент на новых моделях»: конфиг
     * opencode читается только при старте процесса, поэтому это проверяет
     * [org.opencode.mobile.server.RoutingReadiness].
     */
    fun applyModelRouting(context: Context): RoutingApply =
        runCatching {
            val routing = ModelRoutingPrefs.read(context) ?: return@runCatching RoutingApply.FAILED
            val cfg = OpencodeApp.ServerConfig
            val file = File(File(cfg.opencodeConfig, "opencode"), "opencode.jsonc")
            if (!file.exists()) return@runCatching RoutingApply.FAILED
            when (val plan = planRoutingEntry(file.readTextOrEmpty(), routing)) {
                RoutingPlan.Keep -> RoutingApply.UNCHANGED
                is RoutingPlan.Write ->
                    if (writeMemoryConfigText(file, plan.text)) {
                        RoutingApply.WRITTEN
                    } else {
                        RoutingApply.FAILED
                    }
                RoutingPlan.Refuse -> {
                    android.util.Log.w(
                        "OpencodeRuntime",
                        "applyModelRouting: config shape not recognised; leaving routing untouched",
                    )
                    RoutingApply.FAILED
                }
            }
        }.onFailure {
            android.util.Log.e("OpencodeRuntime", "applyModelRouting failed: ${it.message}")
        }.getOrDefault(RoutingApply.FAILED)

    /**
     * Держит блок `agent` и строку `small_model` вровень с выбором пользователя.
     *
     * Не трогаем, если маршрутизация не включена: у тех, кто пикер не открывал, в
     * конфиге не должно появиться ни новой секции, ни нового ключа.
     */
    private fun syncRoutingEntry(
        file: File,
        text: String,
        routing: ModelRouting?,
    ) {
        if (routing == null) return
        when (val plan = planRoutingEntry(text, routing)) {
            RoutingPlan.Keep -> Unit
            is RoutingPlan.Write ->
                if (!writeMemoryConfigText(file, plan.text)) {
                    android.util.Log.w(
                        "OpencodeRuntime",
                        "Could not apply model routing; agents keep the default model",
                    )
                }
            RoutingPlan.Refuse ->
                android.util.Log.w(
                    "OpencodeRuntime",
                    "Config shape is not recognised; leaving model routing untouched",
                )
        }
    }

    /**
     * Закрепляет бесплатную модель по умолчанию.
     *
     * Зачем: если в конфиге нет `model`, opencode выбирает модель сам — по данным удалённого
     * каталога. Каталог врёт (модель может быть помечена active/enabled, а провайдер на неё
     * отвечает 410 ModelDeprecated), и тогда агент молчит: каждый ход заканчивается ошибкой,
     * а в ленте не появляется ни слова. Строка `"model": "opencode/big-pickle"` снимает эту
     * зависимость от каталога — провайдер `opencode` отдаёт её бесплатно и без ключа.
     *
     * Чужой выбор не трогаем: ключ `model` в конфиге может стоять внутри агента, и молча
     * переписать решение пользователя опаснее, чем оставить как есть.
     */
    private fun syncModelEntry(
        file: File,
        text: String,
    ) {
        when (val plan = planModelEntry(text)) {
            ModelConfigPlan.Keep -> Unit
            is ModelConfigPlan.Write -> {
                if (!writeMemoryConfigText(file, plan.text)) {
                    android.util.Log.w(
                        "OpencodeRuntime",
                        "Could not pin the default model; opencode will pick one itself",
                    )
                }
            }
            ModelConfigPlan.Refuse ->
                android.util.Log.w(
                    "OpencodeRuntime",
                    "Config shape is not recognised; leaving the model choice to opencode",
                )
        }
    }

    /**
     * Закрепляет `"question": "ask"`, чтобы вопросы доходили до карточки.
     *
     * Зачем. Пока ключа нет, инструмент question не спрашивает разрешения: он
     * стартует и навсегда остаётся в `status=running`, а `GET
     * /api/session/{id}/permission` возвращает пустой список — карточка есть, но
     * показать ей нечего. На устройстве это читалось как «карточка вопроса опять
     * не вызвалась». Подробности разбора — в [planQuestionPermission].
     *
     * Отказ здесь не важен для запуска: сервер поднимется и без ключа, просто
     * вопросы останутся без ответа.
     */
    private fun syncQuestionPermission(
        file: File,
        text: String,
    ) {
        when (val plan = planQuestionPermission(text)) {
            PermissionPlan.Keep -> Unit
            is PermissionPlan.Write ->
                if (!writeMemoryConfigText(file, plan.text)) {
                    android.util.Log.w(
                        "OpencodeRuntime",
                        "Could not pin the question permission; the ask card will stay silent",
                    )
                }
            PermissionPlan.Refuse ->
                android.util.Log.w(
                    "OpencodeRuntime",
                    "No permission section in the config; cannot pin the question permission",
                )
        }
    }

    /**
     * Держит запись «moltbook» в конфиге вровень с тем, поднялся ли плагин.
     *
     * Отдельным шагом по той же причине, что и музыка ([syncMusicEntry]): плагин может
     * не подняться (нет ключа, нет файла ассета, порт занят), и тогда модель получила бы
     * инструмент, который гарантированно не отвечает. Отсутствие подключения - нормальное
     * состояние, а не авария, поэтому мы не запускаемся вовсе, а не «запускаемся и падаем».
     */
    private fun syncMoltbookEntry(
        file: File,
        text: String,
        ready: Boolean,
    ): Boolean =
        when (val plan = planMoltbookEntry(text, ready)) {
            MoltbookEntryPlan.Keep -> true
            is MoltbookEntryPlan.Write -> writeMemoryConfigText(file, plan.text)
            MoltbookEntryPlan.Refuse -> {
                android.util.Log.w(
                    "OpencodeRuntime",
                    "Managed mcp block has unexpected formatting; leaving moltbook MCP unregistered",
                )
                false
            }
        }

    /**
     * Доводит секцию mcp до управляемой двухсерверной формы (память + телефон).
     *
     * Лестница миграций старая и не тронута: каждая ступень — это точное совпадение
     * ранее сгенерированной нами формы. Непохожий текст означает чужой (ручной) конфиг,
     * и мы его не переписываем, а отказываемся, чтобы не включить неаутентифицированный
     * доступ к локальным портам.
     */
    private fun ensureMemorySection(file: File): Boolean {
        val text = file.readTextOrNull()
        val memoryKey = "\"${MemoryMcp.MEMORY_NAME}\""
        return when {
            text == null -> writeManagedConfig(file, managedConfigBlock())
            !text.contains(memoryKey) && !text.contains("\"mcp\"") ->
                writeManagedConfig(file, managedConfigBlock())
            !text.contains(memoryKey) -> {
                android.util.Log.w(
                    "OpencodeRuntime",
                    "Existing mcp config is not managed; refusing to append a duplicate mcp object",
                )
                false
            }
            // Старая форма без auth-заголовка: доращиваем до двух серверов.
            text.contains(legacyMemoryBlock()) ->
                upgradeMcpSection(file, text.replace(legacyMemoryBlock(), mcpEntries()))
            // Управляемая форма, но одним сервером: memory прятала в себе ещё и
            // инструменты телефона, из-за чего в UI она называлась «1 MCP» с
            // семью мобильными тулзами внутри. Разносим на два сервера.
            text.contains(singleServerMcpObject()) ->
                upgradeMcpSection(file, text.replace(singleServerMcpObject(), mcpBlock()))
            // Промежуточная форма: сервер управления телефоном назывался "mobile".
            text.contains(mobileAliasMcpObject()) ->
                upgradeMcpSection(file, text.replace(mobileAliasMcpObject(), mcpBlock()))
            // Уже актуальная форма, с music или без неё — осталось только проверить auth
            // и дописать permission-ключи, если их ещё нет.
            hasManagedMcpBlock(text) &&
                text.contains(AUTH_HEADER) &&
                text.contains(MEMORY_MCP_URL) -> ensureManagedLaunchPermission(file, text)
            else -> {
                android.util.Log.w("OpencodeRuntime", "Existing memory MCP config has no managed auth header")
                false
            }
        }
    }

    /**
     * Держит запись «music» в конфиге вровень с тем, подключён ли Яндекс.
     *
     * Отдельным шагом, а не третьим сервером в [mcpEntries], по двум причинам.
     * Первая: запись условная — без токена Яндекса порт не поднят, и модель получила бы
     * шесть инструментов, которые гарантированно не работают (хуже их не бывает только
     * в диагностике, где такой сервер горит красным). Вторая: при добавлении music внутрь
     * [mcpEntries] «актуальная форма» [mcpBlock] стала бы зависеть от наличия токена, и
     * лестница миграций перестала бы отличать старую форму от новой — любой существующий
     * конфиг упал бы в ветку «не управляемый» и тихо потерял бы регистрацию.
     *
     * Правится только точное совпадение известного блока: чужую секцию mcp по-прежнему
     * не трогаем.
     */
    private fun syncMusicEntry(
        file: File,
        text: String,
        ynisonToken: String?,
    ): Boolean =
        when (val plan = OpencodeRuntime.planMusicEntry(text, ynisonToken)) {
            MusicConfigPlan.Keep -> true
            is MusicConfigPlan.Write -> writeMemoryConfigText(file, plan.text)
            MusicConfigPlan.Refuse -> {
                android.util.Log.w(
                    "OpencodeRuntime",
                    "Managed mcp block has unexpected formatting; leaving music MCP unregistered",
                )
                false
            }
        }

    private const val AUTH_HEADER = "\"Authorization\": \"Bearer {env:MCP_MEMORY_TOKEN}\""
    private const val MEMORY_MCP_URL = "http://127.0.0.1:$MEMORY_PORT${MemoryMcp.MEMORY_PATH}"
    private const val MOBILE_MCP_URL = "http://127.0.0.1:$MEMORY_PORT${MemoryMcp.TOOLS_PATH}"
    private const val YNISON_MCP_URL = "http://127.0.0.1:$YNISON_PORT${YnisonMcp.PATH}"
    private const val SCRIPT_DIR = "mem"
    private const val MEMORY_ASSET = "mcp/memory.js"
    private const val MEMORY_SCRIPT = "memory.js"
    private const val PROVENANCE_ASSET = "mcp/provenance.js"
    private const val PROVENANCE_SCRIPT = "provenance.js"
    private const val YNISON_ASSET = "mcp/ynison.js"
    private const val YNISON_SCRIPT = "ynison.js"
    private const val RETENTION_ASSET = "harness/retention.js"
    private const val RETENTION_SCRIPT = "retention.js"
    private const val MEMORY_LABEL = "memory"
    private const val YNISON_LABEL = "ynison"
    internal val MUSIC_NAME_KEY = "\"${YnisonMcp.NAME}\""
    private const val YNISON_AUTH_HEADER = "\"Authorization\": \"Bearer {env:MCP_YNISON_TOKEN}\""

    /**
     * Лимит одного вызова музыкального MCP. Должен быть заведомо больше худшего случая
     *ensurePhone (запуск приложения плюс ожидание регистрации в Ynison), иначе инструмент
     * не успевает отдать результат и вместо него приходит ошибка транспорта.
     */
    private const val YNISON_MCP_TIMEOUT_MS = 180_000

    private fun remoteServerBlock(
        name: String,
        url: String,
        authHeader: String = AUTH_HEADER,
        timeoutMs: Int? = null,
    ): String =
        "\"$name\": {\n" +
            "      \"type\": \"remote\",\n" +
            "      \"url\": \"$url\",\n" +
            (timeoutMs?.let { "      \"timeout\": $it,\n" } ?: "") +
            "      \"headers\": {\n" +
            "        $authHeader\n" +
            "      }\n" +
            "    }"

    private fun mcpMemoryBlock(): String = remoteServerBlock(MemoryMcp.MEMORY_NAME, MEMORY_MCP_URL)

    /** Управление телефоном: установка, запуск, медиа. Отдельный сервер ради честного UI. */
    private fun mcpMobileBlock(): String = remoteServerBlock(MemoryMcp.TOOLS_NAME, MOBILE_MCP_URL)

    /**
     * Яндекс.Музыка. Свой bearer — [YNISON_AUTH_HEADER], а не токен памяти: у music.js
     * отдельная проверка `Authorization`, и общий токен означал бы, что любой из серверов
     * открывается заголовком, предназначенным другому.
     */

    /**
     * Яндекс.Музыка с явным таймаутом.
     *
     * Измерено 30.09.2026: запуск приложения с нуля занимает у Яндекс.Музыки дольше, чем
     * ждал ensurePhone, и вызов упирался в клиентский MCP-таймаут ("Request timed out"),
     * не успев ни записать очередь, ни вернуть внятную ошибку. Остальным серверам хватает
     * дефолта, потому что они отвечают сразу; здесь ожидание - часть работы инструмента,
     * поэтому лимит должен покрывать холодный старт.
     */
    private fun mcpMusicBlock(): String = remoteServerBlock(YnisonMcp.NAME, YNISON_MCP_URL, YNISON_AUTH_HEADER, YNISON_MCP_TIMEOUT_MS)

    /** Содержимое объекта "mcp" — записи памяти и телефона, разделённые запятой. */
    private fun mcpEntries(): String = "${mcpMemoryBlock()},\n    ${mcpMobileBlock()}"

    /** То же плюс третья запись: музыка подключена. */
    private fun mcpEntriesWithMusic(): String = "${mcpEntries()},\n    ${mcpMusicBlock()}"

    private fun legacyMemoryBlock(): String =
        "\"${MemoryMcp.MEMORY_NAME}\": {\n" +
            "      \"type\": \"remote\",\n" +
            "      \"url\": \"$MEMORY_MCP_URL\"\n" +
            "    }"

    private fun mcpObject(entries: String): String = "\"mcp\": {\n    $entries\n  }"

    private fun mcpBlock(): String = mcpObject(mcpEntries())

    /**
     * Управляемая форма в любой из двух разновидностей: с музыкой и без неё.
     *
     * Проверка «уже актуальная форма» обязана узнавать ОБЕ, а раньше искала только
     * [mcpBlock] — без music, — хотя music дописывается следующим шагом, [syncMusicEntry].
     * В итоге лестница перестала признавать собственный продукт: стоило music попасть в
     * mcp-объект, как `contains(mcpBlock())` переставал срабатывать, и каждый запуск
     * падал в ветку «не управляемый» с false.
     *
     * На устройстве это стоило четырёх дней тишины: конфиг не переписывался с 01.10,
     * эталон для ПК-агента протух, новые permission-ключи не добавлялись — при живых
     * MCP и рабочих симлинках, то есть внешне совершенно здоровый сервер.
     *
     * 25.10.2026 повторилось то же самое с третьей стороны: у конфига с подключённым
     * moltbook после телефона стоит его запись, а не `}`, поэтому ни [mcpBlock], ни
     * [mcpBlockWithMusic] в тексте не находились. Поэтому узнаём не весь объект, а две
     * обязательные записи внутри него: у чужой секции их побайтово не будет.
     */
    internal fun hasManagedMcpBlock(text: String): Boolean = text.contains(mcpMemoryBlock()) && text.contains(mcpMobileBlock())

    /** Управляемая форма с музыкой. Только для записи, когда Яндекс подключён. */
    internal fun mcpBlockWithMusic(): String = mcpObject(mcpEntriesWithMusic())

    /**
     * Управляемая форма без музыки. Внутренняя, но видна тесту: тест сверяет результат
     * планировщика с той строкой, которую пишет приложение, - иначе проверка была бы
     * самооценкой, где эталон и результат расходятся незаметно.
     */
    internal fun mcpBlockForTest(): String = mcpBlock()

    /**
     * Вставка managed-блока после `$schema`. Видна тесту, потому что дефект здесь
     * строковый: разделитель между полями решается одной запятой, и на устройстве
     * ошибка выглядит как «сервер больше не поднимается», без единой подсказки.
     */
    internal fun insertAfterSchemaForTest(
        text: String,
        managedBlock: String,
    ): String? = insertAfterSchema(text, managedBlock)

    /**
     * Пара записей «память + телефон» без обрамляющего объекта.
     *
     * Видна снаружи объекта по той же причине, что и [mcpBlockForTest]: [planMusicEntry]
     * объявлен расширением и правки делает на уровне записей, а не всего блока, иначе
     * запись moltbook, стоящая после телефона, отменяла бы регистрацию music.
     */
    internal fun mcpEntriesForTest(): String = mcpEntries()

    /** То же вместе с записью music. */
    internal fun mcpEntriesWithMusicForTest(): String = mcpEntriesWithMusic()

    /** Прежняя управляемая форма: один сервер memory. Только для миграции, не для записи. */
    private fun singleServerMcpObject(): String = mcpObject(mcpMemoryBlock())

    /** Промежуточная форма: второй сервер назывался "mobile". Только для миграции. */
    private fun mobileAliasMcpObject(): String = mcpObject("${mcpMemoryBlock()},\n    ${mobileAliasBlock()}")

    private fun mobileAliasBlock(): String = remoteServerBlock("mobile", MOBILE_MCP_URL)

    private fun managedConfigBlock(): String =
        buildString {
            append(mcpBlock())
            append(
                ",\n  \"permission\": {\n" +
                    "    \"mobile_launch_app\": \"ask\"\n" +
                    "  }",
            )
        }

    /**
     * То же для формы с музыкой. Без неё дозапись permission-ключей в конфиг с music
     * сносила бы саму music: [mcpBlock] в таком файле не ищется, и подстановка
     * без-музыкальной формы его бы не нашла — а нашлась бы замена, убирающая регистрацию.
     */
    private fun managedConfigBlockWithMusic(): String =
        buildString {
            append(mcpBlockWithMusic())
            append(
                ",\n  \"permission\": {\n" +
                    "    \"mobile_launch_app\": \"ask\"\n" +
                    "  }",
            )
        }

    /**
     * Секция mcp уже обновлена до двух серверов — осталось её записать.
     *
     * Ловушка, из-за которой миграция молча терялась: если permission-ключ
     * уже есть, [ensureManagedLaunchPermission] выходит с true по первой ветке и
     * НИЧЕГО не пишет, а её аргумент — новый текст — уходит в никуда. Поэтому при
     * полном наборе разрешений пишем файл сами, иначе delegate дозапишет ключи.
     */
    private fun upgradeMcpSection(
        file: File,
        upgraded: String,
    ): Boolean =
        when {
            upgraded.contains(LAUNCH_PERMISSION_KEY) -> writeMemoryConfigText(file, upgraded)
            // Пользовательская секция permission без нашего ключа: чужие разрешения
            // не трогаем, но и обновление mcp не выбрасываем. Раньше здесь вызывался
            // [ensureManagedLaunchPermission], упирался в ветку «user-managed» и
            // возвращал true, ничего не записав: двухсерверный mcp терялся на каждом
            // старте, и телефонный сервер не регистрировался никогда.
            file.readTextOrEmpty().contains("\"permission\"") -> writeMemoryConfigText(file, upgraded)
            else -> ensureManagedLaunchPermission(file, upgraded)
        }

    private fun ensureManagedLaunchPermission(
        file: File,
        text: String,
    ): Boolean =
        when {
            text.contains(LAUNCH_PERMISSION_KEY) -> true
            text.contains("\"permission\"") -> {
                android.util.Log.w(
                    "OpencodeRuntime",
                    "Existing permission config is user-managed; leaving mobile permissions unchanged",
                )
                true
            }
            !hasManagedMcpBlock(text) -> {
                android.util.Log.w(
                    "OpencodeRuntime",
                    "Managed memory MCP block has unexpected formatting; cannot add launch permission safely",
                )
                false
            }
            // Музыку сохраняем: замена формы без music убрала бы её регистрацию.
            text.contains(mcpBlockWithMusic()) ->
                writeMemoryConfigText(file, text.replace(mcpBlockWithMusic(), managedConfigBlockWithMusic()))
            else -> writeMemoryConfigText(file, text.replace(mcpBlock(), managedConfigBlock()))
        }

    /** Записывает managed-секцию mcp.memory и permission в opencode.jsonc атомарно. */
    private fun writeManagedConfig(
        file: File,
        managedBlock: String,
    ): Boolean {
        // Экранированный $schema (в строке Kotlin $ начинал бы интерполяцию).
        val schemaMarker = "\$schema"
        val text = if (file.exists()) file.readText() else ""
        val updated: String? =
            if (text.contains(schemaMarker)) {
                insertAfterSchema(text, managedBlock)
            } else if (text.isBlank()) {
                "{\n  \"\$schema\": \"https://opencode.ai/config.json\",\n  $managedBlock\n}"
            } else {
                // Произвольный jsonc без $schema: вставляем перед последней "}".
                val idx = text.lastIndexOf('}')
                if (idx <= 0) {
                    null
                } else {
                    text.substring(0, idx) + ",\n  " + managedBlock + "\n" + text.substring(idx)
                }
            }
        if (updated == null) return false
        return writeMemoryConfigText(file, updated)
    }

    /**
     * Вставляет managed-блок сразу после поля `$schema`.
     *
     * Запятая после блока ставится по тому, что идёт дальше: перед закрывающей
     * скобкой она была бы ошибкой, а перед следующим полем её отсутствие — тоже.
     * Раньше запятая ставилась всегда, и конфиг, где `$schema` — последнее поле,
     * превращался в невалидный JSON (схема без запятой, блок без разделителя).
     * [looksLikeManagedConfig] такую порчу не видит — она считает только скобки, —
     * поэтому битый файл записывался, и сервер уже не поднимался.
     */
    private fun insertAfterSchema(
        text: String,
        managedBlock: String,
    ): String? {
        val match = Regex("\"\\\$schema\"\\s*:\\s*\"[^\"]*\"").find(text) ?: return null
        val afterField = text.substring(match.range.last + 1)
        // Свою запятую у схемы выбрасываем: ниже она ставится нами, ровно одна.
        val commaAt = afterField.indexOfFirst { !it.isWhitespace() }
        val hadComma = commaAt >= 0 && afterField[commaAt] == ','
        val rest = if (hadComma) afterField.removeRange(commaAt, commaAt + 1) else afterField
        val comma = if (rest.trimStart().startsWith("}")) "" else ","
        return text.substring(0, match.range.last + 1) + ",\n  " + managedBlock + comma + rest
    }

    /**
     * Атомарно записывает подготовленный конфиг памяти и проверяет результат.
     *
     * Сначала проверяем текст, потом после записи перечитываем файл: битый opencode.jsonc
     * не даёт подняться серверу, и приложение навсегда залипает на «Starting Open Code server».
     * Поэтому любой сомнительный текст не пишем вовсе, а расхождение после записи откатываем.
     */
    private fun writeMemoryConfigText(
        file: File,
        text: String,
    ): Boolean {
        if (!looksLikeManagedConfig(text)) {
            android.util.Log.e(
                "OpencodeRuntime",
                "Refusing to write a config that would not parse: ${file.absolutePath}",
            )
            return false
        }
        val previous = if (file.exists()) runCatching { file.readText() }.getOrNull() else null
        file.parentFile?.mkdirs()
        // Имя tmp уникальное: конфиг пишут и сервис, и UI (applyModelRouting). Общий
        // opencode.jsonc.tmp означал, что второй писатель мог подменить файл первому
        // между writeText и renameTo — и чья-то правка терялась молча.
        var tmp: File? = null
        return runCatching {
            val target = File.createTempFile("opencode.jsonc", ".tmp", file.parentFile)
            tmp = target
            target.writeText(text)
            check(target.renameTo(file)) { "tmp->rename failed" }
            check(file.readText() == text) { "config verification after write failed" }
            android.util.Log.i("OpencodeRuntime", "ensureMcpConfig: memory MCP config updated in ${file.absolutePath}")
            true
        }.onFailure { error ->
            tmp?.delete()
            previous?.let { runCatching { file.writeText(it) } }
            android.util.Log.e("OpencodeRuntime", "ensureMcpConfig: atomic config update failed: ${error.message}")
        }.getOrDefault(false)
    }

    /**
     * Дешёвая проверка перед записью. Ловит ровно тот класс порчи, который уже brick-ил
     * приложение: несработавшая интерполяция Kotlin попадала в JSON как
     * `kotlin.text.MatcherMatchResult@...value`, и opencode отказывался стартовать.
     *
     * Запись music проверяется отдельно и только когда она есть: без подключённого
     * Яндекса её в тексте нет и быть не должно, и требовать её всегда означало бы
     * запретить запись валидного двухсерверного конфига.
     */
    private fun looksLikeManagedConfig(text: String): Boolean {
        val leakedKotlinObject =
            text.contains("kotlin.") ||
                text.contains("MatcherMatchResult") ||
                text.contains("\${")
        val keysIntact =
            text.contains(LAUNCH_PERMISSION_KEY) &&
                text.contains("\"${MemoryMcp.MEMORY_NAME}\"") &&
                text.contains("\"${MemoryMcp.TOOLS_NAME}\"")
        val musicIntact = !text.contains(MUSIC_NAME_KEY) || text.contains(YNISON_MCP_URL)
        return !leakedKotlinObject &&
            keysIntact &&
            musicIntact &&
            text.count { it == '{' } == text.count { it == '}' }
    }

    private fun File.readTextOrNull(): String? = if (exists()) runCatching { readText() }.getOrNull() else null

    private fun File.readTextOrEmpty(): String = readTextOrNull().orEmpty()
}

/**
 * Всё, чем один локальный MCP-сервер отличается от другого.
 *
 * Обвязка запуска (musl-загрузчик, LD_LIBRARY_PATH, HOME/XDG) одинакова для обоих, и
 * различаться могут только эти четыре вещи. Собраны в один объект, чтобы сигнатура
 * [OpencodeRuntime] не росла вместе с числом серверов, а местоимение «launch» читалось
 * как «запуск», а не как седьмой позиционный параметр.
 */
internal data class McpLaunch(
    val script: File,
    val port: Int,
    val label: String,
    val env: Map<String, String>,
)

/**
 * Три секрета, с которыми живёт процесс ynison.js.
 *
 * Сложены в один объект намеренно: держать их тремя соседними `String` в сигнатуре -
 * значит однажды перепутать порядок и годами отлаживать 403 на локальном порту.
 * [accessToken] - токен Яндекса, [mcpToken] - локальный bearer, [deviceId] - тот же, что
 * в device-flow.
 */
internal data class YnisonCredentials(
    val accessToken: String,
    val mcpToken: String,
    val deviceId: String,
)

/**
 * Системно зарезолвленные IP для ynison.js — подстраховка, а не единственный путь.
 * Оба могут быть null при сбое резолва — скрипт тогда работает по доменам (и резолвит сам).
 */
internal data class YnisonHosts(
    val apiIp: String?,
    val ynisonIp: String?,
)

/**
 * Модель, которую opencode рекомендует первой и которую провайдер `opencode`
 * отдаёт бесплатно и без ключа (`https://opencode.ai/inference/v1/models`,
 * apiKey "public"). Формат — `провайдер/идентификатор`.
 */
internal const val DEFAULT_FREE_MODEL = "opencode/big-pickle"

/** Ключ верхнего уровня, которым opencode задаёт модель по умолчанию. */
private const val MODEL_KEY = "\"model\""

/** Что делать с записью `model` в управляемом opencode.jsonc. */
internal sealed interface ModelConfigPlan {
    /** Модель уже задана или ставить нечего — файл не трогаем. */
    data object Keep : ModelConfigPlan

    /** Дописать запись `model` в начало объекта. */
    data class Write(
        val text: String,
    ) : ModelConfigPlan

    /** Формат не узнан: оставляем как есть, opencode выберет модель сам. */
    data object Refuse : ModelConfigPlan
}

/**
 * Решает, что делать с записью `model`, ничего не записывая.
 *
 * Вынесено наружу по той же причине, что и [OpencodeRuntime.planMusicEntry]: правка
 * управляемого конфига — единственное место, где легко тихо испортить работающую
 * установку, и проверять его должен юнит-тест, а не устройство.
 *
 * Вставка идёт сразу после открывающей `{`, а не после строки `$schema` как в
 * [OpencodeRuntime.writeManagedConfig]: у `$schema` есть запятая, который regex там
 * съедает по желанию, из-за чего на некоторых конфигах получалось `,,`. Запятая
 * после вставки ставится только когда дальше действительно есть ещё поля.
 */
internal fun OpencodeRuntime.planModelEntry(text: String): ModelConfigPlan =
    when {
        // Только ключ в корне: `model` внутри агента — это про того агента,
        // а не про модель по умолчанию, и такой конфиг надо всё равно закрепить.
        hasTopLevelKey(text, "model") -> ModelConfigPlan.Keep
        // Корневая скобка ищется с пропуском строк и комментариев: первая `{`
        // вообще может оказаться внутри `/* … {model} … */`, и тогда запись
        // уехала бы в комментарий, а проверка looksLikeManagedConfig такой файл
        // пропустила бы — сервер на нём не поднимется.
        rootObjectStart(text) < 0 -> ModelConfigPlan.Refuse
        else -> {
            val entry = "$MODEL_KEY: \"$DEFAULT_FREE_MODEL\""
            val brace = rootObjectStart(text)
            val rest = text.substring(brace + 1)
            // Пустой объект `{}` — единственный случай, когда запятая после записи не нужна.
            // «Пустой» определяется по первому ЗНАЧАЩЕМУ символу: для `{ // TODO }`
            // первым непробельным идёт `/`, и запись получила бы висячую запятую.
            val closing = nextSignificant(text, brace + 1)
            val tail = if (closing < text.length && text[closing] == '}') "\n  $entry\n" else "\n  $entry,"
            ModelConfigPlan.Write(text.substring(0, brace + 1) + tail + rest)
        }
    }

internal sealed interface MusicConfigPlan {
    /** Уже в нужном состоянии — файл не трогаем. */
    data object Keep : MusicConfigPlan

    /** Перезаписать конфиг указанным текстом. */
    data class Write(
        val text: String,
    ) : MusicConfigPlan

    /** Формат не узнан: оставляем как есть, чтобы не портить чужой конфиг. */
    data object Refuse : MusicConfigPlan
}

/**
 * Решает, что делать с записью «music», ничего не записывая.
 *
 * Вынесено из [OpencodeRuntime] отдельной функцией по одной причине: это единственное
 * место в миграции конфига, где легко тихо испортить работающую установку, и проверять
 * его нужно юнит-тестом, а не на устройстве. Внутри объекта проверка потребовала бы
 * Context, файловой системы и Robolectric, которого в проекте нет, - то есть не
 * проверялась бы вообще никогда.
 *
 * Логика ровно одна и в четыре строки, но каждая опасна: добавление music внутрь
 * mcpEntries сделало бы [OpencodeRuntime.mcpBlock] зависимым от наличия токена, и лестница
 * миграций перестала бы отличать старую форму от новой - любой существующий конфиг упал бы
 * в ветку «не управляемый» и тихо потерял бы регистрацию памяти. Поэтому music добавляется
 * и убирается заменой точного блока, а не пересборкой секции.
 */
internal fun OpencodeRuntime.planMusicEntry(
    text: String,
    ynisonToken: String?,
): MusicConfigPlan =
    when {
        // Bearer в конфиге - ссылка {env:MCP_YNISON_TOKEN}, а не значение, поэтому смена
        // токена на диске конфиг не затрагивает: «есть токен и есть запись» = не делать
        // ничего, даже если сам токен сменился.
        ynisonToken != null && text.contains(MUSIC_NAME_KEY) -> MusicConfigPlan.Keep
        ynisonToken == null && !text.contains(MUSIC_NAME_KEY) -> MusicConfigPlan.Keep
        // Меняем не весь объект mcp, а только пару «память + телефон»: за ней в секции
        // может стоять moltbook, и тогда закрывающей скобки объекта сразу после телефона
        // нет. Пока здесь сравнивался целый [mcpBlock], любой конфиг с подключённым
        // moltbook уходил в Refuse, и music не регистрировалась никогда - при живом
        // токене, живом порте 4200 и пустом списке MCP в интерфейсе.
        text.contains(mcpEntriesWithMusicForTest()) ->
            MusicConfigPlan.Write(text.replace(mcpEntriesWithMusicForTest(), mcpEntriesForTest()))
        text.contains(mcpEntriesForTest()) ->
            MusicConfigPlan.Write(text.replace(mcpEntriesForTest(), mcpEntriesWithMusicForTest()))
        else -> MusicConfigPlan.Refuse
    }
