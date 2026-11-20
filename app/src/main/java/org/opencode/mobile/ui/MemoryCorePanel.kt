@file:Suppress("MagicNumber")

package org.opencode.mobile.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencode.mobile.engine.GgufCatalog
import org.opencode.mobile.engine.GgufModel
import org.opencode.mobile.engine.GgufStore
import org.opencode.mobile.server.MemoryTools

/**
 * Единый пульт ядра памяти: одна карточка в диагностике, внутри — всё, что
 * относится к памяти. Раньше загрузчик GGUF и список записей были двумя
 * соседними секциями; человек искал «память», а находил «модели LLM» и не
 * понимал, где собственно управлять ядром. Теперь одна точка входа.
 *
 * Содержимое раскрывается вложенными [DiagSection], каждая из которых сама
 * запоминает открытое состояние — можно оставить открытой только нужную.
 * Ничего не хардкодим: список инструментов спрашиваем у живого сервера,
 * готовность моделей — из маркеров, статистику — из MCP-вызовов.
 */
@Composable
internal fun MemoryCorePanel(
    context: Context,
    refreshKey: Int,
    ggufState: GgufDownloadState,
    onGgufDownload: (GgufModel) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        InfoRow(
            "Что это",
            "Самодостаточное ядро: SQLite + FTS5, гибридный поиск, адаптивные " +
                "бюджеты, аудит и откат. LLM резидентна с старта: селектор и " +
                "переформулировщик, чанки выбирает код.",
        )

        DiagSection("Состояние ядра", DiagTone.IDLE) {
            CoreStatusSection(refreshKey)
        }
        DiagSection("LLM-сопроцессор", DiagTone.IDLE) {
            LlmSection(refreshKey)
        }
        DiagSection("Инструменты и вызовы", DiagTone.IDLE) {
            CoreToolsSection(refreshKey)
        }
        DiagSection("Модели LLM (GGUF)", DiagTone.IDLE) {
            GgufModelRows(context, refreshKey, ggufState, onGgufDownload)
        }
        DiagSection("Записи и удаление", DiagTone.IDLE) {
            memorySection()
        }
        DiagSection("Технические детали", DiagTone.IDLE) {
            CoreTechSection()
        }
    }
}

/** Живые счётчики индекса плюс интерактивная карта связей. */
@Composable
private fun CoreStatusSection(refreshKey: Int) {
    var out by remember { mutableStateOf("запрос…") }
    var edges by remember { mutableStateOf<List<org.opencode.mobile.server.MemoryWire.Edge>>(emptyList()) }
    var graphNote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun reload() {
        out = "запрос…"
        graphNote = null
        scope.launch {
            out =
                withContext(Dispatchers.IO) {
                    MemoryTools.coreStats().fold(
                        onSuccess = { compactJson(it) },
                        onFailure = { "недоступно: ${it.message}" },
                    )
                }
            val r = withContext(Dispatchers.IO) { MemoryTools.coreGraph() }
            edges = r.getOrElse { emptyList() }
            graphNote = r.exceptionOrNull()?.message
        }
    }
    LaunchedEffect(refreshKey) { reload() }
    InfoRow("Индекс", out)
    graphNote?.let { InfoRow("Граф", "недоступен: $it", Color(0xFFFF6F5A)) }
    MemoryGraphView(edges)
    downloadButton("Обновить состояние") { reload() }
}

/**
 * Резидентный LLM-сопроцессор: статус прогретой модели и ручная проверка
 * точечного поиска. В ответе всегда лежит детерминированный pack ядра —
 * это позволяет сравнить «было/стало» одним нажатием, не роняя агент.
 */
@Composable
private fun LlmSection(refreshKey: Int) {
    var status by remember { mutableStateOf("запрос…") }
    var searchOut by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun reload() {
        status = "запрос…"
        scope.launch {
            status =
                withContext(Dispatchers.IO) {
                    MemoryTools.llmStatus().fold(
                        onSuccess = { compactJson(it) },
                        onFailure = { "недоступно: ${it.message}" },
                    )
                }
        }
    }
    LaunchedEffect(refreshKey) { reload() }

    InfoRow("Модель", status)
    InfoRow("Когда вызывается", "только при низкой уверенности ядра; у агента " +
        "обычный путь — детерминированный pack за ~10 мс")
    downloadButton("Обновить статус LLM") { reload() }
    downloadButton("Проверить поиск (LLM + pack ядра)") {
        if (busy) return@downloadButton
        busy = true
        searchOut = "считаю…"
        scope.launch {
            searchOut =
                withContext(Dispatchers.IO) {
                    MemoryTools.llmSearch("куда агент пишет отчёт и как устроена память").fold(
                        onSuccess = { compactJson(it) },
                        onFailure = { "ошибка: ${it.message}" },
                    )
                }
            busy = false
        }
    }
    searchOut?.let { InfoRow("Поиск", it.take(PREVIEW) + if (it.length > PREVIEW) "…" else "") }
}

/** Список инструментов приходит из `tools/list`, а не из константы в Kotlin. */
@Composable
@Suppress("LongMethod")
private fun CoreToolsSection(refreshKey: Int) {
    var tools by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var packOut by remember { mutableStateOf<String?>(null) }
    var benchOut by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun reload() {
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { MemoryTools.coreTools() }
            tools = r.getOrElse { emptyList() }
            error = r.exceptionOrNull()?.message
        }
    }
    LaunchedEffect(refreshKey) { reload() }

    InfoRow("Всего memory_*", tools.size.toString(), if (tools.isEmpty()) Color(0xFFFF6F5A) else Color(0xFF7BD88F))
    if (tools.isEmpty() && error != null) {
        InfoRow("Ошибка", error.orEmpty(), Color(0xFFFF6F5A))
    } else {
        tools.forEach { InfoRow(it, "доступен", Color(0xFF7BD88F)) }
    }
    downloadButton("Обновить список") { reload() }

    downloadButton("Проверить пак (memory_pack, 1024 токена)") {
        if (busy) return@downloadButton
        busy = true
        packOut = "считаю…"
        scope.launch {
            packOut =
                withContext(Dispatchers.IO) {
                    MemoryTools.corePack("архитектура ядра памяти").fold(
                        onSuccess = { compactJson(it) },
                        onFailure = { "ошибка: ${it.message}" },
                    )
                }
            busy = false
        }
    }
    packOut?.let { InfoRow("Пак", it.take(PREVIEW) + if (it.length > PREVIEW) "…" else "") }

    downloadButton("Быстрый бенч (adaptive)") {
        if (busy) return@downloadButton
        busy = true
        benchOut = "считаю…"
        scope.launch {
            benchOut =
                withContext(Dispatchers.IO) {
                    MemoryTools.coreBench("adaptive").fold(
                        onSuccess = { compactJson(it) },
                        onFailure = { "ошибка: ${it.message}" },
                    )
                }
            busy = false
        }
    }
    benchOut?.let { InfoRow("Бенч", it.take(PREVIEW) + if (it.length > PREVIEW) "…" else "") }
}

/**
 * Список GGUF-моделей и кнопки скачивания. Готовность намеренно без хеширования
 * 400 МБ: [GgufStore.isReady] читает только маркер `.ok` и размер, а полный
 * sha256 считается один раз — сразу после закачки.
 */
@Composable
@Suppress("LongMethod")
private fun GgufModelRows(
    context: Context,
    refreshKey: Int,
    state: GgufDownloadState,
    onDownload: (GgufModel) -> Unit,
) {
    val models = remember { GgufCatalog.load(context) }
    val ready =
        remember(refreshKey, models) {
            models.associateWith { GgufStore.isReady(context, it) }
        }
    var selectedId by remember(refreshKey) { mutableStateOf(GgufStore.selectedId(context)) }
    var note by remember { mutableStateOf<String?>(null) }
    InfoRow("Зачем", "Семантический сопроцессор ядра: селектор точечного поиска и " +
        "переформулировка синонимов. Грузится при старте; чанки выбирает код, " +
        "LLM ядро не заменяет и память не трогает.")
    InfoRow("Каталог", "assets/llm/models.json (" + models.size + " моделей)")
    InfoRow("Источник", "Hugging Face · huggingface.co, точный URL и sha256 в манифесте")
    InfoRow("Рекомендована", GgufCatalog.preferred(), Color(0xFFFFC107))
    InfoRow(
        "Назначена",
        selectedId ?: "нет — выберите модель ниже",
        if (selectedId == null) Color(0xFFFF6F5A) else Color(0xFF7BD88F),
    )
    for (model in models) {
        val isReady = ready[model] == true
        val isSelected = model.id == selectedId
        val size = if (isReady) GgufStore.bytesOnDisk(context, model) else model.size
        InfoRow(
            model.id + if (isSelected) " ★" else "",
            (if (isReady) "готов · " else "нет · ") + fmtBytes(size) + " · " + model.repo + "/" + model.file,
            if (isReady) Color(0xFF7BD88F) else Color(0xFFFF6F5A),
        )
        InfoRow(
            "Назначение",
            model.purpose.ifBlank { "не указано" },
            if (isSelected) Color(0xFFFFC107) else Color(0xFF8A8A8A),
        )
        InfoRow(
            "Скорость",
            if (model.ttftMs > 0) {
                "TTFT " + model.ttftMs + " мс · " + model.decodeTs + " t/s · 24 токена " + model.total24Ms + " мс"
            } else {
                "не замеряно"
            },
        )
        InfoRow("Лицензия", model.license.ifBlank { "не указана" })
        InfoRow("Качество", model.quality.ifBlank { "не проверено" })
        if (isReady) {
            downloadButton(if (isSelected) "Назначена ✓" else "Назначить " + model.id) {
                runCatching { GgufStore.select(context, model) }
                    .onSuccess { selectedId = model.id; note = "Назначена: ${model.id}" }
                    .onFailure { note = "Не удалось назначить: ${it.message}" }
            }
        }
    }
    note?.let { InfoRow("Результат", it, Color(0xFF7BD88F)) }
    when (val dl = state) {
        is GgufDownloadState.Running -> {
            val model = GgufCatalog.byId(context, dl.model)
            InfoRow(
                "Загрузка " + dl.model,
                fmtBytes(dl.done) + " из " + fmtBytes(dl.total),
                Color(0xFFFFC107),
            )
            if (model != null) {
                InfoRow("Откуда", model.repo + "/" + model.file, Color(0xFFFFC107))
            }
        }

        is GgufDownloadState.Failed -> {
            InfoRow("Скачивание не удалось", dl.message, Color(0xFFFF6F5A))
            downloadButton("Повторить (докачка)") {
                GgufCatalog.byId(context, dl.model)?.let(onDownload)
            }
        }

        GgufDownloadState.Idle ->
            for (model in models) {
                if (ready[model] == true) continue
                downloadButton("Скачать " + model.id + " (~" + fmtBytes(model.size) + ")") {
                    onDownload(model)
                }
            }
    }
}

/** Не меняющиеся руками факты: пути, порт, whence модели. */
@Composable
private fun CoreTechSection() {
    InfoRow("Транспорт", "JSON-RPC 2.0, 127.0.0.1:4199/mcp, bearer из-environment")
    InfoRow("Каталог моделей", "assets/llm/models.json → " + GgufCatalog.preferred())
    InfoRow("Куда кладём GGUF", "files/musl/ рядом с libllama-bench.so, в APK модели нет")
    InfoRow("Выбор рантайма", "files/musl/active-model.json — читает сопроцессор, не UI")
    InfoRow("Безопасность", "sha256 + размер до маркера .ok; при расхождении файл удаляется")
    Text(
        "Управление записями выше использует MemoryTools: удаление настоящее, " +
            "поэтому перед ним всегда диалог подтверждения.",
        color = Color(0xFF8A8A8A),
        fontSize = 11.sp,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

/** JSON в одну строку для InfoRow, чтобы ответ MCP не ломал вёрстку. */
private fun compactJson(raw: String): String =
    raw
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .ifEmpty { "(пустой ответ)" }

private const val PREVIEW = 400
