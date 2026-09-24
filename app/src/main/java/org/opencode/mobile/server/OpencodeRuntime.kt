package org.opencode.mobile.server

import android.content.Context
import org.opencode.mobile.OpencodeApp
import java.io.File
import java.io.FileOutputStream

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
object OpencodeRuntime {

    private const val BIN_NAME = "libopencode.so"        // в nativeLibraryDir
    private const val LOADER_NAME = "libldmusl.so"       // в nativeLibraryDir
    private const val BUN_NAME = "libbun-musl.so"        // встроенный musl-Bun в nativeLibraryDir
    const val MEMORY_PORT = 4199                          // TCP/Streamable-порт локальной памяти

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
    fun startServe(
        context: Context,
        additionalArgs: List<String> = emptyList(),
        logFile: File,
        workDir: File? = null,
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
        cmd.add(loader)                 // interpreter-first: ld-musl ./opencode ...
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

        // КРИТИЧНО для локальных MCP (stdio transport): НЕЛЬЗЯ редиректить stdout serve
        // в файл. opencode запускает дочерние MCP процессы (например встроенный bun через
        // libldmusl) и ждёт от них JSON-RPC по pipe. Если serve сам редиректит stdout в файл,
        // все дочерние наследуют этот файл вместо pipe, и opencode не читает ответ MCP ->
        // "Operation timed out after 30000ms". Поэтому: stdout/stderr serve -> отдельные pipes,
        // а их содержимое мы в фоне дублируем в logFile для отладки (redirect через pipe не
        // мешает opencode создавать нормальные stdio pipes у своих MCP детей).
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE)

        return runCatching {
            val proc = pb.start()
            if (logFile.parentFile?.exists() != true) logFile.parentFile?.mkdirs()
            val out = FileOutputStream(logFile, true)
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
            }.apply { isDaemon = true; name = "opencode-log" }.start()
            proc
        }.onFailure { e ->
            android.util.Log.e("OpencodeRuntime", "failed to start opencode: ${e.message}")
        }.getOrNull()
    }

    private fun nativeLibraryDir(context: Context): String {
        return context.applicationInfo.nativeLibraryDir
    }

    /**
     * Копирует memory.js (Streamable HTTP MCP-сервер локальной памяти) из встроенных
     * assets в filesDir/mem/memory.js, откуда его может запустить встроенный musl-Bun.
     * Возвращает путь к скрипту (или null при ошибке).
     */
    fun ensureMemoryScript(context: Context): File? {
        val dir = File(context.filesDir, "mem").apply { mkdirs() }
        val dest = File(dir, "memory.js")
        return try {
            // assets - источник истины: всегда сверяем, перезаписываем если отличается
            // (install -r сохраняет app data/firstDir, старая копия оставалась и тормозила фиксы).
            val source = context.assets.open("mcp/memory.js").use { input ->
                input.readBytes()
            }
            val changed = !dest.exists() ||
                    dest.length() != source.size.toLong() ||
                    !dest.readBytes().contentEquals(source)
            if (changed) {
                // Атомарная замена: tmp + rename. Обрезанный writeBytes (kill посреди
                // записи) оставил бы битый memory.js по целевому имени навсегда —
                // dest.size()==source.size() прошёл бы только при совпадении длины.
                val tmp = File(dir, "memory.js.tmp")
                tmp.writeBytes(source)
                if (!tmp.renameTo(dest)) {
                    android.util.Log.e("OpencodeRuntime", "ensureMemoryScript: tmp->dest rename failed")
                    tmp.delete()
                    return null
                }
                android.util.Log.i("OpencodeRuntime", "ensureMemoryScript: wrote ${source.size}B to ${dest.absolutePath}")
            }
            dest
        } catch (e: Exception) {
            android.util.Log.e("OpencodeRuntime", "ensureMemoryScript failed: ${e.message}")
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
    fun startMemoryServer(context: Context, logFile: File, workDir: File? = null): Process? {
        if (!isAssembled(context)) return null
        val script = ensureMemoryScript(context) ?: return null
        val nativeDir = nativeLibraryDir(context)
        val loader = File(nativeDir, LOADER_NAME).absolutePath
        val bun = File(nativeDir, BUN_NAME).absolutePath

        val cmd = ArrayList<String>()
        cmd.add(loader)                 // ld-musl загрузчик первым
        cmd.add(bun)                    // сам runtime
        cmd.add(script.absolutePath)    // наш MCP-скрипт

        android.util.Log.i("OpencodeRuntime", "starting memory http server: $cmd (port $MEMORY_PORT)")

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
            // умрёт на старте (не найдёт libc) и молча оставит память нерабочей.
            android.util.Log.e("OpencodeRuntime", "memory: ensureMuslLibs failed: ${e.message}")
            return null
        }
        pb.environment()["LD_LIBRARY_PATH"] = "$muslDir:$nativeDir"
        pb.environment()["NO_COLOR"] = "1"
        pb.environment()["PATH"] = (pb.environment()["PATH"] ?: "") + ":" + nativeDir
        pb.environment()["MCP_NATIVE_DIR"] = nativeDir
        pb.environment()["MCP_MEMORY_DIR"] = File(cfg.opencodeHome, ".memory").absolutePath
        pb.environment()["MCP_TCP_PORT"] = MEMORY_PORT.toString()
        pb.environment()["NO_PROXY"] = "127.0.0.1,localhost"

        // stdout/stderr memory -> отдельный лог (stdio не нужен: транспорт TCP).
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))

        return runCatching {
            pb.start()
        }.onFailure { e ->
            android.util.Log.e("OpencodeRuntime", "failed to start memory server: ${e.message}")
        }.getOrNull()
    }

    /**
     * Гарантирует, что opencode serve знает про локальную память MCP как
     * remote-сервер (http://127.0.0.1:MEMORY_PORT/mcp). Без регистрации в
     * конфиге serve память слушает порт, но индикатор чата показывает «0 MCP»,
     * а модель не получает её инструменты.
     *
     * Конфиг serve: $XDG_CONFIG_HOME/opencode/opencode.jsonc (opencode читает
     * Global.Path.config = XDG_CONFIG_HOME + "/opencode", см. opencode-src
     * packages/opencode/src/config/config.ts:379-381). Формат MCP-секции:
     * "mcp": { "<имя>": { "type": "remote", "url": "..." } }.
     *
     * Идемпотентно: если в конфиге уже есть секция "memory" — не трогаем
     * (сохраняем ручные правки юзера). Пишем атомарно (tmp + rename), чтобы
     * обрыв записи не оставил битый jsonc (serve бы упал на парсинге).
     * @return true когда конфиг гарантированно содержит memory-MCP.
     */
    fun ensureMcpConfig(context: Context): Boolean {
        return try {
            val cfg = OpencodeApp.ServerConfig
            val dir = File(cfg.opencodeConfig, "opencode")
            val file = File(dir, "opencode.jsonc")
            val mcpBlock =
                "\"mcp\": {\n" +
                    "    \"memory\": {\n" +
                    "      \"type\": \"remote\",\n" +
                    "      \"url\": \"http://127.0.0.1:$MEMORY_PORT/mcp\"\n" +
                    "    }\n" +
                    "  }"
            // Экранированный $schema (в строке Kotlin $ начинал бы интерполяцию).
            val schemaMarker = "\$schema"

            // Идемпотентность: уже есть секция memory (в т.ч. с другим портом —
            // не переписываем поверх осознанной ручной настройки).
            if (file.exists() && file.readText().contains("\"memory\"")) return true

            val text = if (file.exists()) file.readText() else ""
            val updated: String =
                if (text.contains(schemaMarker)) {
                    // Вставляем после строки $schema (opencode всегда создаёт её первым полем).
                    // $0 в replacement — вся matched строка (схема + запятая, если была).
                    text.replaceFirst(
                        Regex("(\"\\\$schema\"\\s*:\\s*\"[^\"]*\"\\s*,?)"),
                        "$0\n  $mcpBlock,",
                    )
                } else if (text.isBlank()) {
                    "{\n  \"\$schema\": \"https://opencode.ai/config.json\",\n  $mcpBlock\n}"
                } else {
                    // Произвольный jsonc без $schema: вставляем перед последней "}".
                    val idx = text.lastIndexOf('}')
                    if (idx <= 0) return false
                    text.substring(0, idx) + ",\n  " + mcpBlock + "\n" + text.substring(idx)
                }

            dir.mkdirs()
            val tmp = File(dir, "opencode.jsonc.tmp")
            tmp.writeText(updated)
            if (!tmp.renameTo(file)) {
                android.util.Log.e("OpencodeRuntime", "ensureMcpConfig: tmp->rename failed, retry direct")
                file.writeText(updated)
            }
            android.util.Log.i("OpencodeRuntime", "ensureMcpConfig: memory MCP registered in ${file.absolutePath}")
            true
        } catch (e: Exception) {
            android.util.Log.e("OpencodeRuntime", "ensureMcpConfig failed: ${e.message}")
            false
        }
    }
}
