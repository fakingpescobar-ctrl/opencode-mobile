package org.opencode.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencode.mobile.server.MemoryTools
import org.opencode.mobile.server.MemoryWire
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Память агента с человеческой стороны: что именно он запомнил и кнопка
// «забыть» на каждой записи.
//
// Зачем это в приложении, а не только у модели. Память копится сама, и без
// просмотра человек не знает, что там лежит; а неверную запись он до сих пор
// мог убрать только уговорив модель вызвать инструмент. Запись, которую нельзя
// удалить руками, - это не память, а мусор, который никто не чистит.
//
// Удаление - настоящее (local_memory_forget стирает строку), поэтому перед ним
// всегда стоит подтверждение с текстом записи: отменить это нельзя, а «забыть»
// рядом с каждой строкой легко нажать случайно.

/** Состояние секции: загрузка, готовый список или внятная причина отказа. */
private sealed interface MemoryState {
    data object Loading : MemoryState

    data class Ready(
        val stats: MemoryWire.Stats?,
        val records: List<MemoryWire.Record>,
    ) : MemoryState

    data class Failed(
        val message: String,
    ) : MemoryState
}

@Composable
internal fun memorySection() {
    var state by remember { mutableStateOf<MemoryState>(MemoryState.Loading) }
    var target by remember { mutableStateOf<MemoryWire.Record?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        if (busy) return
        busy = true
        scope.launch {
            state = withContext(Dispatchers.IO) { loadMemory() }
            busy = false
        }
    }

    fun forget(record: MemoryWire.Record) {
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { MemoryTools.forget(record.id) }
            note =
                result.fold(
                    onSuccess = { "Запись удалена: ${record.id}" },
                    onFailure = { "Не удалось удалить: ${it.message}" },
                )
            state = withContext(Dispatchers.IO) { loadMemory() }
            busy = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    sectionHeader("Локальная память (что агент помнит)")
    InfoRow(
        "Что это",
        "Записи, которые агент сохраняет между сессиями: решения, найденные причины, " +
            "правила проекта. Здесь же их можно забыть - удаление настоящее.",
    )
    when (val current = state) {
        is MemoryState.Loading -> InfoRow("Записи", "читаю…", Color(0xFFFFC107))
        is MemoryState.Failed -> InfoRow("Записи", current.message, Color(0xFFFF6F5A))
        is MemoryState.Ready -> {
            val stats = current.stats
            if (stats != null) {
                InfoRow("Всего", "${stats.memories} · связей в графе ${stats.edges} · вытеснено ${stats.superseded}")
                InfoRow("Доверие", "свои/оператор ${stats.autoApplyable} · требует человека ${stats.needsHuman}")
            }
            if (current.records.isEmpty()) {
                InfoRow("Записи", "память пуста")
            }
            current.records.forEach { record ->
                MemoryRow(record, enabled = !busy) { target = record }
            }
        }
    }
    note?.let { InfoRow("Результат", it, Color(0xFF7BD88F)) }
    downloadButton(if (busy) "Читаю…" else "Обновить список") { reload() }

    target?.let { record ->
        ForgetDialog(
            record = record,
            onConfirm = {
                target = null
                forget(record)
            },
            onDismiss = { target = null },
        )
    }
}

/** Одна запись: текст, служебная строка и «забыть» справа. */
@Composable
private fun MemoryRow(
    record: MemoryWire.Record,
    enabled: Boolean,
    onForget: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                record.content.ifBlank { "(пустая запись)" },
                color = Color(0xFFE6E6E6),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                memoryMeta(record),
                color = Color(0xFF8A8A8A),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        TextButton(onClick = onForget, enabled = enabled) {
            Text("Забыть", color = Color(0xFFFF6F5A), fontSize = 11.sp)
        }
    }
}

@Composable
private fun ForgetDialog(
    record: MemoryWire.Record,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Забыть запись?") },
        text = {
            Text(
                "Удалится навсегда, восстановить нельзя:\n\n" +
                    record.content.take(CONFIRM_PREVIEW_CHARS) +
                    if (record.content.length > CONFIRM_PREVIEW_CHARS) "…" else "",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Забыть") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Читает список и счётчики. Счётчики необязательны: без них список полезен. */
private fun loadMemory(): MemoryState =
    MemoryTools.list().fold(
        onSuccess = { MemoryState.Ready(MemoryTools.stats().getOrNull(), it) },
        onFailure = { MemoryState.Failed(it.message ?: "память не отвечает") },
    )

/** Служебная строка записи: тип, проект, доверие, дата. Пустые поля не печатаем. */
private fun memoryMeta(record: MemoryWire.Record): String {
    val parts = mutableListOf<String>()
    if (record.type.isNotBlank()) parts += record.type
    if (record.project.isNotBlank()) parts += record.project
    if (record.trust.isNotBlank()) parts += "доверие: ${record.trust}"
    if (record.superseded) parts += "вытеснена"
    if (record.created > 0) parts += MEMORY_STAMP.format(Date(record.created))
    return parts.joinToString(" · ").ifBlank { record.id }
}

private const val CONFIRM_PREVIEW_CHARS = 300
private val MEMORY_STAMP = SimpleDateFormat("dd.MM HH:mm", Locale.ROOT)
