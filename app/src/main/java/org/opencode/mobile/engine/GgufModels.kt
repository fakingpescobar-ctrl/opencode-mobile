@file:Suppress("MagicNumber")

package org.opencode.mobile.engine

import android.content.Context
import org.json.JSONArray

/**
 * Одна GGUF-модель для семантического сопроцессора ядра памяти.
 *
 * Источник — `assets/llm/models.json` (копия `MOBILE_AGENT/tools/llama-bench/models.json`).
 * В APK лежит только этот файл с URL/размером/sha256 и характеристиками:
 * сами модели весят сотни мегабайт и скачиваются по кнопке в диагностике.
 */
data class GgufModel(
    val id: String,
    val repo: String,
    val file: String,
    val size: Long,
    val sha256: String,
    val url: String,
    /** Для чего модель: объяснения, резерв, эксперимент. */
    val purpose: String = "",
    /** Замер на нашем железе, мс до первого токена. */
    val ttftMs: Int = 0,
    /** Замер на нашем железе, токенов в секунду. */
    val decodeTs: Double = 0.0,
    /** Замер на 24 токенах, мс. */
    val total24Ms: Int = 0,
    /** Лицензия — пользователь видит её до назначения. */
    val license: String = "",
    /** Результат quality-gate: LLM не выбирает чанки, но качество надо показывать. */
    val quality: String = "",
)

/** Каталог моделей: читает ассет один раз и проверяет его перед использованием. */
object GgufCatalog {
    private const val ASSET = "llm/models.json"
    private const val PREFERRED = "qwen3-0.6b-q4km"
    private const val SHA_HEX = 64
    private val ID_RE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")

    @Volatile
    private var cached: List<GgufModel>? = null

    /** Модель, зафиксированная в `ВЫБОР_МОДЕЛИ_LLM.md` по latency и quality-gate. */
    fun preferred(): String = PREFERRED

    fun load(context: Context): List<GgufModel> =
        cached ?: parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })
            .also { cached = it }

    fun byId(
        context: Context,
        id: String,
    ): GgufModel? = load(context).firstOrNull { it.id == id }

    /**
     * Чистый парсер без Android — проверяется обычным JVM-тестом.
     *
     * Проверки здесь не «для красоты»: имя файла становится путём в filesDir,
     * id попадает в JSON назначенной модели, URL уходит в HttpURLConnection,
     * а sha256 решает, считать ли модель готовой. Подмена любого поля ассета
     * не должна привести к записи вне песочницы.
     */
    fun parse(json: String): List<GgufModel> {
        val arr = JSONArray(json)
        require(arr.length() > 0) { "models.json пуст" }
        val ids = HashSet<String>()
        val out = ArrayList<GgufModel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("id").trim()
            val file = o.getString("file").trim()
            val sha = o.getString("sha256").trim().lowercase()
            val url = o.getString("url").trim()
            val size = o.getLong("size")
            require(ID_RE.matches(id)) { "неверный id: $id" }
            require(ids.add(id)) { "дубль id: $id" }
            requireSafeName(file)
            require(size > 0L) { "неверный размер: $file" }
            require(sha.length == SHA_HEX && sha.all { it.isDigit() || it in 'a'..'f' }) {
                "неверный sha256: $file"
            }
            require(url.startsWith("https://")) { "не https URL: $file" }
            out.add(
                GgufModel(
                    id = id,
                    repo = o.getString("repo").trim(),
                    file = file,
                    size = size,
                    sha256 = sha,
                    url = url,
                    purpose = o.optString("purpose").trim(),
                    ttftMs = o.optInt("ttftMs", 0),
                    decodeTs = o.optDouble("decodeTs", 0.0),
                    total24Ms = o.optInt("total24Ms", 0),
                    license = o.optString("license").trim(),
                    quality = o.optString("quality").trim(),
                ),
            )
        }
        return out
    }

    private fun requireSafeName(file: String) {
        require(file.endsWith(".gguf", ignoreCase = true)) { "не gguf: $file" }
        require(
            !file.contains('/') && !file.contains('\\') &&
                file != "." && file != ".." && !file.contains('\u0000'),
        ) { "имя модели не должно содержать путь: $file" }
    }
}
