package org.opencode.mobile.server

import org.json.JSONObject

/**
 * Определение версии локального opencode serve.
 *
 * Зачем: один APK должен уметь работать и с 1.18.25, и с 2.0.27, иначе
 * нечем откатываться. Пути у них разные (см. [ApiPaths]), поэтому перед
 * первым обращением к серверу надо понять, кто перед нами.
 *
 * ПОЧЕМУ НЕЛЬЗЯ ПРОСТО ПОМЕРИТЬ КОД ОТВЕТА
 *
 * В v2 любой неизвестный путь отдаёт **200 + HTML SPA** (catch-all на
 * клиентском роутере), а v1 на тот же запрос отдаёт 404. Значит проверка
 * «200 = значит v2» объявила бы любой сервер версией 2 — и дальше все
 * запросы уехали бы в `/api/…` на сервер, который их не понимает.
 * Поэтому критерий только такой: **тело парсится как JSON и в нём поле
 * `version` вида `2.x`**. HTML не парсится и отсекается сам собой.
 *
 * Порт отдельной проблемы не создаёт: приложение всегда передаёт `--port`
 * явно (`OpencodeRuntime.startServe`), поэтому дефолтные 4096 у v1 и
 * 4099 у v2 не играют роли. Единственный источник правды о порте —
 * [org.opencode.mobile.OpencodeApp.ServerConfig.PORT].
 */
object ServerFlavorProbe {
    /** Путь, который в v2 отдаёт машинночитаемое описание сервера. */
    const val INFO_PATH = "/api/info"

    /** Префикс версии, по которому сервер считается v2. */
    private const val V2_PREFIX = "2."

    /** Тег лога, чтобы версия сервера была видна в одном месте логов. */
    private const val TAG = "ServerFlavor"

    /** Что удалось выяснить о сервере на порту. */
    data class Probe(
        /** Версия для [ApiPaths.install]. */
        val flavor: ServerFlavor,
        /** Строка версии, если сервер её отдал, иначе null. */
        val version: String?,
    ) {
        companion object {
            /** Сервер не ответил или ответил непонятным: считаем v1 и не выдумываем версию. */
            val UNKNOWN = Probe(ServerFlavor.V1, null)
        }
    }

/**
     * Спросить живой сервер на [port].
     *
     * Любой отказ (сервер ещё поднимается, нет пароля, таймаут) даёт
     * [Probe.UNKNOWN]: вызывающий обязан продолжить работу, а не упасть —
     * флаг версии не должен быть причиной не запустить приложение.
     */
    fun detect(port: Int): Probe = of(LocalOpenCodeClient.get(port, INFO_PATH))

    /**
     * Определить версию и сразу переключить [ApiPaths] на неё.
     *
     * Вызывается один раз на старте, когда сервер уже ответил на HTTP, но
     * ещё не обслуживал ни одного запроса приложения. Все [ApiPaths.active]
     * читают установленный флаг, поэтому важно сделать это ДО первого
     * обращения к сессиям, а не лениво при первом вызове.
     */
    fun detectAndInstall(port: Int): Probe {
        val probe = detect(port)
        ApiPaths.install(probe.flavor)
        android.util.Log.i(TAG, "сервер ${probe.version ?: "без версии"} -> ${probe.flavor}")
        return probe
    }

    /**
     * Разобрать уже полученное тело. Вынесено отдельно от [detect],
     * чтобы правило проверялось тестами без сервера: именно здесь живёт
     * вся логика, а [detect] только ходит по HTTP.
     */
    fun of(body: String?): Probe {
        val version = versionOf(body) ?: return Probe.UNKNOWN
        return Probe(if (version.startsWith(V2_PREFIX)) ServerFlavor.V2 else ServerFlavor.V1, version)
    }

    /**
     * Достать строку версии из тела или null, если это не JSON описание
     * сервера. Любое исключение разбора — это «не v2» (SPA, мусор, пустое
     * тело), а не повод поднимать исключение на живом пути.
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
