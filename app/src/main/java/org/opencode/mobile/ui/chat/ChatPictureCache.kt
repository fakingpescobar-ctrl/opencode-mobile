package org.opencode.mobile.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val CACHE_TAG = "ChatPicture"

/**
 * Декодированные картинки ленты.
 *
 * Лента пересобирается на каждом тике опроса (доли секунды), и без кэша четыре
 * референса декодировались бы по многу раз в секунду — на телефоне это читается
 * как «чат дёргается». Ключ включает размер и время правки: перезаписанный
 * файл читается заново, а не показывается старым.
 */
internal object ChatPictureCache {
    private const val CACHE_BYTES = 24 * 1024 * 1024
    private const val MAX_DECODE_WIDTH = 1600

    private val lru =
        object : LruCache<String, Bitmap>(CACHE_BYTES) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.byteCount
        }

    fun keyOf(file: File): String = "${file.path}|${file.length()}|${file.lastModified()}"

    fun peek(file: File): Bitmap? = lru.get(keyOf(file))

    suspend fun load(file: File): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = keyOf(file)
            val cached = lru.get(key)
            if (cached != null) return@withContext cached
            val decoded = decode(file)
            if (decoded != null) lru.put(key, decoded)
            decoded
        }

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(CACHE_TAG, "не разобрал размеры: ${file.path}")
            return null
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth) }
        return BitmapFactory.decodeFile(file.path, opts)
    }

    /** Уменьшение в 2^n раз: больше MAX_DECODE_WIDTH по ширине не нужно даже на планшете. */
    fun sampleSizeFor(width: Int): Int {
        var sample = 1
        while (width / sample > MAX_DECODE_WIDTH) sample *= 2
        return sample
    }
}
