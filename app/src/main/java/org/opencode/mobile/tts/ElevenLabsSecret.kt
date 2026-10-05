package org.opencode.mobile.tts

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API-ключ ElevenLabs в зашифрованном виде: AES-256/GCM, ключ в Android Keystore.
 *
 * Тот же приём, что в ServerAuth для пароля локального сервера, и по той же
 * причине: ключ — полноценный доступ к облачному синтезу за счёт юзера, и в
 * обычных SharedPreferences (которые читает любой процесс приложения и видно в
 * дампах/бэкапах) ему не место.
 *
 * Ключ НИКОГДА не пишется в лог и не возвращается наружу из [load] — единственный
 * способ его увидеть в приложении, это [load] внутри движка синтеза.
 */
object ElevenLabsSecret {
    private const val TAG = "ElevenLabs"

    /** Отдельный prefs от "chat_overlay": туда ключ не попадает даже в бэкапе. */
    private const val PREFS = "opencode_tts_secrets"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "elevenlabs_api_key"

    /** Формат хранения: Base64(iv) + ":" + Base64(ciphertext). */
    private const val PREF_KEY = "xi_api_key.enc"

    /**
     * Отпечаток для UI: "<последние 4 символа>|<длина>". Пишется открытым текстом,
     * потому что это НЕ секрет — 4 символа из 48 случайных не подобрать, а
     * благодаря ему экран настроек показывает «ключ на месте» без расшифровки.
     * Раньше поле выглядело пустым, и пользователь не мог отличить «ключ сохранён»
     * от «ключ не введён» — приходилось гадать по логам.
     */
    private const val PREF_PREVIEW = "xi_api_key.preview"

    /**
     * Счётчик ревизий. Растёт при каждом save/clear.
     *
     * Нужен потому, что движок озвучки переиспользуется между ответами и решает
     * «пересоздавать ли себя» по строке-ключу. Без ревизии смена API-ключа при
     * неизменных engine/voice/model не давала бы нового ключа — движок продолжил
     * бы работать со старым (или вовсе без ключа), и озвучка молча осталась бы
     * прежней. Сам ключ в строку сравнения не попадает — только номер ревизии.
     */
    private const val PREF_REV = "xi_api_key.rev"

    /** Номер текущей ревизии ключа; растёт при каждом сохранении и очистке. */
    fun revision(context: Context): Long =
        prefs(context).getLong(PREF_REV, 0L)

    private const val AES_KEY_BITS = 256
    private const val GCM_TAG_BITS = 128

    /** Есть ли ключ (без расшифровки — достаточно факта наличия). */
    fun hasKey(context: Context): Boolean =
        prefs(context).getString(PREF_KEY, null).isNullOrBlank().not()

    /**
     * Человекочитаемый вид ключа для настроек, например «•••• 5a29 (51 симв.)».
     * Расшифровку не трогает. null, если ключ не задан.
     */
    fun preview(context: Context): String? {
        val cached = prefs(context).getString(PREF_PREVIEW, null)
        if (cached != null) {
            val parts = cached.split("|")
            if (parts.size == 2) {
                val len = parts[1].toIntOrNull()
                if (len != null) return "•••• ${parts[0]} ($len симв.)"
            }
        }
        // Ключ сохранён до того, как появился отпечаток: досчитываем один раз и
        // кэшируем, чтобы пользователю не пришлось вводить ключ заново. В лог
        // уходит только длина, сам ключ — нет.
        val key = load(context) ?: return null
        val filled = key.takeLast(4) + "|" + key.length
        prefs(context).edit().putString(PREF_PREVIEW, filled).commit()
        return "•••• ${key.takeLast(4)} (${key.length} симв.)"
    }

    /**
     * Ключ из хранилища; null, если его нет или он не расшифровался.
     *
     * null — это не ошибка: движок сам решает, что делать (откатиться на локальный
     * синтез), поэтому читатель не обязан разбираться с криптографией.
     */
    fun load(context: Context): String? {
        val enc = prefs(context).getString(PREF_KEY, null) ?: return null
        return try {
            val parts = enc.split(":")
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8).ifBlank { null }
        } catch (e: GeneralSecurityException) {
            // Ключ в Keystore переехал (смена профиля/устройства) или данные побиты.
            Log.w(TAG, "ключ не расшифровался (${e.javaClass.simpleName}) — нужен новый ввод")
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "битый формат сохранённого ключа: ${e.message}")
            null
        }
    }

    /**
     * Сохраняет ключ. [apiKey] с пробелами по краям обрезается: с телефона его
     * вставляют копипастом с переносом строки, и такой ключ ушёл бы в API как есть.
     */
    fun save(context: Context, apiKey: String) {
        val clean = apiKey.trim()
        if (clean.isEmpty()) {
            clear(context)
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ct = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        val enc = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
        // commit, а не apply: смена движка озвучки идёт сразу после сохранения,
        // ключ должен быть виден следующему чтению без гонки.
        prefs(context).edit()
            .putString(PREF_KEY, enc)
            .putString(PREF_PREVIEW, clean.takeLast(4) + "|" + clean.length)
            .putLong(PREF_REV, revision(context) + 1)
            .commit()
        Log.i(TAG, "ключ сохранён (${clean.length} симв.), расшифрованный вид не логируется")
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(PREF_KEY)
            .remove(PREF_PREVIEW)
            .putLong(PREF_REV, revision(context) + 1)
            .commit()
        Log.i(TAG, "ключ удалён")
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** AES-ключ в Keystore; создаётся лениво при первом сохранении. */
    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        builder.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        builder.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        builder.setKeySize(AES_KEY_BITS)

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(builder.build())
        return gen.generateKey()
    }
}