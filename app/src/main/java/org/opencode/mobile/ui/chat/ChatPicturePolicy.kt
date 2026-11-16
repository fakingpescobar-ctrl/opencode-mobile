package org.opencode.mobile.ui.chat

import android.util.Log
import java.io.File
import java.io.IOException

private const val POLICY_TAG = "ChatPicture"

/**
 * Где приложению разрешено брать картинки для ленты.
 *
 * Ограничение не косметическое: текст реплики может прийти из чужого источника
 * (Moltbook, чужой агент), и без списка корней строка
 * `[[pic:/data/user/0/…/moltkey]]` превратила бы ленту в просмотрщик приватных
 * файлов. Разрешены только рабочая папка агента и Download — там, где картинки
 * и так лежат открыто.
 */
internal object ChatPicturePolicy {
    private val ROOTS =
        listOf(
            "/storage/emulated/0/Documents/OpencodeTerminal",
            "/storage/emulated/0/Download",
        )
    private val EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    private const val MAX_BYTES = 16L * 1024 * 1024

    /** Канонические корни считаются один раз: resolve зовётся на каждой пересборке ленты. */
    private val ROOT_PATHS = ROOTS.mapNotNull { canonicalOrNull(it)?.path }

    fun resolve(path: String): File? =
        path
            .takeIf { looksLikePicturePath(it) }
            ?.let { canonicalOrNull(it) }
            ?.takeIf { allowed(it) }

    private fun allowed(file: File): Boolean =
        file.isFile &&
            file.extension.lowercase() in EXTENSIONS &&
            file.length() in 1..MAX_BYTES &&
            ROOT_PATHS.any { file.path.startsWith("$it/") }

    private fun canonicalOrNull(path: String): File? =
        try {
            File(path).canonicalFile
        } catch (e: IOException) {
            Log.w(POLICY_TAG, "путь не разрешился: $path (${e.message})")
            null
        }
}
