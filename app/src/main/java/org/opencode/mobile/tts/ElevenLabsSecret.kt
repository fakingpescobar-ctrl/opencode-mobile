package org.opencode.mobile.tts

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
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

    /** Формат хранения: Base64(iv) + PREF_SEP + Base64(ciphertext). */
    private const val PREF_KEY = "xi_api_key.enc"
    private const val PREF_SEP = ":"
    private const val COLON_PARTS = 2

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
    private const val REV_STEP = 1L

    private const val AES_KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val CIPHER_TRANSFORM = "AES/GCM/NoPadding"

    /** Номер текущей ревизии ключа; растёт при каждом сохранении и очистке. */
    fun revision(context: Context): Long = prefs(context).getLong(PREF_REV, 0L)

    /** Есть ли ключ (без расшифровки — достаточно факта наличия). */
    fun hasKey(context: Context): Boolean = prefs(context).getString(PREF_KEY, null).isNullOrBlank().not()

    /**
     * Человекочитаемый вид ключа для настроек, например «•••• 5a29 (51 симв.)».
     * Расшифровку не трогает. null, если ключ не задан.
     */
    fun preview(context: Context): String? {
        val prefs = prefs(context)
        return prefs.getString(PREF_PREVIEW, null)?.let(Preview::fromCache)
            ?: load(context)?.let { key ->
                // Ключ сохранён до того, как появился отпечаток: досчитываем один раз
                // и кэшируем, чтобы пользователю не пришлось вводить ключ заново.
                // В лог уходит только длина, сам ключ — нет.
                prefs.edit().putString(PREF_PREVIEW, Preview.raw(key)).commit()
                Preview.render(key)
            }
    }

    /**
     * Отпечаток ключа: сырой вид для кэша и человеческий для UI.
     *
     * Отдельный класс, потому что это ровно два формата одного и того же
     * значения, и держать их в [ElevenLabsSecret] было нечем — всё остальное там
     * про криптографию.
     */
    private object Preview {
        private const val TAIL = 4
        private const val SEP = "|"
        private const val PARTS = 2

        /** Кэш: «5a29|51». */
        fun raw(key: String): String = key.takeLast(TAIL) + SEP + key.length

        /** UI: «•••• 5a29 (51 симв.)». */
        fun render(key: String): String = "•••• ${key.takeLast(TAIL)} (${key.length} симв.)"

        /** Кэш -> UI; мусорный кэш даёт null, чтобы досчитать по-настоящему. */
        fun fromCache(cached: String): String? {
            val parts = cached.split(SEP)
            val len = parts.getOrNull(PARTS - 1)?.toIntOrNull()
            return len?.let { "•••• ${parts.first()} ($len симв.)" }
        }
    }

    /**
     * Ключ из хранилища; null, если его нет или он не расшифровался.
     *
     * null — это не ошибка: движок сам решает, что делать (откатиться на локальный
     * синтез), поэтому читатель не обязан разбираться с криптографией.
     */
    fun load(context: Context): String? = prefs(context).getString(PREF_KEY, null)?.let(::decrypt)

    /**
     * Расшифровка [enc]. Любая неудача — это null, а не исключение наружу:
     * недоступный Keystore или битые данные не должны ронять озвучку.
     */
    private fun decrypt(enc: String): String? =
        try {
            val parts = enc.split(PREF_SEP)
            if (parts.size != COLON_PARTS) {
                Log.w(TAG, "битый формат сохранённого ключа: ${parts.size} частей вместо $COLON_PARTS")
                null
            } else {
                val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateKey(),
                    GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
                )
                String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
                    .ifBlank { null }
            }
        } catch (e: GeneralSecurityException) {
            // Ключ в Keystore переехал (смена профиля/устройства) или данные побиты.
            Log.w(TAG, "ключ не расшифровался (${e.javaClass.simpleName}) — нужен новый ввод")
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "битый формат сохранённого ключа: ${e.message}")
            null
        }

    /**
     * Итог сохранения ключа.
     *
     * Раньше [save] ничего не возвращала, и любая ошибка Keystore улетала в
     * обработчик клика в UI — то есть в крэш приложения посреди настройки.
     * Теперь вызывающий обязан отличить «сохранилось» от «не вышло» и показать
     * это юзеру, не роняя экран.
     */
    sealed interface SaveResult {
        /** Ключ зашифрован и лежит в хранилище. */
        object Ok : SaveResult

        /** Сохранить не вышло; [reason] — уже человеческим текстом для UI. */
        data class Failed(
            val reason: String,
        ) : SaveResult
    }

    /**
     * Сохраняет ключ. [apiKey] с пробелами по краям обрезается: с телефона его
     * вставляют копипастом с переносом строки, и такой ключ ушёл бы в API как есть.
     *
     * Пустая строка означает «удалить ключ» — так удобнее, чем отдельный [clear]
     * на пустом поле, и результат всё равно [SaveResult.Ok].
     */
    fun save(
        context: Context,
        apiKey: String,
    ): SaveResult {
        val clean = apiKey.trim()
        return if (clean.isEmpty()) {
            clear(context)
            SaveResult.Ok
        } else {
            encryptAndStore(context, clean)
        }
    }

    private fun encryptAndStore(
        context: Context,
        clean: String,
    ): SaveResult =
        try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ct = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
            val enc = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + PREF_SEP +
                Base64.encodeToString(ct, Base64.NO_WRAP)
            // commit, а не apply: смена движка озвучки идёт сразу после сохранения,
            // ключ должен быть виден следующему чтению без гонки.
            prefs(context)
                .edit()
                .putString(PREF_KEY, enc)
                .putString(PREF_PREVIEW, Preview.raw(clean))
                .putLong(PREF_REV, revision(context) + REV_STEP)
                .commit()
            Log.i(TAG, "ключ сохранён (${clean.length} симв.), расшифрованный вид не логируется")
            SaveResult.Ok
        } catch (e: GeneralSecurityException) {
            // Keystore недоступен: нет аппаратного бэкенда, ключ не создан или
            // устройство его не отдало. Ключ юзера при этом ещё в поле ввода —
            // поэтому подробность уходит в лог, а в UI короткая причина.
            Log.w(TAG, "Keystore отказал при сохранении (${e.javaClass.simpleName})", e)
            SaveResult.Failed("Keystore не дал ключ: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: ProviderException) {
            // Штатный отказ провайдера Keystore: провайдер не зарегистрирован, ключ
            // не создан или устройство его не отдало. Раньше это ловилось через
            // RuntimeException и называлось «не удалось зашифровать», хотя дело было
            // в хранилище.
            Log.w(TAG, "провайдер Keystore отказал (${e.javaClass.simpleName})", e)
            SaveResult.Failed("Keystore не дал ключ: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: IllegalStateException) {
            // Устройство без Keystore вообще, либо он заблокирован в этот момент.
            // OutOfMemory сюда НЕ попадает: это Error, а не RuntimeException. Ловить
            // нехватку памяти здесь незачем — если её не хватило на Base64, то на
            // восстановление приложения её не хватит тем более, и «мягкая» ошибка
            // лишь спрятала бы настоящую причину.
            Log.w(TAG, "Keystore недоступен (${e.javaClass.simpleName})", e)
            SaveResult.Failed("Keystore недоступен: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            // Битый ключ или негодный padding — ошибка данных, а не хранилища.
            Log.w(TAG, "не удалось зашифровать ключ (${e.message})", e)
            SaveResult.Failed("не удалось зашифровать: ${e.message ?: e.javaClass.simpleName}")
        }

    fun clear(context: Context) {
        prefs(context)
            .edit()
            .remove(PREF_KEY)
            .remove(PREF_PREVIEW)
            .putLong(PREF_REV, revision(context) + REV_STEP)
            .commit()
        Log.i(TAG, "ключ удалён")
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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
