package org.opencode.mobile.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Скачивание STT-моделей на устройство по требованию.
 *
 * Whisper ggml small — single-file в filesDir/models.
 * Sherpa-onnx модели — multi-file каталоги в filesDir/stt/<id>/.
 */
object ModelDownloader {
    private const val TAG = "ModelDL"

    private const val HF_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
    private const val URL_SMALL = "$HF_BASE/ggml-small.bin"
    const val SMALL_FILE = "ggml-small.bin"
    private const val MIN_SMALL = 460L * 1024 * 1024
    const val SMALL_BYTES = 487601967L
    const val SMALL_MODEL = "small"
    private const val MIN_FREE_SMALL = 550L * 1024 * 1024

    private val RETIRED_MODEL_DIRS = listOf("ncnn-turbo")

    /** Папка каталога ggml-моделей внутри filesDir: <filesDir>/models/. */
    fun modelsDir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    private val downloadMutex = Mutex()

    /** Целостность скачанной модели. */
    enum class ModelIntegrity { VALID, NO_MANIFEST, CORRUPT }

    // ---- manifest/sha256 ----

    private fun exactSizeFile(file: File): File = File(file.parentFile, "${file.name}.size")

    fun persistExactSize(file: File) {
        runCatching { exactSizeFile(file).writeText(file.length().toString()) }
    }

    private fun readExpectedSize(sizeFile: File): Long? =
        runCatching { sizeFile.takeIf { it.exists() }?.readText()?.trim()?.toLong() }
            .getOrNull()
            ?.takeIf { it > 0L }

    private fun sha256File(file: File): File = File(file.parentFile, "${file.name}.sha256")

    fun sha256Of(file: File): String? {
        if (!file.exists() || !file.isFile) return null
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(DEFAULT_BUF)
        file.inputStream().use { input ->
            var read: Int
            while (input.read(buf).also { read = it } >= 0) {
                if (read > 0) digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun writeManifest(file: File, digest: String) {
        runCatching { sha256File(file).writeText(digest.lowercase() + "\n") }
    }

    private fun readManifest(file: File): String? =
        runCatching { sha256File(file).takeIf { it.exists() }?.readText()?.trim()?.lowercase() }
            .getOrNull()
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }

    private data class IntegrityStamp(val length: Long, val mtime: Long)
    private val integrityCache = ConcurrentHashMap<String, IntegrityStamp>()

    fun checkIntegrity(file: File): ModelIntegrity {
        if (!file.exists() || !file.isFile) return ModelIntegrity.CORRUPT
        val stamp = IntegrityStamp(file.length(), file.lastModified())
        if (integrityCache[file.absolutePath] == stamp) return ModelIntegrity.VALID
        val expected = readManifest(file) ?: return ModelIntegrity.NO_MANIFEST
        val digest = sha256Of(file) ?: return ModelIntegrity.CORRUPT
        val ok = digest == expected
        val stampAfter = IntegrityStamp(file.length(), file.lastModified())
        if (ok && stampAfter == stamp) integrityCache[file.absolutePath] = stamp
        return if (ok) ModelIntegrity.VALID else ModelIntegrity.CORRUPT
    }

    // ---- small ----

    fun smallFile(context: Context): File = File(modelsDir(context), SMALL_FILE)

    fun smallReady(context: Context): Boolean {
        val f = smallFile(context)
        return f.exists() &&
            f.length() >= readyThreshold(f, MIN_SMALL) &&
            checkIntegrity(f) != ModelIntegrity.CORRUPT
    }

    suspend fun downloadSmall(
        context: Context,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File = downloadTo(context, URL_SMALL, smallFile(context), MIN_SMALL, "small", MIN_FREE_SMALL, onProgress)

    // ---- generic multi-file STT models (sherpa-onnx) ----

    fun sttRoot(context: Context): File = File(context.filesDir, "stt").apply { mkdirs() }

    fun sttModelDir(context: Context, model: SttModel): File =
        File(sttRoot(context), model.dirName ?: model.id).apply { mkdirs() }

    fun sttModelReady(context: Context, model: SttModel): Boolean {
        if (model.flatFileName != null) {
            val f = model.fileOf(context)
            return f.exists() && f.length() > 0
        }
        if (model.files.isEmpty()) return false
        val dir = File(sttRoot(context), model.dirName ?: model.id)
        if (!dir.exists()) return false
        return model.files.all { mf ->
            val f = File(dir, mf.name)
            f.exists() && f.length() > 0 && (mf.bytes <= 0L || f.length() >= minOf(mf.bytes, 1024L))
        }
    }

    suspend fun downloadSttModel(
        context: Context,
        model: SttModel,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        if (model.files.isEmpty()) {
            return if (model.id == SttModels.DEFAULT_ID) downloadSmall(context, onProgress)
            else error("Модель ${model.id} не имеет файлов для скачивания")
        }
        return downloadMutex.withLock {
            withContext(Dispatchers.IO) {
                val dir = sttModelDir(context, model)
                val totalKnown = model.files.sumOf { it.bytes.coerceAtLeast(0L) }.takeIf { it > 0L } ?: model.bytes.coerceAtLeast(0L)
                ensureFreeSpace(context, model.id, model.bytes.coerceAtLeast(totalKnown) + 64L * 1024L * 1024L)
                var doneBefore = 0L
                for (mf in model.files) {
                    val target = File(dir, mf.name)
                    if (target.exists() && target.length() > 0 && (mf.bytes <= 0L || target.length() == mf.bytes)) {
                        doneBefore += target.length()
                        onProgress(doneBefore, totalKnown.coerceAtLeast(doneBefore))
                        continue
                    }
                    doneBefore += downloadToSttFile(dir, mf, doneBefore, totalKnown, onProgress)
                }
                File(dir, ".ready").writeText("ok\n")
                onProgress(totalKnown.coerceAtLeast(doneBefore), totalKnown.coerceAtLeast(doneBefore))
                dir
            }
        }
    }

    private suspend fun downloadToSttFile(
        dir: File,
        mf: SttModelFile,
        doneBefore: Long,
        totalKnown: Long,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        val dest = File(dir, mf.name)
        val tmp = File(dir, mf.name + ".part")
        val expectedMin = if (mf.bytes > 0L) maxOf(1L, mf.bytes - 1024L) else 1L
        if (dest.exists() && dest.length() >= expectedMin) return dest.length()
        var startAt = if (tmp.exists()) tmp.length() else 0L
        val conn = (URL(mf.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "opencode-mobile/1.0")
            if (startAt > 0L) setRequestProperty("Range", "bytes=$startAt-")
        }
        try {
            conn.connect()
            val code = conn.responseCode
            val append = code == HttpURLConnection.HTTP_PARTIAL
            if (!append) startAt = 0L
            if (code !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                error("HTTP $code при скачивании ${mf.name}")
            }
            val bodyLen = conn.contentLengthLong.takeIf { it > 0L } ?: -1L
            val fileTotal = if (append && bodyLen > 0L) startAt + bodyLen else if (bodyLen > 0L) bodyLen else mf.bytes
            FileOutputStream(tmp, append).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(DEFAULT_BUF)
                    var read: Int
                    var fileDone = startAt
                    while (input.read(buf).also { read = it } >= 0) {
                        coroutineContext.ensureActive()
                        if (read == 0) continue
                        out.write(buf, 0, read)
                        fileDone += read
                        val overallTotal = if (totalKnown > 0L) totalKnown else if (fileTotal > 0L) doneBefore + fileTotal else doneBefore + fileDone
                        onProgress(doneBefore + fileDone, overallTotal)
                    }
                    out.fd.sync()
                }
            }
            if (mf.sha256 != null) {
                val got = sha256Of(tmp)
                if (!got.equals(mf.sha256, ignoreCase = true)) {
                    tmp.delete()
                    error("SHA-256 не совпал для ${mf.name}")
                }
                writeManifest(tmp, got)
            }
            if (mf.bytes > 0L && tmp.length() != mf.bytes) {
                Log.w(TAG, "${mf.name}: размер ${tmp.length()} != ожидаемый ${mf.bytes}, принимаю по факту")
            }
            persistExactSize(tmp)
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            return dest.length()
        } finally {
            conn.disconnect()
        }
    }

    fun deleteSttModel(context: Context, model: SttModel): Boolean {
        if (model.flatFileName != null) return deleteModel(model.fileOf(context))
        val dir = File(sttRoot(context), model.dirName ?: model.id)
        return dir.exists() && dir.deleteRecursively()
    }

    fun deleteOtherSttDirs(context: Context): List<File> {
        val root = sttRoot(context)
        val keep = SttModels.sttDirNames
        val gone = root.listFiles()?.filter { it.isDirectory && it.name !in keep }.orEmpty()
        return gone.filter { it.deleteRecursively() }
    }

    // ---- управление моделями ----

    fun deleteModel(file: File): Boolean {
        var deleted = false
        if (file.exists() && file.delete()) deleted = true
        val dir = file.parentFile ?: return deleted
        listOf(
            "${file.name}.part",
            "${file.name}.part.etag",
            "${file.name}.part.size",
            "${file.name}.sha256",
            "${file.name}.size",
            "${file.name}.tmp-final",
            "${file.name}.part.tmp",
            "${file.name}.tmp",
        ).forEach { name ->
            val f = File(dir, name)
            if (f.exists() && f.delete()) deleted = true
        }
        return deleted
    }

    fun deleteOtherModels(context: Context): List<File> {
        val keep = SttModels.fileNames
        val root = modelsDir(context)
        val gone =
            root
                .listFiles()
                ?.filter { it.isFile && it.name.endsWith(".bin") && it.name !in keep }
                .orEmpty()
        val dirs =
            RETIRED_MODEL_DIRS
                .map { File(root, it) }
                .filter { it.isDirectory && it.deleteRecursively() }
        return gone.filter { deleteModel(it) } + dirs
    }

    fun modelsUsedBytes(context: Context): Long {
        val dir = modelsDir(context)
        val ggml = if (dir.exists()) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        val stt = sttRoot(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return ggml + stt
    }

    fun freeBytes(context: Context): Long = context.filesDir.usableSpace

    private suspend fun downloadTo(
        context: Context,
        url: String,
        dest: File,
        minBytes: Long,
        label: String,
        requiredFree: Long,
        onProgress: (Long, Long) -> Unit,
    ): File =
        downloadMutex.withLock {
            withContext(Dispatchers.IO) {
                if (dest.exists() && dest.length() > minBytes) {
                    when (checkIntegrity(dest)) {
                        ModelIntegrity.VALID -> {
                            persistExactSize(dest)
                            Log.d(TAG, "уже докачана и валидна: ${dest.absolutePath}")
                            return@withContext dest
                        }
                        ModelIntegrity.NO_MANIFEST -> {
                            if (hasGgmlMagic(dest)) {
                                persistExactSize(dest)
                                Log.d(TAG, "уже докачана (NO_MANIFEST, ggml-magic ok): ${dest.absolutePath}")
                                return@withContext dest
                            }
                            Log.w(TAG, "${dest.name}: GGML-magic не сходится — перекачка")
                            deleteModel(dest)
                        }
                        ModelIntegrity.CORRUPT -> {
                            Log.w(TAG, "${dest.name}: SHA-256 не сошёлся — удаляю и качаю заново")
                            deleteModel(dest)
                        }
                    }
                }

                ensureFreeSpace(context, label, requiredFree)

                val tmp = File(modelsDir(context), "${dest.name}.part")
                var partial = tmp.exists() && tmp.length() > 0
                var startAt = if (partial) tmp.length() else 0L
                val etagFile = File(modelsDir(context), "${dest.name}.part.etag")
                val sizeFile = File(modelsDir(context), "${dest.name}.part.size")
                val expectedSize = readExpectedSize(sizeFile)
                val savedEtag = if (partial) etagFile.takeIf { it.exists() }?.readText()?.trim() else null
                var conn = openGet(url, startAt, savedEtag)
                var code = conn.responseCode
                var isPartial = false
                when (code) {
                    HttpURLConnection.HTTP_PARTIAL -> {
                        isPartial = true
                        val cr = conn.getHeaderField("Content-Range")
                        val rangeStart = cr?.substringAfter("bytes ")?.substringBefore("-")?.toLongOrNull()
                        if (rangeStart != null && rangeStart != startAt) {
                            Log.w(TAG, "$label: сервер вернул старт $rangeStart, ожидали $startAt — перекачка")
                            conn.disconnect()
                            tmp.delete()
                            etagFile.delete()
                            sizeFile.delete()
                            startAt = 0L
                            partial = false
                            conn = openGet(url, 0L, null)
                            code = conn.responseCode
                            isPartial = code == HttpURLConnection.HTTP_PARTIAL
                        }
                    }
                    HttpURLConnection.HTTP_OK -> {
                        if (partial) {
                            Log.w(TAG, "$label: сервер игнорирует Range, скачиваем с нуля")
                            tmp.delete()
                            etagFile.delete()
                            sizeFile.delete()
                            startAt = 0L
                            partial = false
                        }
                    }
                    else -> {
                        conn.disconnect()
                        error("HTTP $code при скачивании $label")
                    }
                }
                if (code !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                    conn.disconnect()
                    error("HTTP $code при скачивании $label")
                }
                if (!isPartial) startAt = 0L
                val bodyLen = conn.contentLengthLong.takeIf { it > 0L } ?: -1L
                val total = when {
                    isPartial && bodyLen > 0L -> startAt + bodyLen
                    bodyLen > 0L -> bodyLen
                    expectedSize != null -> expectedSize
                    else -> -1L
                }
                conn.getHeaderField("ETag")?.takeIf { it.isNotBlank() }?.let { etagFile.writeText(it.trim()) }
                if (!partial || !sizeFile.exists()) {
                    if (total > 0L) sizeFile.writeText(total.toString())
                }

                try {
                    FileOutputStream(tmp, isPartial).use { out ->
                        conn.inputStream.use { input ->
                            val buf = ByteArray(DEFAULT_BUF)
                            var read: Int
                            var done = startAt
                            var lastReport = 0L
                            while (input.read(buf).also { read = it } >= 0) {
                                coroutineContext.ensureActive()
                                if (read == 0) continue
                                out.write(buf, 0, read)
                                done += read
                                val now = System.currentTimeMillis()
                                if (now - lastReport > PROGRESS_MS) {
                                    onProgress(done, total.coerceAtLeast(done))
                                    lastReport = now
                                }
                            }
                            out.fd.sync()
                            onProgress(done, total.coerceAtLeast(done))
                        }
                    }
                } finally {
                    conn.disconnect()
                }

                val finalSize = tmp.length()
                val exactExpected = readExpectedSize(sizeFile)
                if (exactExpected != null && finalSize != exactExpected) {
                    Log.w(TAG, "$label: размер $finalSize != $exactExpected, оставляю .part для докачки")
                    error("Неполная загрузка $label: $finalSize из $exactExpected")
                }
                if (finalSize < minBytes) {
                    error("Скачанный файл $label слишком мал: $finalSize < $minBytes")
                }
                if (!verifyGgmlMagic(tmp)) {
                    tmp.delete()
                    etagFile.delete()
                    sizeFile.delete()
                    error("Файл $label не похож на GGML (bad magic)")
                }
                val digest = sha256Of(tmp)
                if (digest != null) writeManifest(tmp, digest)
                persistExactSize(tmp)
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                etagFile.delete()
                sizeFile.delete()
                Log.d(TAG, "$label скачан: ${dest.absolutePath} (${dest.length()} б)")
                dest
            }
        }

    private fun openGet(url: String, startAt: Long, etag: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "opencode-mobile/1.0")
            if (startAt > 0L) {
                setRequestProperty("Range", "bytes=$startAt-")
                if (!etag.isNullOrBlank()) setRequestProperty("If-Range", etag)
            }
        }

    private fun ensureFreeSpace(context: Context, label: String, required: Long) {
        val free = context.filesDir.usableSpace
        if (free < required) {
            val needMb = required / 1024 / 1024
            val freeMb = free / 1024 / 1024
            error("Недостаточно места для $label: нужно ~${needMb} МБ, свободно ${freeMb} МБ")
        }
    }

    private fun hasGgmlMagic(file: File): Boolean = verifyGgmlMagic(file)

    private fun verifyGgmlMagic(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        val head = ByteArray(4)
        return runCatching {
            file.inputStream().use { if (it.read(head) != 4) return@runCatching false }
            // "ggml" in little-endian-ish file header appears as bytes 67 67 6d 6c? In GGUF it's "GGUF";
            // ggml old format starts with 0x67676d6c ('ggml') little-endian uint32.
            val magicLe = ((head[3].toInt() and 0xFF) shl 24) or ((head[2].toInt() and 0xFF) shl 16) or
                ((head[1].toInt() and 0xFF) shl 8) or (head[0].toInt() and 0xFF)
            magicLe == 0x67676D6C || magicLe == 0x46554747 // ggml or GGUF
        }.getOrDefault(false)
    }

    private fun readyThreshold(file: File, fallback: Long): Long {
        val exact = readExpectedSize(exactSizeFile(file))
        if (exact != null && exact >= fallback * 0.97) return exact
        return fallback
    }

    private const val DEFAULT_BUF = 64 * 1024
    private const val PROGRESS_MS = 400L
}
