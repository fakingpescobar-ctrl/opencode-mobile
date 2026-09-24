package org.opencode.mobile.server

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.withLock

/**
 * Общая авторизация для HTTP-клиентов приложения, ходящих на локальный
 * opencode serve (порт 4096). После включения OPENCODE_SERVER_PASSWORD
 * serve отдаёт 401 на все запросы без заголовка Authorization, поэтому
 * каждый клиент (ChatOverlay, WebView и т.п.) должен слать Basic-заголовок.
 *
 * ЕДИНСТВЕННАЯ точка чтения/создания пароля — [ensurePassword] (load-or-create):
 *  - onCreate (WebView стартует раньше сервиса) и startServe (env serve)
 *    оба идут через неё, поэтому первый вызвавший генерирует пароль,
 *    остальные читают тот же — рассинхрон auth невозможен;
 *  - пароль хранится в SharedPreferences ЗАШИФРОВАННЫМ (AES-256/GCM,
 *    ключ — в Android Keystore, не экспортируется), а не plaintext;
 *  - legacy plaintext-ключ (до PR2) при первой же загрузке мигрирует
 *    в шифрованный и удаляется.
 */
object ServerAuth {
    /** Пароль локального сервера (24 hex-символа), null пока не инициализирован. */
    @Volatile
    var password: String? = null
        private set

    private val lock = ReentrantLock()

    private const val TAG = "ServerAuth"
    private const val PREFS_NAME = "opencode_server"
    private const val KEYSTORE_NAME = "AndroidKeyStore"
    private const val KEY_ALIAS = "opencode_server_pwd"

    /** Шифрованный пароль: Base64(iv) + ":" + Base64(ct). */
    private const val PREFS_PWD_KEY = "password.enc"

    /** Legacy plaintext-ключ (до Keystore-шифрования) — мигрирует и удаляется. */
    private const val LEGACY_PWD_KEY = "password"
    private const val PWD_LEN = 24
    private const val AES_KEY_BITS = 256
    private const val GCM_TAG_BITS = 128

    /**
     * Гарантирует актуальный пароль: кэшированный → расшифрованный из
     * стора → миграция legacy → генерация нового. Синхронна (AES+commit)
     * и идемпотентна: все вызывающие получают ОДИН и тот же пароль.
     */
    fun ensurePassword(context: Context): String =
        lock.withLock {
            password ?: loadOrCreate(context).also { password = it }
        }

    private fun loadOrCreate(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enc = prefs.getString(PREFS_PWD_KEY, null)
        if (enc != null) {
            decryptPassword(enc)?.let { return it }
            Log.w(TAG, "не удалось расшифровать пароль (ключ/данные повреждены) — генерим новый")
        }

        val legacy = prefs.getString(LEGACY_PWD_KEY, null)
        val pwd = legacy ?: generatePassword()
        storeEncrypted(prefs, pwd, dropLegacy = legacy != null)
        return pwd
    }

    /** true, когда пароль известен и клиент может слать авторизацию. */
    val available: Boolean
        get() = password != null

    /**
     * Готовый заголовок Authorization для HttpURLConnection:
     * Basic base64("opencode:" + password). Логин по умолчанию opencode
     * (см. middleware.ts и server.ts в opencode-src).
     */
    fun basicHeader(): String? {
        val pwd = password ?: return null
        val raw = "opencode:" + pwd
        return "Basic " + Base64.encodeToString(
            raw.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
    }

    private fun generatePassword(): String =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(PWD_LEN)

    /** Шифрует пароль в prefs одним атомарным commit (put + опц. remove legacy). */
    private fun storeEncrypted(
        prefs: android.content.SharedPreferences,
        pwd: String,
        dropLegacy: Boolean,
    ) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ct = cipher.doFinal(pwd.toByteArray(Charsets.UTF_8))
        val enc = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
        val editor = prefs.edit().putString(PREFS_PWD_KEY, enc)
        if (dropLegacy) editor.remove(LEGACY_PWD_KEY)
        // commit (не apply): пароль критичный — serve и WebView стартуют сразу
        // после записи, async-запись не гарантирует видимость из другого процесса.
        editor.commit()
    }

    /** Расшифровка "iv:ct"; null при повреждении (тогда пароль регенерируется). */
    private fun decryptPassword(enc: String): String? {
        return try {
            val parts = enc.split(":")
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "расшифровка пароля: ${e.javaClass.simpleName}: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "расшифровка пароля: битый формат: ${e.message}")
            null
        }
    }

    /** AES-ключ из Keystore (создаётся лениво при первом использовании). */
    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_NAME).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        // AES-256/GCM, только encrypt/decrypt; без user-auth-привязки (биометрии):
        // пароль нужен фоновым компонентам (сервис, WebView) без диалога.
        val builder =
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
        builder.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        builder.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        builder.setKeySize(AES_KEY_BITS)

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_NAME)
        gen.init(builder.build())
        return gen.generateKey()
    }
}
