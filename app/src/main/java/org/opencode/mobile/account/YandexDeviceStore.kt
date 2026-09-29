package org.opencode.mobile.account

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * Состояние device-flow: идентификатор устройства и код, ждущий подтверждения.
 *
 * Отдельный класс от [YandexTokenStore], хотя делят с ним один файл настроек. Разделение
 * по смыслу, а не по размеру: здесь лежит только то, что живёт минуты и пропадает после
 * входа, там — то, что живёт год. Смешанное в одном классе состояние читалось бы как
 * «токен готовится на 5 минут», что неправда, и однажды правкой одной строки убило бы
 * обновление токена.
 *
 * Имя файла совпадает намеренно: оба класса работают с одной парой ключей, и разводить их
 * по разным файлам означало бы либо второй файл, либо синхронизацию двух файлов.
 */
class YandexDeviceStore(
    context: Context,
) {
    /**
     * Заход device-flow в полёте.
     *
     * Живёт в хранилище, а не в памяти процесса: юзер уходит в браузер подтвердить вход,
     * процесс в это время умирает, и опрос без сохранённого `device_code` уже невозможен —
     * пришлось бы начинать заново и снова занимать юзера. [expiresAtMillis] хранится
     * готовым, а не через `startedAtMillis`, потому что срок жизни кода задаёт сервер.
     */
    data class PendingDeviceAuth(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val intervalSeconds: Int,
        val expiresAtMillis: Long,
    ) {
        fun expiredAt(nowMillis: Long): Boolean = nowMillis >= expiresAtMillis
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Идентификатор этого устройства в device-flow, один на установку.
     *
     * Постоянный, а не новый на каждый вход: Яндекс показывает его в списке активных
     * сессий, и меняющийся id превращался бы в россыпь незнакомых устройств, которые
     * юзеру пришлось бы по одной узнавать и отзывать.
     */
    fun deviceId(random: SecureRandom = SecureRandom()): String {
        val stored = prefs.getString(KEY_DEVICE_ID, null)?.trim().orEmpty()
        if (stored.isNotEmpty()) return stored
        val generated = YandexDeviceAuth.newDeviceId(random)
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    fun savePending(pending: PendingDeviceAuth) {
        prefs
            .edit()
            .putString(KEY_DEVICE_CODE, pending.deviceCode)
            .putString(KEY_DEVICE_USER_CODE, pending.userCode)
            .putString(KEY_DEVICE_URL, pending.verificationUrl)
            .putInt(KEY_DEVICE_INTERVAL, pending.intervalSeconds)
            .putLong(KEY_DEVICE_EXPIRES_AT, pending.expiresAtMillis)
            .apply()
    }

    fun pending(): PendingDeviceAuth? {
        val deviceCode = prefs.getString(KEY_DEVICE_CODE, null)?.trim().orEmpty()
        val userCode = prefs.getString(KEY_DEVICE_USER_CODE, null)?.trim().orEmpty()
        if (deviceCode.isEmpty() || userCode.isEmpty()) return null
        return PendingDeviceAuth(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUrl = prefs.getString(KEY_DEVICE_URL, null)?.trim().orEmpty(),
            intervalSeconds = prefs.getInt(KEY_DEVICE_INTERVAL, DEFAULT_INTERVAL_SECONDS),
            expiresAtMillis = prefs.getLong(KEY_DEVICE_EXPIRES_AT, 0L),
        )
    }

    /**
     * Заход закончен: коды больше не нужны.
     *
     * Только коды. [YandexTokenStore.clear] стирает всё, и вызывать его здесь означало бы
     * снести вместе с кодом только что полученный токен.
     */
    fun clearPending() {
        prefs
            .edit()
            .remove(KEY_DEVICE_CODE)
            .remove(KEY_DEVICE_USER_CODE)
            .remove(KEY_DEVICE_URL)
            .remove(KEY_DEVICE_INTERVAL)
            .remove(KEY_DEVICE_EXPIRES_AT)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "opencode_yandex_auth"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_CODE = "device_code"
        const val KEY_DEVICE_USER_CODE = "device_user_code"
        const val KEY_DEVICE_URL = "device_verification_url"
        const val KEY_DEVICE_INTERVAL = "device_interval"
        const val KEY_DEVICE_EXPIRES_AT = "device_expires_at"
        const val DEFAULT_INTERVAL_SECONDS = 5
    }
}
