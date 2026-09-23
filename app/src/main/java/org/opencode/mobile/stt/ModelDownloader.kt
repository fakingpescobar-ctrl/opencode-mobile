package org.opencode.mobile.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Скачивание whisper-моделей на устройство по требованию.
 *
 * APK остаётся маленьким: и base, и turbo НЕ кладём в assets, а качаем в
 * filesDir/models (по выбору в настройках STT). Загруженный файл живёт между
 * запусками — повторно качаем только если его нет.
 *
 * - base  = ggerganov/whisper.cpp ggml-base.bin      (141 047 000 б ≈ 141 МБ)
 * - turbo = ggerganov/whisper.cpp ggml-large-v3-turbo-q5_0.bin (574 МБ)
 */
object ModelDownloader {
    private const val TAG = "ModelDL"

    private const val HF_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
    private const val URL_TURBO = "$HF_BASE/ggml-large-v3-turbo-q5_0.bin"
    private const val URL_BASE = "$HF_BASE/ggml-base.bin"

    /** Имена файлов моделей на диске устройства. */
    const val TURBO_FILE = "ggml-large-v3-turbo-q5_0.bin"
    const val BASE_FILE = "ggml-base.bin"

    /**
     * Fallback-пороги «не оборван», когда sidecar с точным размером ещё нет
     * (см. [expectedSizeFile]): чуть НИЖЕ реальных размеров (~134.5MiB / ~547MiB),
     * т.к. предел выше реального размера навсегда заблокировал бы *Ready(). Точные
     * сверки после скачивания идут по sidecar-размеру (см. downloadTo).
     */
    private const val MIN_BASE = 130L * 1024 * 1024
    private const val MIN_TURBO = 540L * 1024 * 1024

    /**
     * Минимально необходимое свободное место на томе filesDir ПЕРЕД началом
     * скачивания (размер модели + запас на хвост-resume и sidecar-ы). Проверку
     * делаем до старта, а не в пути: обрыв посреди 574 МБ из-за места — худший
     * сценарий для пользователя (модель «наполовину скачана», USB-освобождения).
     * usableSpace ≤ 0 (ФС без лимита) — проверку пропускаем.
     */
    private const val MIN_FREE_BASE = 200L * 1024 * 1024   // 141 МБ base + запас
    private const val MIN_FREE_TURBO = 700L * 1024 * 1024  // 574 МБ turbo + запас

    /** Папка каталога моделей внутри filesDir: <filesDir>/models/. */
    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    /**
     * Сериализация скачиваний: downloadBase/downloadTurbo могут прийти параллельно
     * (UI + сервис); общий .part-файл и общий выходной файл — общие ресурсы.
     */
    private val downloadMutex = Mutex()

    // ---- SHA-256 манифест ----

    /**
     * Результат проверки файла модели против локального манифеста (SHA-256 снапшот).
     * - [VALID] — хеш совпал (или файл уже проверен в этом процессе при тех же размер+mtime);
     * - [CORRUPT] — файл на диске повреждён/подменён (SHA-256 не сошёлся);
     * - [NO_MANIFEST] — модель скачана до введения манифеста: проверять нечем,
     *   загрузка разрешена (ровно прежний путь trust: ETag + строгий размер).
     */
    enum class ModelIntegrity { VALID, CORRUPT, NO_MANIFEST }

    /** Sidecar с SHA-256 снапшотом: <имя модели>.sha256 рядом с моделью. */
    private fun manifestFile(file: File): File = File(file.parentFile, "${file.name}.sha256")

    /** Кэш «этот файл (size+mtime) уже проверен в этом процессе» — дешёвая повторная сверка. */
    private val integrityCache = ConcurrentHashMap<String, Pair<Long, Long>>()

    /** SHA-256 файла (hex, lowercase); null при ошибке чтения. */
    fun sha256Of(file: File): String? = try {
        MessageDigest.getInstance("SHA-256").run {
            file.inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    update(buf, 0, n)
                }
            }
            toHex(digest())
        }
    } catch (e: Exception) {
        Log.w(TAG, "sha256 ${file.name}: ${e.message}")
        null
    }

    /** 32 байта → 64 hex-символа (без per-byte String.format — хешируется до 574МБ). */
    private fun toHex(bytes: ByteArray): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(hex[(b.toInt() shr 4) and 0xf])
            sb.append(hex[b.toInt() and 0xf])
        }
        return sb.toString()
    }

    /** Манифест валиден только как 64 hex-символа; битый/пустой/оборванный — NO_MANIFEST. */
    private val HEX_64 = Regex("[0-9a-fA-F]{64}")

    /**
     * Пишет манифест модели. digest можно передать готовым (считанный по tmp до
     * rename — содержимое то же), иначе считается заново.
     */
    private fun writeManifest(file: File, digest: String? = null) {
        val value = digest ?: sha256Of(file) ?: run {
            // digest не передан, а пересчёт не удался — молча выходить нельзя
            Log.w(TAG, "manifest ${file.name}: SHA-256 не посчитался — манифест не пишу")
            return
        }
        // кэш integrity устаревает при любой перезаписи модели/манифеста
        integrityCache.remove(file.absolutePath)
        try {
            // Атомарно (tmp+rename): обрыв записи не оставит битый манифест — на
            // месте останется прежний валидный снапшот, а не мусор NO_MANIFEST-болванка.
            val tmp = File(file.parentFile, "${file.name}.sha256.tmp")
            tmp.delete() // сирота от прошлого краха — не должен мешать перезаписи
            tmp.writeText(value)
            if (!tmp.renameTo(manifestFile(file))) {
                tmp.delete()
                // Старый манифест мог быть от ПРЕЖНЕЙ ревизии файла — оставить его
                // = ложный CORRUPT на новой ревизии. Снимаем: без манифеста модель
                // останется NO_MANIFEST (не блокирует), честнее ложной блокировки.
                manifestFile(file).delete()
                Log.w(TAG, "manifest ${file.name}: rename не удался — снапшот снят")
            }
        } catch (e: Exception) {
            // при сбое записи тоже снимаем старый снапшот (иначе ложный CORRUPT
            // для новой ревизии файла — блокировка без вины модели)
            manifestFile(file).delete()
            Log.w(TAG, "manifest ${file.name}: ${e.message}")
        }
    }

    /**
     * Проверяет файл модели против манифеста. Тяжёлую сверку (чтение всего файла)
     * выполняет один раз в процессе; повторные вызовы для того же size+mtime — дёшево.
     *
     * Инвариант: не вызывается параллельно с downloadTo (downloadMutex сериализует
     * скачивания; сервис дёргает сверку до/после — во время скачивания dest ещё
     * не существует или нетронут, rename атомарен на одном томе).
     */
    fun checkIntegrity(file: File): ModelIntegrity {
        // файла-модели нет/пуст — это не повреждение, а отсутствие (манифест-сирота)
        if (!file.exists() || file.length() == 0L) return ModelIntegrity.NO_MANIFEST
        val manifest = manifestFile(file)
        val expected = try {
            manifest.readText().trim()
        } catch (e: java.io.FileNotFoundException) {
            null // манифеста нет — штатно (модель скачана до введения манифеста)
        } catch (e: Exception) {
            // манифест есть, но не читается (права/IO) — диагностируем, но не блокируем
            Log.w(TAG, "manifest ${file.name} не читается: ${e.message}")
            null
        }
        // нет манифеста или он битый (оборвался writeText) — проверить нечем:
        // не блокируем загрузку (прежний путь доверия ETag+размер), но и не VALID
        if (expected == null || !HEX_64.matches(expected)) {
            return ModelIntegrity.NO_MANIFEST
        }
        val stamp = file.length() to file.lastModified()
        integrityCache[file.absolutePath]?.let { if (it == stamp) return ModelIntegrity.VALID }
        val digest = sha256Of(file)
            ?: return ModelIntegrity.CORRUPT // файл не читается — деградировал, это повреждение
        val ok = digest == expected
        // TOCTOU-защита: кэшируем ЗЕЛЁНЫЙ статус только если файл не менялся
        // между снятием stamp и хэшированием (иначе в кэш ляжет штамп от старого
        // содержимого — ложный VALID до следующего изменения файла).
        val stampAfter = file.length() to file.lastModified()
        if (ok && stampAfter == stamp) integrityCache[file.absolutePath] = stamp
        return if (ok) ModelIntegrity.VALID else ModelIntegrity.CORRUPT
    }

    // ---- base ----

    fun baseFile(context: Context): File = File(modelsDir(context), BASE_FILE)

    /** Докачана ли base (файл существует и >130MB — не оборванный; точный
     * размер — из sidecar, если он есть). */
    fun baseReady(context: Context): Boolean {
        val f = baseFile(context)
        return f.exists() && f.length() >= readyThreshold(f, MIN_BASE)
    }

    /** Скачивает base-модель в filesDir/models. Блокирующий (suspend). */
    suspend fun downloadBase(
        context: Context,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File = downloadTo(context, URL_BASE, baseFile(context), MIN_BASE, "base", MIN_FREE_BASE, onProgress)

    // ---- turbo ----

    fun turboFile(context: Context): File = File(modelsDir(context), TURBO_FILE)

    /** Докачана ли turbo (файл существует и >540MB — не оборванный; точный
     * размер — из sidecar, если он есть). */
    fun turboReady(context: Context): Boolean {
        val f = turboFile(context)
        return f.exists() && f.length() >= readyThreshold(f, MIN_TURBO)
    }

    /** Скачивает turbo-модель в filesDir/models. Блокирующий (suspend). */
    suspend fun downloadTurbo(
        context: Context,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File = downloadTo(context, URL_TURBO, turboFile(context), MIN_TURBO, "turbo", MIN_FREE_TURBO, onProgress)

    /**
     * Общий загрузчик: качает url в dest (с resume и progress). При любом сбое
     * бросает Exception; временный файл чистится только при полном срыве.
     */
    private suspend fun downloadTo(
        context: Context,
        url: String,
        dest: File,
        minBytes: Long,
        label: String,
        requiredFree: Long,
        onProgress: (Long, Long) -> Unit
    ): File = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            if (dest.exists() && dest.length() > minBytes) {
                Log.d(TAG, "уже докачана: ${dest.absolutePath}")
                return@withContext dest
            }

            // Pre-check свободного места ДО старта (см. MIN_FREE_*): пользователь
            // увидит «Недостаточно места» сразу, а не через 400 МБ скачивания.
            ensureFreeSpace(context, label, requiredFree)

            val tmp = File(modelsDir(context), "${dest.name}.part")
            var partial = tmp.exists() && tmp.length() > 0
            // Один снапшот длины на весь запрос: и для Range, и для сверок ниже
            // (защита от TOCTOU, если файл урезали извне между чтениями).
            var startAt = if (partial) tmp.length() else 0L
            // ETag первого скачивания (sidecar рядом с .part) → If-Range при resume:
            // сервер вернёт 206 только если ревизия НЕ менялась; если файл перезалили —
            // 200 полного файла, и Guard 1 ниже пересоберёт с нуля. Это единственный
            // полный способ отличить «тот же файл» от «чужой ревизии той же длины».
            val etagFile = File(modelsDir(context), "${dest.name}.part.etag")
            // Точный ожидаемый размер (из Content-Length первого 200-ответа): после
            // скачивания сверяем done с ним ±0.5%. Без этого обрыв на 99% файла мог бы
            // пройти константный порог MIN_TURBO и «валидная» битая модель упала бы в whisper.
            val sizeFile = File(modelsDir(context), "${dest.name}.part.size")
            // .part из прошлого запуска мог остаться с МУСОРНЫМ началом (HTTP-страница
            // от прокси/капчи, которую не поймали ни content-type, ни первый-чанк —
            // например, обрыв записи после них). Append к такому началу дал бы битый
            // файл, который магическая сверка убьёт в самом конце — пустая трата 574 МБ.
            // Проверяем GGML-magic начала .part ДО Range-запроса: мусор → перекачка с нуля.
            if (partial && !hasGgmlMagic(tmp)) {
                Log.w(TAG, "${dest.name}: .part не начинается с GGML-magic — перекачка с нуля")
                tmp.delete()
                etagFile.delete()
                sizeFile.delete()
                partial = false
                startAt = 0L
            }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "Mozilla/5.0")
                // Дозапись оборванной части (resume) снижает трафик при разрывах.
                if (partial) {
                    setRequestProperty("Range", "bytes=$startAt-")
                    if (etagFile.exists()) {
                        val etag = etagFile.readText().trim()
                        if (etag.isNotEmpty()) setRequestProperty("If-Range", etag)
                    }
                }
            }

            try {
                conn.connect()
                val code = conn.responseCode
                val total: Long = when (code) {
                    HttpURLConnection.HTTP_PARTIAL, HttpURLConnection.HTTP_OK -> conn.contentLengthLong
                    else -> throw IllegalStateException("HTTP $code при скачивании ${dest.name}")
                }
                // Fail-loud вместо тихой дыры: без Content-Length (chunked, total<=0)
                // сверка размера после скачивания невозможна — обрыв на 99% пройдёт
                // незамеченным. HuggingFace resolve всегда отдаёт Content-Length.
                if (total <= 0) {
                    throw IllegalStateException("сервер не отдал Content-Length для ${dest.name} — не могу гарантировать целостность")
                }

                // HTML вместо бинарной модели: HF/CDN при 502/403/редиректах иногда
                // отвечают 200 с error-страницей. Пишем fail-loud ДО записи байтов —
                // иначе «успешно скачанная» текстуха упадёт в whisper, а resume-хвост
                // склеит HTML дальше. GGML всегда бинарь; text/html|text/plain —
                // точно не модель. Прочие/пустые типы не блокируем (легальные CDN
                // отдают application/octet-stream или вовсе без типа) — мусор поймает
                // финальная GGML-magic сверка ниже.
                val ct = conn.contentType ?: ""
                if (ct.contains("text/html", ignoreCase = true) ||
                    ct.contains("text/plain", ignoreCase = true)
                ) {
                    throw IllegalStateException("сервер вернул HTML/текст вместо модели $label (content-type=$ct) — проверь сеть/URL")
                }
                if (ct.isEmpty()) {
                    Log.w(TAG, "$label: content-type пуст — доверяю только GGML-magic сверке")
                }

                // Guard 1: сервер может проигнорировать Range и ответить 200 — тогда в теле
                // ПОЛНЫЙ файл с начала, а не хвост. Дозапись его к оборванному .part дала бы
                // битую модель. При 200 всегда пересоздаём .part с нуля. Если пришел 200
                // при наличии If-Range — значит ревизия изменилась, фиксируем новый ETag.
                var resuming = code == HttpURLConnection.HTTP_PARTIAL
                if (code == HttpURLConnection.HTTP_OK) {
                    if (partial) {
                        Log.w(TAG, "HTTP 200 (If-Range/Range → новая ревизия): пересобираю ${dest.name} с нуля")
                    }
                    tmp.delete()
                    saveEtag(conn.getHeaderField("ETag"), etagFile)
                    saveExpectedSize(total, sizeFile)
                }

                // Guard 2: без ETag-гарантии НЕ доверяем хвосту даже при совпавшем Content-Range
                // (ревизия могла смениться незаметно: длины совпали, байты другие),
                // и не доверяем, если начало хвоста не совпало с .part.
                if (resuming) {
                    val crStart = parseContentRangeStart(conn.getHeaderField("Content-Range"))
                    // 206 обязан нести Content-Range: если заголовка нет (crStart == null) —
                    // не знаем границу хвоста, склейка может лечь на неверный офсет → с нуля.
                    if (!etagFile.exists() || crStart == null || crStart != startAt) {
                        Log.w(
                            TAG,
                            "206 без etag/Content-Range гарантии (start=$crStart, ждали $startAt, etag=${etagFile.exists()}) — перекачка с нуля"
                        )
                        resuming = false
                        tmp.delete()
                        etagFile.delete()
                        sizeFile.delete()
                    }
                }

                if (!tmp.exists() || tmp.length() == 0L) resuming = false

                val doneStart = if (resuming) startAt else 0L
                // total гарантированно > 0 (fail-loud выше) — единственный источник
                // неизвестности был chunked, который мы отсекли.
                val totalBytes = if (resuming) doneStart + total else total
                Log.d(TAG, "скачиваю ${dest.name}, http=$code, осталось=$total, уже есть=$doneStart")

                var done = doneStart
                // append (true) ТОЛЬКО при честном 206 — иначе полный 200-ответ ляжет поверх хвоста.
                val out = FileOutputStream(tmp, resuming)
                conn.inputStream.use { input ->
                    out.use { fos ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            // Каждый чанк: отмена пользователем (корутина) — чистый выход,
                            // .part и sidecar-ы ОСТАЮТСЯ → следующий заход докачает с этого
                            // места (resume), а не начнёт 574 МБ заново.
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            // Доп. защита от HTML: content-type мог быть пустым/обманным —
                            // смотрим сами байты. Проверяем ПЕРВЫЙ чанк тела (done == doneStart;
                            // при честном 206 это начало хвоста — тоже валидный бинарь, а HTML
                            // туда не попадёт, т.к. хвост продолжает уже скачанный GGML).
                            if (done == doneStart && looksLikeHtml(buf, n)) {
                                // HTML — не «оборванная модель»: resume бессмысленен,
                                // чистим .part и sidecar-ы, чтобы не склеивать страницу.
                                tmp.delete()
                                etagFile.delete()
                                sizeFile.delete()
                                throw IllegalStateException("сервер вернул HTML вместо модели $label (error page?) — проверь сеть/URL")
                            }
                            fos.write(buf, 0, n)
                            done += n
                            onProgress(done, totalBytes)
                        }
                    }
                }

                if (done < minBytes) {
                    throw IllegalStateException("файл оборван: $done байт (min=$minBytes)")
                }

                // Точная сверка с ожидаемым размером из sidecar (Content-Length первого
                // 200-ответа): Content-Length точен, поэтому равенство строгое — ловит
                // «чистый» обрыв, при котором сервер закрыл поток на 99% файла, а
                // константный порог MIN_TURBO такой обрыв бы пропустил.
                val expected = readExpectedSize(sizeFile)
                if (expected != null && expected > 0 && done != expected) {
                    throw IllegalStateException("размер не сошёлся: $done != $expected")
                }

                // SHA-256 снапшот проверенного файла: считаем по tmp ДО rename (rename
                // не меняет содержимое — хеш тот же, экономит повторное чтение 574МБ),
                // манифест пишем уже под финальным именем после переноса.
                // GGML-magic ПЕРВЫМ: проверка 4 байт дешева, мусор убиваем ДО тяжёлого
                // SHA-256 574 МБ (хэш на заведомо битом файле — 3-5 сек впустую).
                verifyGgmlMagic(tmp, etagFile, sizeFile, label)
                val digest = sha256Of(tmp)
                if (digest == null) {
                    // не читается — это уже повреждение: не отдаём успех без манифеста
                    throw IllegalStateException("SHA-256 не посчитался для ${tmp.name} — файл не читается")
                }

                // Целиком скачан → атомарно переносим в финальное имя. Если renameTo
                // не дался (кросс-ФС/FUSE) — fallback копированием в временный
                // файл и rename; при сбое чистим всё, чтобы не оставить ни
                // недописанный dest, ни битый tmp.
                if (!tmp.renameTo(dest)) {
                    val destTmp = File(dest.parentFile, "${dest.name}.tmp-final")
                    Log.w(TAG, "${dest.name}: rename не дался — копирую целиком (может занять время на ${done / 1024 / 1024}MB)")
                    // Fallback-копия создаёт ВТОРОЙ экземпляр файла при живых tmp и dest.
                    // Пик каталога: tmp (done) + dest (если жив) + destTmp-копия (done)
                    // + запас. Считаем по done (прогресс), не по tmp.length() (буфер).
                    destTmp.delete() // сироты прошлых fallback-попыток
                    val peak = done + maxOf(done, if (dest.exists()) dest.length() else 0L) + 16L * 1024 * 1024
                    ensureFreeSpace(context, label, peak)
                    try {
                        tmp.copyTo(destTmp, overwrite = true)
                        if (!destTmp.renameTo(dest)) {
                            destTmp.delete()
                            throw IllegalStateException("не удалось перенести ${dest.name} в финальное имя")
                        }
                    } catch (e: Exception) {
                        // НЕ трогаем dest: если копирование упало (место/IO), на месте
                        // мог остаться прежний рабочий файл — уничтожать его нельзя.
                        destTmp.delete()
                        throw e
                    }
                    tmp.delete()
                    // Fallback-путь копировал байты вручную — в отличие от rename,
                    // содержимое dest не гарантировано идентично tmp: сверяем явно.
                    val copiedDigest = sha256Of(dest)
                    if (copiedDigest == null || copiedDigest != digest) {
                        throw IllegalStateException("${dest.name}: SHA-256 не сошёлся после fallback-переноса — файл не тот")
                    }
                }
                // Манифест пишем ТОЛЬКО под реально лежащим файлом: rename мог тихо
                // не пройти (кривая ФС) — тогда dest старый/отсутствует, и манифест
                // не должен фиксировать несуществующий или прежний файл.
                if (dest.exists() && dest.length() == done) {
                    writeManifest(dest, digest)
                } else {
                    throw IllegalStateException("${dest.name}: файл не на месте после переноса (${dest.length()} != $done)")
                }
                // Скачивание завершено: sidecar-ы больше не нужны (следующий полный
                // заход запишет свежие). Ошибка удаления не критична.
                etagFile.delete()
                sizeFile.delete()
                Log.d(TAG, "скачана: ${dest.absolutePath} (${dest.length() / 1024 / 1024}MB)")
                dest
            } finally {
                conn.disconnect()
            }
        }
    }

    /** "bytes 1234-999999/1000000" → 1234; null если заголовка/парсинга нет. */
    private fun parseContentRangeStart(header: String?): Long? {
        if (header == null) return null
        val m = Regex("""bytes\s+(\d+)-""").find(header) ?: return null
        return m.groupValues[1].toLongOrNull()
    }

    /**
     * Предстартовая проверка места: у пользователя должно быть сообщение ДО того,
     * как 574 МБ оборвутся посреди пути. usableSpace в {0, -1} — неизвестно
     * (ФС без лимита / квота не реализована): не блокируем, только логируем —
     * реальный недостаток места всё равно поймает IOException при записи.
     */
    private fun ensureFreeSpace(context: Context, label: String, requiredFree: Long) {
        val free = context.filesDir.usableSpace
        val needMb = requiredFree / 1024 / 1024
        when {
            free >= requiredFree -> Unit // места достаточно
            free > 0 -> throw IllegalStateException(
                "Недостаточно места для $label: нужно примерно $needMb МБ, " +
                    "свободно ${free / 1024 / 1024} МБ (не хватает ${(requiredFree - free) / 1024 / 1024} МБ)"
            )
            else -> Log.w(TAG, "usableSpace=$free для $label (нужно ~$needMb МБ) — проверку пропускаю")
        }
    }

    /**
     * Error-page (HTML) вместо бинарной модели: ищем маркеры страницы в первых
     * 512 байтах. Смотрим ТОЛЬКО начало файла (первый чанк тела): GGML всегда
     * открывается magic "ggml", '<' там невозможен — ложных срабатываний нет.
     * Середину потока НЕ сканируем (в бинарнике '<' встречается легально) —
     * там мусор поймает финальная GGML-magic сверка.
     */
    private fun looksLikeHtml(buf: ByteArray, len: Int): Boolean {
        val head = String(buf, 0, minOf(len, 512), Charsets.UTF_8)
        return head.contains("<!DOCTYPE", ignoreCase = true) ||
            head.contains("<html", ignoreCase = true)
    }

    /**
     * GGML-magic файла: первые 4 байта обязаны быть "ggml" (whisper.cpp ggml-*.bin).
     * Дешёвая проверка (4 байта) — используется и для .part-начала перед resume,
     * и для финальной сверки всего tmp.
     */
    private fun hasGgmlMagic(file: File): Boolean = try {
        file.inputStream().use { input ->
            // readNBytes — API 33+, minSdk 28 — читаем вручную.
            val magic = ByteArray(4)
            var off = 0
            while (off < 4) {
                val n = input.read(magic, off, 4 - off)
                if (n < 0) break
                off += n
            }
            off == 4 && magic[0] == 'g'.code.toByte() &&
                magic[1] == 'g'.code.toByte() &&
                magic[2] == 'm'.code.toByte() &&
                magic[3] == 'l'.code.toByte()
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Финальная сверка tmp перед rename: см. [hasGgmlMagic]. Ловит мусор,
     * прошедший предварительные проверки (обманутый content-type, HTML в середине
     * потока от прокси). При провале чистим .part + sidecar-ы ДО rename/манифеста —
     * повреждение не станет «валидной» моделью, следующая попытка начнёт с нуля.
     */
    private fun verifyGgmlMagic(tmp: File, etagFile: File, sizeFile: File, label: String) {
        if (hasGgmlMagic(tmp)) return
        Log.e(TAG, "$label: GGML-magic не сошёлся — мусор вместо модели, перекачка с нуля")
        tmp.delete()
        etagFile.delete()
        sizeFile.delete()
        throw IllegalStateException("сервер отдал не модель (нет GGML-magic) для $label — файл очищен, попробуй ещё раз")
    }

    /**
     * Пишет ETag ответа в sidecar; если сервер ETag не отдал или он слабый
     * (W/"..." — не гарантирует байтовую идентичность) — удаляет sidecar,
     * чтобы resume не доверял недоказанной ревизии.
     */
    private fun saveEtag(etag: String?, etagFile: File) {
        try {
            if (etag != null && etag.isNotEmpty() && !etag.startsWith("W/")) {
                etagFile.writeText(etag)
            } else {
                etagFile.delete()
            }
        } catch (_: Exception) {
            // sidecar — best-effort, скачивание от него не зависит
        }
    }

    /** Пишет ожидаемый размер в sidecar; при -1 (chunked) размер неизвестен — удаляет. */
    private fun saveExpectedSize(total: Long, sizeFile: File) {
        try {
            if (total > 0) {
                sizeFile.writeText(total.toString())
            } else {
                sizeFile.delete()
            }
        } catch (_: Exception) {
            // best-effort
        }
    }

    /** Читает ожидаемый размер из sidecar; null если sidecar нет/битый. */
    private fun readExpectedSize(sizeFile: File): Long? {
        if (!sizeFile.exists()) return null
        return try {
            sizeFile.readText().trim().toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Порог «докачана» для *Ready(): точный размер из sidecar (если есть — значит
     * загрузка завершалась и размер известен), иначе fallback-константа.
     */
    private fun readyThreshold(dest: File, fallback: Long): Long {
        val sizeFile = File(dest.parentFile, "${dest.name}.part.size")
        return readExpectedSize(sizeFile) ?: fallback
    }
}