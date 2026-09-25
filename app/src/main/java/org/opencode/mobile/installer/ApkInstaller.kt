package org.opencode.mobile.installer

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/** Выполняет только официальные rootless-операции Android и хранит их состояние. */
@Suppress("MagicNumber", "TooManyFunctions")
@SuppressLint("StaticFieldLeak")
object ApkInstaller {
    const val STATUS_ACTION = "org.opencode.mobile.INSTALL_STATUS"
    const val EXTRA_JOB_ID = "job_id"

    private const val TAG = "ApkInstaller"
    private const val MAX_REDIRECTS = 5
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val BUFFER_BYTES = 128 * 1024
    private const val MIN_FREE_BYTES = 64L * 1024 * 1024
    private const val PLAY_PACKAGE = "com.android.vending"

    private fun packageInstallerCallbackFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

    private data class Job(
        val id: String,
        val source: String,
        val state: InstallState,
        val packageName: String?,
        val message: String,
        val updatedAt: Long,
        val signingCertificateSha256: String?,
    )

    private data class VerifiedApk(
        val packageName: String,
        val signingCertificateSha256: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private lateinit var context: Context

    @Synchronized
    fun initialize(context: Context) {
        if (::context.isInitialized) return
        this.context = context.applicationContext
        installDirectory().mkdirs()
        cleanOldFiles()
    }

    @Synchronized
    @Suppress("ReturnCount")
    fun openPlay(spec: PlayInstallSpec): InstallJobSnapshot {
        initialize(contextOrThrow())
        val id = UUID.randomUUID().toString()
        val marketUri =
            if (spec.packageName != null) {
                "market://details?id=${Uri.encode(spec.packageName)}"
            } else {
                "market://search?q=${Uri.encode(spec.query.orEmpty())}&c=apps"
            }
        val webUri =
            if (spec.packageName != null) {
                "https://play.google.com/store/apps/details?id=${Uri.encode(spec.packageName)}"
            } else {
                "https://play.google.com/store/search?q=${Uri.encode(spec.query.orEmpty())}&c=apps"
            }
        val storeInstalled =
            runCatching {
                context.packageManager.getPackageInfo(PLAY_PACKAGE, 0)
                true
            }.getOrDefault(false)
        val intent =
            Intent(
                Intent.ACTION_VIEW,
                (if (storeInstalled) marketUri else webUri).toUri(),
            ).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (storeInstalled) setPackage(PLAY_PACKAGE)
            }
        try {
            context.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            Log.w(TAG, "No activity can open the Play listing", error)
            return failedSnapshot(id, "play", spec.packageName, "No activity can open the Play listing")
        } catch (error: SecurityException) {
            Log.w(TAG, "Android blocked the Play listing", error)
            return failedSnapshot(id, "play", spec.packageName, "Android blocked opening the Play listing")
        }
        val message = if (storeInstalled) {
            "Google Play opened; tap Install to continue"
        } else {
            "Google Play is unavailable; opened the web listing"
        }
        return update(id, "play", InstallState.STORE_OPENED, spec.packageName, message)
    }

    @Synchronized
    fun submit(spec: ApkInstallSpec): InstallJobSnapshot {
        initialize(contextOrThrow())
        val id = UUID.randomUUID().toString()
        val initial =
            update(
                id,
                spec.source.wireName,
                InstallState.QUEUED,
                spec.packageName,
                "Download queued from ${spec.source.wireName}",
            )
        scope.launchSafely(id, spec.packageName) {
            val apk = download(spec, id)
            val verified = verifyPackage(apk, spec.packageName, spec.signingCertificateSha256, id)
            commit(apk, id, verified.packageName, spec.sha256)
        }
        return initial
    }

    @Synchronized
    fun status(id: String): InstallJobSnapshot? = jobs[id]?.toSnapshot()

    internal fun onInstallerStatus(
        jobId: String,
        status: Int,
        statusMessage: String?,
        packageName: String?,
        pendingIntent: Intent?,
    ) {
        Log.i(
            TAG,
            "Installer status=$status package=$packageName pendingIntent=${pendingIntent != null}",
        )
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                update(
                    jobId,
                    sourceForJob(jobId),
                    InstallState.AWAITING_USER,
                    packageName,
                    statusMessage ?: "Waiting for user confirmation",
                )
                if (pendingIntent != null) {
                    launchInstaller(jobId, packageName, pendingIntent)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                update(
                    jobId,
                    sourceForJob(jobId),
                    InstallState.INSTALLED,
                    packageName,
                    statusMessage ?: "Installed",
                )
                deleteDownload(jobId)
            }
            else -> {
                update(
                    jobId,
                    sourceForJob(jobId),
                    InstallState.FAILED,
                    packageName,
                    statusMessage ?: "Android installer failed with status $status",
                )
                deleteDownload(jobId)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth", "TooGenericExceptionCaught")
    private suspend fun download(
        spec: ApkInstallSpec,
        jobId: String,
    ): File {
        val directory = installDirectory()
        val part = File(directory, "$jobId.apk.part")
        val apk = File(directory, "$jobId.apk")
        update(jobId, sourceForJob(jobId), InstallState.DOWNLOADING, spec.packageName, "Downloading APK")
        var downloaded = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        var connection: HttpURLConnection? = null
        try {
            val activeConnection = openDownloadConnection(spec.url)
            connection = activeConnection
            activeConnection.connect()
            val responseCode = activeConnection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                error("APK server returned HTTP $responseCode")
            }
            val declaredSize = activeConnection.contentLengthLong
            if (declaredSize > AppInstallRequestValidator.MAX_APK_BYTES) {
                error("APK exceeds the 2 GiB safety limit")
            }
            spec.sizeBytes?.let { expected ->
                if (declaredSize >= 0 && declaredSize != expected) {
                    error("APK size differs from size_bytes")
                }
            }
            val expectedSize = spec.sizeBytes ?: declaredSize.takeIf { it >= 0 } ?: 0L
            val requiredFree = expectedSize + MIN_FREE_BYTES
            if (directory.usableSpace < requiredFree) {
                error("Not enough free space for the APK")
            }
            activeConnection.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        if (downloaded > AppInstallRequestValidator.MAX_APK_BYTES) {
                            error("APK exceeds the 2 GiB safety limit")
                        }
                        spec.sizeBytes?.let { expected ->
                            if (downloaded > expected) error("APK is larger than size_bytes")
                        }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
            }
            if (spec.sizeBytes != null && downloaded != spec.sizeBytes) {
                error("Downloaded APK size does not match size_bytes")
            }
            update(jobId, sourceForJob(jobId), InstallState.VERIFYING, spec.packageName, "Verifying SHA-256")
            val actualHash = digest.digest().toHexString()
            if (actualHash != spec.sha256) {
                error("APK SHA-256 mismatch")
            }
            if (!part.renameTo(apk)) {
                error("Cannot finalize downloaded APK")
            }
            return apk
        } catch (cancelled: CancellationException) {
            part.delete()
            update(jobId, sourceForJob(jobId), InstallState.CANCELLED, spec.packageName, "Download cancelled")
            throw cancelled
        } catch (error: Exception) {
            part.delete()
            update(jobId, sourceForJob(jobId), InstallState.FAILED, spec.packageName, safeMessage(error))
            throw error
        } finally {
            connection?.disconnect()
        }
    }

    private fun openDownloadConnection(initialUrl: String): HttpURLConnection {
        val expectedSource = AppInstallRequestValidator.validateTrustedApkUrl(initialUrl)
        var currentUrl = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            AppInstallRequestValidator.validateTrustedApkUrl(currentUrl, expectedSource.wireName)
            val uri = URI(currentUrl)
            val host = checkNotNull(uri.host) { "APK URL must contain a host" }
            val addresses = InetAddress.getAllByName(host)
            require(addresses.isNotEmpty() && addresses.none(::isLocalAddress)) {
                "APK URL resolved to a local/private address"
            }
            val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream")
                setRequestProperty("User-Agent", "OpenCode-Mobile/0.1")
            }
            val code = connection.responseCode
            if (code !in 300..399) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            require(!location.isNullOrBlank()) { "APK redirect has no Location header" }
            currentUrl = URI(currentUrl).resolve(location).toString()
            require(redirectCount < MAX_REDIRECTS) { "Too many APK redirects" }
        }
        error("Too many APK redirects")
    }

    private fun verifyPackage(
        apk: File,
        expectedPackage: String?,
        expectedSigningCertificateSha256: String?,
        jobId: String,
    ): VerifiedApk {
        val info = context.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val detected = info?.packageName
        require(!detected.isNullOrBlank()) { "Downloaded file is not a readable APK" }
        if (expectedPackage != null) {
            require(detected == expectedPackage) {
                "APK package is $detected, expected $expectedPackage"
            }
        }
        val signerFingerprints = signingFingerprints(checkNotNull(info))
        require(signerFingerprints.isNotEmpty()) { "APK has no signing certificate" }
        expectedSigningCertificateSha256?.let { expectedSigner ->
            require(expectedSigner in signerFingerprints) {
                "APK signing certificate does not match the expected certificate"
            }
        }
        val signerSummary = signerFingerprints.joinToString(",")
        update(
            jobId,
            sourceForJob(jobId),
            InstallState.READY_TO_INSTALL,
            detected,
            "APK verified; signing certificate $signerSummary",
            signerSummary,
        )
        return VerifiedApk(detected, signerSummary)
    }

    private fun signingFingerprints(info: PackageInfo): List<String> {
        val signingInfo: SigningInfo = checkNotNull(info.signingInfo) { "APK has no signing info" }
        // Match the certificate that signs this APK, not an old certificate from a rotation history.
        val signatures: Array<Signature> = signingInfo.apkContentsSigners
        val fingerprints =
            signatures.map { signature ->
                val digest = MessageDigest.getInstance("SHA-256")
                digest.digest(signature.toByteArray()).toHexString()
            }
        return fingerprints.distinct().sorted()
    }

    @Suppress("NestedBlockDepth", "TooGenericExceptionCaught")
    private fun commit(
        apk: File,
        jobId: String,
        packageName: String,
        expectedSha256: String,
    ) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                val digest = MessageDigest.getInstance("SHA-256")
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                        }
                    }
                    session.fsync(output)
                }
                check(digest.digest().toHexString() == expectedSha256) {
                    "APK changed after verification"
                }
                val pendingIntent =
                    PendingIntent.getBroadcast(
                        context,
                        jobId.hashCode(),
                        Intent(context, PackageInstallReceiver::class.java).apply {
                            action = STATUS_ACTION
                            data = "opencode://install/$jobId".toUri()
                            putExtra(EXTRA_JOB_ID, jobId)
                            putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                        },
                        // PackageInstaller adds status extras to this sender;
                        // Android 12+ therefore requires a mutable PendingIntent here.
                        packageInstallerCallbackFlags(),
                    )
                update(
                    jobId,
                    sourceForJob(jobId),
                    InstallState.INSTALLING,
                    packageName,
                    "Waiting for Android installer",
                )
                session.commit(pendingIntent.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
                .onFailure { abandonError -> Log.w(TAG, "Cannot abandon install session $sessionId", abandonError) }
            update(jobId, sourceForJob(jobId), InstallState.FAILED, packageName, safeMessage(error))
            deleteDownload(jobId)
        }
    }

    private fun launchInstaller(
        jobId: String,
        packageName: String?,
        activityIntent: Intent,
    ) {
        val launchIntent =
            Intent(activityIntent).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val wrapped =
            PendingIntent.getActivity(
                context,
                jobId.hashCode(),
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
            )
        runCatching { wrapped.send() }
            .onSuccess { Log.i(TAG, "Android installer launch requested for $jobId") }
            .onFailure { error ->
                Log.w(TAG, "Cannot launch Android installer for $jobId", error)
                update(
                    jobId,
                    sourceForJob(jobId),
                    InstallState.FAILED,
                    packageName,
                    "Cannot open Android installer: ${error.message}",
                )
                deleteDownload(jobId)
            }
    }

    @Suppress("ComplexCondition", "CyclomaticComplexMethod")
    private fun isLocalAddress(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        val octets = address.address.map { it.toInt() and 0xff }
        return when (octets.size) {
            4 -> isBlockedIpv4(octets)
            16 -> isBlockedIpv6(octets)
            else -> true
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun isBlockedIpv4(octets: List<Int>): Boolean {
        val first = octets[0]
        val second = octets[1]
        val third = octets[2]
        return when (first) {
            0, 10, 127 -> true
            100 -> second in 64..127
            169 -> second == 254
            172 -> second in 16..31
            192 ->
                second == 168 ||
                    (second == 0 && (third == 0 || third == 2)) ||
                    (second == 88 && third == 99)
            198 -> second in 18..19 || (second == 51 && third == 100)
            203 -> second == 0 && third == 113
            else -> first in 224..255
        }
    }

    private fun isBlockedIpv6(octets: List<Int>): Boolean {
        val first = octets[0]
        val embeddedIpv4 =
            octets.take(10).all { it == 0 } &&
                (
                    (octets[10] == 0xff && octets[11] == 0xff) ||
                        (octets[10] == 0 && octets[11] == 0)
                )
        return (embeddedIpv4 && isBlockedIpv4(octets.takeLast(4))) ||
            first in 0xfc..0xfd ||
            (first == 0x20 && octets[1] == 0x01 && octets[2] == 0x0d && octets[3] == 0xb8)
    }

    private fun installDirectory(): File {
        val root = context.getExternalFilesDir(null) ?: context.cacheDir
        return File(root, "app-installs").apply { mkdirs() }
    }

    private fun cleanOldFiles() {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        installDirectory().listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }

    private fun deleteDownload(jobId: String) {
        installDirectory().listFiles()?.forEach { file ->
            if (file.name.startsWith("$jobId.apk")) file.delete()
        }
    }

    @Synchronized
    @Suppress("LongParameterList")
    private fun update(
        id: String,
        source: String,
        state: InstallState,
        packageName: String?,
        message: String,
        signingCertificateSha256: String? = null,
    ): InstallJobSnapshot {
        // PendingIntent callbacks may omit EXTRA_PACKAGE_NAME; never erase verified metadata.
        val effectivePackageName = packageName ?: jobs[id]?.packageName
        val effectiveSigner = signingCertificateSha256 ?: jobs[id]?.signingCertificateSha256
        val snapshot =
            InstallJobSnapshot(
                id = id,
                state = state,
                source = source,
                packageName = effectivePackageName,
                message = message,
                updatedAt = System.currentTimeMillis(),
                signingCertificateSha256 = effectiveSigner,
            )
        jobs[id] =
            Job(
                id = id,
                source = source,
                state = state,
                packageName = effectivePackageName,
                message = message,
                updatedAt = snapshot.updatedAt,
                signingCertificateSha256 = effectiveSigner,
            )
        return snapshot
    }

    private fun Job.toSnapshot(): InstallJobSnapshot =
        InstallJobSnapshot(
            id = id,
            state = state,
            source = source,
            packageName = packageName,
            message = message,
            updatedAt = updatedAt,
            signingCertificateSha256 = signingCertificateSha256,
        )

    @Synchronized
    private fun sourceForJob(jobId: String): String = jobs[jobId]?.source ?: "apk"

    private fun failedSnapshot(
        id: String,
        source: String,
        packageName: String?,
        message: String,
    ): InstallJobSnapshot = update(id, source, InstallState.FAILED, packageName, message)

    private fun contextOrThrow(): Context {
        check(::context.isInitialized) { "ApkInstaller.initialize must be called first" }
        return context
    }

    private fun safeMessage(error: Exception): String =
        error.message?.take(240)?.replace(Regex("[\\r\\n]+"), " ") ?: "APK installation failed"

    private fun ByteArray.toHexString(): String = joinToString("") { byte -> "%02x".format(byte) }

    @Suppress("TooGenericExceptionCaught")
    private inline fun CoroutineScope.launchSafely(
        jobId: String,
        packageName: String?,
        crossinline block: suspend () -> Unit,
    ) {
        launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                update(jobId, sourceForJob(jobId), InstallState.CANCELLED, packageName, "Installation cancelled")
                throw cancelled
            } catch (error: Exception) {
                update(jobId, sourceForJob(jobId), InstallState.FAILED, packageName, safeMessage(error))
                deleteDownload(jobId)
            }
        }
    }
}
