package org.opencode.mobile.installer

import android.os.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Copies an APK from the user-visible Download folder into private installer storage. */
@Suppress("MagicNumber", "LongMethod", "NestedBlockDepth", "TooGenericExceptionCaught")
internal object LocalApkStager {
    private const val BUFFER_BYTES = 128 * 1024
    private const val MIN_FREE_BYTES = 64L * 1024 * 1024

    suspend fun stage(
        jobId: String,
        path: String,
        expectedSize: Long,
        expectedSha256: String,
        directory: File,
    ): File {
        val source = resolve(path)
        require(source.length() == expectedSize) {
            "Local APK size differs from size_bytes"
        }
        val part = File(directory, "$jobId.apk.part")
        val apk = File(directory, "$jobId.apk")
        val requiredFree = expectedSize + MIN_FREE_BYTES
        require(directory.usableSpace >= requiredFree) {
            "Not enough free space for the local APK"
        }
        var copied = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            source.inputStream().use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= AppInstallRequestValidator.MAX_APK_BYTES) {
                            "Local APK exceeds the 2 GiB safety limit"
                        }
                        require(copied <= expectedSize) {
                            "Local APK is larger than size_bytes"
                        }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
            }
            require(copied == expectedSize) {
                "Local APK size does not match size_bytes"
            }
            require(digest.digest().toHexString() == expectedSha256) {
                "Local APK SHA-256 mismatch"
            }
            require(part.renameTo(apk)) {
                "Cannot finalize local APK"
            }
            return apk
        } catch (cancelled: CancellationException) {
            part.delete()
            throw cancelled
        } catch (error: Exception) {
            part.delete()
            throw error
        }
    }

    private fun resolve(path: String): File {
        val downloadRoot =
            runCatching {
                File(Environment.getExternalStorageDirectory(), "Download").canonicalFile
            }.getOrElse {
                throw IllegalArgumentException("Cannot resolve Android Download folder")
            }
        val source =
            runCatching { File(path).canonicalFile }.getOrElse {
                throw IllegalArgumentException("Cannot resolve local APK path")
            }
        val rootPath = downloadRoot.path
        val insideDownload =
            source.path == rootPath || source.path.startsWith(rootPath + File.separator)
        require(insideDownload) {
            "Local APK must be inside the Android Download folder"
        }
        require(source.isFile) { "Local APK does not exist" }
        require(source.name.endsWith(".apk", ignoreCase = true)) {
            "Local APK path must end with .apk"
        }
        return source
    }

    private fun ByteArray.toHexString(): String = joinToString("") { byte -> "%02x".format(byte) }
}
