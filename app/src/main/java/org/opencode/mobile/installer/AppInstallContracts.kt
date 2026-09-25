package org.opencode.mobile.installer

import java.net.URI
import java.util.Locale

/** Источник запроса, который модель передаёт Android-мосту. */
sealed interface AppInstallSpec {
    val packageName: String?
}

/** Открытие официальной карточки/поиска Google Play. */
data class PlayInstallSpec(
    override val packageName: String?,
    val query: String?,
) : AppInstallSpec

/** APK, скачиваемый из HTTPS и предъявляемый системному PackageInstaller. */
data class ApkInstallSpec(
    val url: String,
    val sha256: String,
    val sizeBytes: Long?,
    override val packageName: String?,
    val source: TrustedApkSource,
    val signingCertificateSha256: String?,
) : AppInstallSpec

/** APK, уже скачанный в пользовательскую папку Downloads. */
data class LocalApkInstallSpec(
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
    override val packageName: String?,
    val signingCertificateSha256: String?,
) : AppInstallSpec

/** Источник APK, который Android-мост принимает для автоматической загрузки. */
enum class TrustedApkSource(
    val wireName: String,
) {
    FDROID("f_droid"),
    GITHUB("github"),
    GITLAB("gitlab"),
}

private fun trustedApkSourceFromWireName(value: String): TrustedApkSource? =
    when (value.trim().lowercase(Locale.ROOT)) {
        "f_droid", "f-droid", "fdroid" -> TrustedApkSource.FDROID
        "github" -> TrustedApkSource.GITHUB
        "gitlab" -> TrustedApkSource.GITLAB
        else -> null
    }

enum class InstallState(
    val wireName: String,
    val terminal: Boolean,
) {
    QUEUED("queued", false),
    STORE_OPENED("store_opened", true),
    DOWNLOADING("downloading", false),
    VERIFYING("verifying", false),
    READY_TO_INSTALL("ready_to_install", false),
    AWAITING_USER("awaiting_user", false),
    INSTALLING("installing", false),
    INSTALLED("installed", true),
    FAILED("failed", true),
    CANCELLED("cancelled", true),
}

data class InstallJobSnapshot(
    val id: String,
    val state: InstallState,
    val source: String,
    val packageName: String?,
    val message: String,
    val updatedAt: Long,
    val signingCertificateSha256: String? = null,
)

/**
 * Валидатор команд от локального MCP. Android-мост дополнительно проверяет
 * DNS-адреса уже при скачивании, но границы URL/пакета/хэша задаются здесь.
 */
@Suppress("MagicNumber")
object AppInstallRequestValidator {
    const val MAX_APK_BYTES: Long = 2L * 1024 * 1024 * 1024
    private const val MAX_URL_LENGTH = 2048
    private const val MAX_LOCAL_PATH_LENGTH = 4096
    private const val MAX_QUERY_LENGTH = 120
    private const val MAX_PACKAGE_LENGTH = 255
    private val PACKAGE_PART = Regex("[A-Za-z][A-Za-z0-9_]*")
    private val SHA256 = Regex("[A-Fa-f0-9]{64}")
    private val ALWAYS_BLOCKED_FIRST_OCTETS = setOf(0, 10, 127)
    private val HIGH_RESERVED_FIRST_OCTETS = 224..255
    private val BLOCKED_SECOND_OCTETS =
        mapOf(
            100 to (64..127).toSet(),
            169 to setOf(254),
            172 to (16..31).toSet(),
            192 to setOf(168),
        )

    fun play(
        packageName: String?,
        query: String?,
    ): PlayInstallSpec {
        val cleanPackage = packageName?.trim()?.takeIf { it.isNotEmpty() }
        val cleanQuery = query?.trim()?.takeIf { it.isNotEmpty() }
        require(cleanPackage != null || cleanQuery != null) {
            "Play Market request needs package or query"
        }
        cleanPackage?.let(::validatePackageName)
        cleanQuery?.let {
            require(it.length <= MAX_QUERY_LENGTH) { "Play search query is too long" }
            require(it.none { char -> char.isISOControl() }) { "Play search query contains control characters" }
        }
        return PlayInstallSpec(cleanPackage, cleanQuery)
    }

    @Suppress("LongParameterList")
    fun apk(
        url: String,
        sha256: String,
        sizeBytes: Long?,
        expectedPackageName: String?,
        source: String? = null,
        signingCertificateSha256: String? = null,
    ): ApkInstallSpec {
        val trustedSource = validateTrustedApkUrl(url, source)
        val cleanHash = sha256.trim().lowercase(Locale.ROOT)
        require(SHA256.matches(cleanHash)) { "sha256 must contain exactly 64 hexadecimal characters" }
        require(sizeBytes == null || sizeBytes in 1..MAX_APK_BYTES) {
            "size_bytes must be between 1 and $MAX_APK_BYTES"
        }
        val cleanPackage = expectedPackageName?.trim()?.takeIf { it.isNotEmpty() }
        cleanPackage?.let(::validatePackageName)
        val cleanSigner =
            signingCertificateSha256?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        require(cleanSigner == null || SHA256.matches(cleanSigner)) {
            "signing_certificate_sha256 must contain exactly 64 hexadecimal characters"
        }
        return ApkInstallSpec(
            url = url.trim(),
            sha256 = cleanHash,
            sizeBytes = sizeBytes,
            packageName = cleanPackage,
            source = trustedSource,
            signingCertificateSha256 = cleanSigner,
        )
    }

    @Suppress("LongParameterList")
    fun local(
        path: String,
        sha256: String,
        sizeBytes: Long,
        expectedPackageName: String?,
        signingCertificateSha256: String? = null,
    ): LocalApkInstallSpec {
        val cleanPath = path.trim()
        require(cleanPath.length in 1..MAX_LOCAL_PATH_LENGTH) { "local APK path has invalid length" }
        require(cleanPath.startsWith('/')) { "local APK path must be absolute" }
        require(cleanPath.endsWith(".apk", ignoreCase = true)) { "local APK path must end with .apk" }
        require(cleanPath.none { it == '\u0000' || it.isISOControl() }) {
            "local APK path contains control characters"
        }
        require(cleanPath.split('/').none { it == ".." }) { "local APK path must not traverse directories" }
        val cleanHash = sha256.trim().lowercase(Locale.ROOT)
        require(SHA256.matches(cleanHash)) { "sha256 must contain exactly 64 hexadecimal characters" }
        require(sizeBytes in 1..MAX_APK_BYTES) {
            "size_bytes must be between 1 and $MAX_APK_BYTES"
        }
        val cleanPackage = expectedPackageName?.trim()?.takeIf { it.isNotEmpty() }
        cleanPackage?.let(::validatePackageName)
        val cleanSigner =
            signingCertificateSha256?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        require(cleanSigner == null || SHA256.matches(cleanSigner)) {
            "signing_certificate_sha256 must contain exactly 64 hexadecimal characters"
        }
        return LocalApkInstallSpec(
            path = cleanPath,
            sha256 = cleanHash,
            sizeBytes = sizeBytes,
            packageName = cleanPackage,
            signingCertificateSha256 = cleanSigner,
        )
    }

    fun validatePackageName(packageName: String) {
        require(packageName.length <= MAX_PACKAGE_LENGTH) { "package name is too long" }
        val parts = packageName.split('.')
        require(parts.size >= 2 && parts.all { PACKAGE_PART.matches(it) }) {
            "invalid Android package name"
        }
    }

    fun validateHttpsUrl(value: String) {
        parseHttpsUrl(value)
    }

    fun validateTrustedApkUrl(
        value: String,
        expectedSource: String? = null,
    ): TrustedApkSource {
        val host = parseHttpsUrl(value).host!!.lowercase(Locale.ROOT)
        val detectedSource =
            when {
                host == "f-droid.org" || host.endsWith(".f-droid.org") -> TrustedApkSource.FDROID
                host == "github.com" || host.endsWith(".github.com") -> TrustedApkSource.GITHUB
                host == "githubusercontent.com" || host.endsWith(".githubusercontent.com") ->
                    TrustedApkSource.GITHUB
                host == "gitlab.com" || host.endsWith(".gitlab.com") -> TrustedApkSource.GITLAB
                host == "gitlabusercontent.com" || host.endsWith(".gitlabusercontent.com") ->
                    TrustedApkSource.GITLAB
                else -> null
            }
        require(detectedSource != null) {
            "APK URL host is not in the trusted source allowlist"
        }
        if (!expectedSource.isNullOrBlank()) {
            val requestedSource = trustedApkSourceFromWireName(expectedSource)
            require(requestedSource == detectedSource) {
                "APK source does not match the trusted URL host"
            }
        }
        return detectedSource
    }

    private fun parseHttpsUrl(value: String): URI {
        val raw = value.trim()
        require(raw.length in 1..MAX_URL_LENGTH) { "APK URL has invalid length" }
        val uri =
            runCatching { URI(raw) }.getOrElse {
                throw IllegalArgumentException("invalid APK URL")
            }
        require(uri.scheme.equals("https", ignoreCase = true)) { "APK URL must use HTTPS" }
        require(uri.userInfo == null) { "APK URL must not contain credentials" }
        require(uri.host != null && uri.host.isNotBlank()) { "APK URL must contain a host" }
        require(uri.fragment == null) { "APK URL must not contain a fragment" }
        val host = uri.host.lowercase(Locale.ROOT)
        require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")) {
            "APK URL must not target a local host"
        }
        require(!isPrivateIpv4(host)) { "APK URL must not target a private IP address" }
        require(!host.contains(':')) { "IPv6 APK URLs are not supported" }
        return uri
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.')
        val octets = parts.map { it.toIntOrNull() ?: -1 }
        if (parts.size != 4 || octets.any { it !in 0..255 }) return false
        val first = octets[0]
        val second = octets[1]
        val blockedSecond = BLOCKED_SECOND_OCTETS[first]
        return first in ALWAYS_BLOCKED_FIRST_OCTETS ||
            first in HIGH_RESERVED_FIRST_OCTETS ||
            blockedSecond?.contains(second) == true
    }
}
