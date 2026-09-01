package org.opencode.mobile.server

import android.content.Context
import org.opencode.mobile.OpencodeApp
import java.io.File

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
            if (dest.exists() && dest.length() > 0) continue
            val src = File(nativeDir, srcName)
            if (!src.exists()) {
                if (required) throw IllegalStateException("musl lib $srcName missing in nativeLibraryDir")
                continue
            }
            src.copyTo(dest, overwrite = true)
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
        // opencode.ai/zen/go/v1 (Cloudflare отдаёт AAAA первыми, на устройстве нет
        // IPv6-маршрута) — «AI_APICallError: Cannot connect to API». Прокси
        // резолвит строго по IPv4; TLS остаётся end-to-end.
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
        // Пустые/безопасные значения чтобы opencode не ныл
        pb.environment()["PATH"] = (pb.environment()["PATH"] ?: "") + ":" + nativeDir

        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))

        return runCatching { pb.start() }.onFailure { e ->
            android.util.Log.e("OpencodeRuntime", "failed to start opencode: ${e.message}")
        }.getOrNull()
    }

    private fun nativeLibraryDir(context: Context): String {
        return context.applicationInfo.nativeLibraryDir
    }
}
