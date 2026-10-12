package org.opencode.mobile.server

import android.util.Log
import org.json.JSONObject
import org.opencode.mobile.BuildConfig

/**
 * Доклад о версии локального opencode serve — проверка, что бинарь в APK и
 * сервер — одна и та же версия.
 *
 * Зачем это осталось после удаления поддержки 1.x. Раньше здесь был
 * переключатель путей: определил версию — выбрал контракт. Теперь путь один,
 * и определять нечего. Но сверка осталась: версия, зашитая в APK, и версия
 * реально запущенного сервера могут разойтись, и последствия тихие. Именно так
 * мобильный агент однажды ответил, что у него opencode 1.18, хотя в APK лежал
 * 2.0.27. Расхождение видно сразу в логе, до того как что-то сломается.
 *
 * ПОЧЕМУ ПРОВЕРЯЕТСЯ ТЕЛО, А НЕ КОД ОТВЕТА
 *
 * В v2 любой неизвестный путь отдаёт **200 + HTML SPA** (catch-all на
 * клиентском роутере), а v1 на тот же запрос отдаёт 404. Значит проверка
 * «200 = значит v2» объявила бы любой сервер версией 2. Поэтому критерий
 * только такой: **тело парсится как JSON и в нём непустое поле `version`**.
 * HTML не парсится и отсекается сам собой.
 *
 * Порт отдельной проблемы не создаёт: приложение всегда передаёт `--port`
 * явно (`OpencodeRuntime.startServe`), поэтому дефолтные 4096 у v1 и
 * 4099 у v2 не играют роли. Единственный источник правды о порте —
 * [org.opencode.mobile.OpencodeApp.ServerConfig.PORT].
 */
object ServerFlavorProbe {
    /** Путь, отдающий машинночитаемое описание сервера. */
    const val INFO_PATH = "/api/info"

    /** Тег лога, чтобы версия сервера была видна в одном месте логов. */
    private const val TAG = "ServerFlavor"

    /** Что удалось выяснить о сервере на порту. */
    data class Probe(
        /** Строка версии, если сервер её отдал, иначе null. */
        val version: String?,
    ) {
        companion object {
            /** Сервер не ответил или ответил непонятным: версию не выдумываем. */
            val UNKNOWN = Probe(null)
        }
    }

    /**
     * Спросить живой сервер на [port] и сверить с версией из APK.
     *
     * Любой отказ (сервер ещё поднимается, нет пароля, таймаут) даёт
     * [Probe.UNKNOWN] и НЕ бросает: приложение обязано стартовать в любом
     * случае. Молчание здесь лучше падения — остальная диагностика всё равно
     * идёт через API и покажет проблему внятнее.
     */
    fun report(port: Int): Probe {
        val probe = of(LocalOpenCodeClient.get(port, INFO_PATH))
        log(probe)
        return probe
    }

    /** Расхождение между APK и сервером — предупреждение, не ошибка. */
    private fun log(probe: Probe) {
        val seen = probe.version
        if (seen == null) {
            Log.w(TAG, "сервер не отдал версию, в APK ${BuildConfig.OPENCODE_VERSION}")
            return
        }
        if (seen == BuildConfig.OPENCODE_VERSION) {
            Log.i(TAG, "сервер $seen, совпадает с бинарём в APK")
            return
        }
        Log.w(
            TAG,
            "сервер $seen, а в APK ${BuildConfig.OPENCODE_VERSION} — расхождение, " +
                "приложение рассчитано на второй",
        )
    }

    /**
     * Разобрать уже полученное тело. Вынесено отдельно от [report],
     * чтобы правило проверялось тестами без сервера: именно здесь живёт
     * вся логика, а [report] только ходит по HTTP.
     */
    fun of(body: String?): Probe = Probe(versionOf(body))

    /**
     * Достать строку версии из тела или null, если это не JSON описание
     * сервера. Любое исключение разбора — это «не описание сервера», а не
     * повод поднимать исключение на живом пути.
     */
    @Suppress("SwallowedException") // Любой отказ разбора = «это не описание сервера».
    private fun versionOf(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            JSONObject(body).optString("version").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
