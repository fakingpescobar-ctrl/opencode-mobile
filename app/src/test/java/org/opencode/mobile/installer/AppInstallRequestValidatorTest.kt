package org.opencode.mobile.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppInstallRequestValidatorTest {
    @Test
    fun `play request accepts package and search fallback`() {
        val byPackage = AppInstallRequestValidator.play("com.example.app", null)
        val byQuery = AppInstallRequestValidator.play(null, "Telegram")

        assertEquals("com.example.app", byPackage.packageName)
        assertEquals("Telegram", byQuery.query)
    }

    @Test
    fun `play request rejects malformed package`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppInstallRequestValidator.play("not a package", null)
        }
    }

    @Test
    fun `apk request normalizes trusted hash and signer`() {
        val spec =
            AppInstallRequestValidator.apk(
                url = "https://f-droid.org/repo/app.apk",
                sha256 = "A".repeat(64),
                sizeBytes = 1234,
                expectedPackageName = "com.example.app",
                source = "fdroid",
                signingCertificateSha256 = "B".repeat(64),
            )

        assertEquals("a".repeat(64), spec.sha256)
        assertEquals(1234L, spec.sizeBytes)
        assertEquals(TrustedApkSource.FDROID, spec.source)
        assertEquals("b".repeat(64), spec.signingCertificateSha256)
    }

    @Test
    fun `local apk request normalizes path, hash, and signer`() {
        val spec =
            AppInstallRequestValidator.local(
                path = "/storage/emulated/0/Download/SberbankOnline.apk",
                sha256 = "A".repeat(64),
                sizeBytes = 175077993,
                expectedPackageName = "ru.sberbankmobile",
                signingCertificateSha256 = "B".repeat(64),
            )

        assertEquals("/storage/emulated/0/Download/SberbankOnline.apk", spec.path)
        assertEquals("a".repeat(64), spec.sha256)
        assertEquals(175077993L, spec.sizeBytes)
        assertEquals("ru.sberbankmobile", spec.packageName)
        assertEquals("b".repeat(64), spec.signingCertificateSha256)
    }

    @Test
    fun `local apk request rejects unsafe paths and sizes`() {
        listOf(
            "Download/app.apk",
            "/storage/emulated/0/Download/app.txt",
            "/storage/emulated/0/Download/../private.apk",
            "/storage/emulated/0/Download/app\n.apk",
        ).forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                AppInstallRequestValidator.local(path, "a".repeat(64), 1, null)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppInstallRequestValidator.local(
                "/storage/emulated/0/Download/app.apk",
                "a".repeat(64),
                0,
                null,
            )
        }
    }

    @Test
    fun `apk request rejects untrusted host and source mismatch`() {
        listOf(
            "https://example.com/app.apk",
            "https://f-droid.org.evil.example/app.apk",
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                AppInstallRequestValidator.apk(
                    url,
                    "a".repeat(64),
                    null,
                    null,
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppInstallRequestValidator.apk(
                "https://f-droid.org/repo/app.apk",
                "a".repeat(64),
                null,
                null,
                source = "github",
            )
        }
    }

    @Test
    fun `apk request rejects insecure or local URLs`() {
        listOf(
            "http://example.com/app.apk",
            "https://user:pass@example.com/app.apk",
            "https://127.0.0.1/app.apk",
            "https://192.168.1.20/app.apk",
            "https://localhost/app.apk",
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                AppInstallRequestValidator.apk(url, "a".repeat(64), null, null)
            }
        }
    }

    @Test
    fun `apk request requires exact sha and bounded size`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppInstallRequestValidator.apk("https://f-droid.org/repo/app.apk", "abc", null, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppInstallRequestValidator.apk(
                "https://f-droid.org/repo/app.apk",
                "a".repeat(64),
                AppInstallRequestValidator.MAX_APK_BYTES + 1,
                null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppInstallRequestValidator.apk(
                "https://f-droid.org/repo/app.apk",
                "a".repeat(64),
                null,
                null,
                signingCertificateSha256 = "not-a-certificate",
            )
        }
    }
}
