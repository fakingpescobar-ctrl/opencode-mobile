package org.opencode.mobile.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencode.mobile.OpencodeApp
import org.opencode.mobile.server.OpencodeServerService
import org.opencode.mobile.server.Workspace
import org.opencode.mobile.server.WorkspacePath
import org.opencode.mobile.server.WorkspacePrefs

/** Цвет отказа: тот же, что у остальных «проблема» на экране. */
private const val WORKSPACE_BAD_ARGB = 0xFFFF6F5AL

/**
 * Секция «Рабочая папка»: где модель работает и как это изменить.
 *
 * Рабочая папка — единственное, что пользователь выбирает сам; всё остальное
 * (дом opencode, память, журнал Молтбука) живёт по фиксированному пути и
 * переживает и смену папки, и переустановку APK. Поэтому здесь показывается и
 * «сейчас» (реальный cwd процесса serve), и «выбрана» (настройка): если они
 * разошлись, это видно, а не выглядит как «настройка не сохранилась».
 */
@Composable
internal fun WorkspaceCard(
    serverPath: String,
    serverProblem: String?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Ключ по serverPath: после применения настройки сервис перезапускается и
    // публикует новый путь, а значит «выбрана» перечитывается, а не остаётся
    // снимком, сделанным при открытии экрана.
    val saved = remember(serverPath) { WorkspacePrefs.saved(context) }
    // Путь по умолчанию считается один раз: defaultDir при отсутствии прав
    // создаёт каталог в песочнице, а делать это на каждой рекомпозиции незачем.
    val fallback = remember { Workspace.defaultDir(context).absolutePath }
    val shown = serverPath.ifBlank { saved ?: fallback }
    var edit by remember { mutableStateOf<WorkspaceEditState?>(null) }

    DiagSection("Рабочая папка", if (serverProblem == null) DiagTone.OK else DiagTone.WARN) {
        InfoRow("Сейчас", shown)
        InfoRow("Выбрана", saved ?: "по умолчанию")
        InfoRow("Дом opencode", OpencodeApp.ServerConfig.opencodeBase.absolutePath)
        serverProblem?.let { InfoRow("Не принята", it, Color(WORKSPACE_BAD_ARGB)) }
        downloadButton("Изменить рабочую папку") {
            edit = WorkspaceEditState(saved ?: shown, saved != null)
        }
    }

    val state = edit
    if (state != null) {
        WorkspaceDialog(
            state = state,
            onDismiss = { edit = null },
            onApply = { path -> applyWorkspace(context, scope, state, path) { edit = null } },
        )
    }
}

/** Состояние открытого диалога: текст, замечание проверки и признак «идёт проверка». */
private class WorkspaceEditState(
    shown: String,
    val hasSaved: Boolean,
) {
    var text by mutableStateOf(shown)
    var note by mutableStateOf<String?>(null)
    var bad by mutableStateOf(false)
    var busy by mutableStateOf(false)
}

@Composable
private fun WorkspaceDialog(
    state: WorkspaceEditState,
    onDismiss: () -> Unit,
    onApply: (String?) -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Рабочая папка") },
        text = {
            // Прокрутка обязательна: на маленьком экране варианты и поле ввода
            // вместе выше диалога, и без неё кнопка «Применить» уехала бы за край.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Модель работает в этой папке. Нужен настоящий путь (/storage/…), а не ссылка: " +
                        "serve запускается с этой директорией как cwd. Дом opencode, память и сессии " +
                        "остаются на месте — их смена папки не касается.",
                    color = MoltIdle,
                    fontSize = SMALL,
                    lineHeight = SMALL_LINE,
                )
                OutlinedTextField(
                    value = state.text,
                    onValueChange = {
                        state.text = it
                        state.note = null
                    },
                    singleLine = true,
                    label = { Text("Путь") },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Text(
                    "Готовые варианты",
                    color = MoltIdle,
                    fontSize = TINY,
                    modifier = Modifier.padding(top = 8.dp),
                )
                for ((label, path) in Workspace.candidates(context)) {
                    downloadButton(label) {
                        state.text = path
                        state.note = null
                    }
                }
                state.note?.let {
                    Text(
                        it,
                        color = if (state.bad) Color(WORKSPACE_BAD_ARGB) else MoltValue,
                        fontSize = SMALL,
                        lineHeight = SMALL_LINE,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (state.hasSaved) {
                    downloadButton("Вернуть папку по умолчанию", warn = true) { onApply(null) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(state.text) }, enabled = !state.busy) {
                Text(if (state.busy) "Проверяю…" else "Применить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/**
 * Проверить и применить путь, затем перезапустить serve.
 *
 * Проверка идёт на IO: она создаёт каталог и пишет пробный файл. Путь не
 * сохраняется, пока проверка не прошла, — иначе настройка «применилась» бы, а
 * сервер молча работал бы в старой папке, и это читалось бы как потеря настройки.
 */
private fun applyWorkspace(
    context: Context,
    scope: CoroutineScope,
    state: WorkspaceEditState,
    path: String?,
    onApplied: () -> Unit,
) {
    if (state.busy) return
    state.busy = true
    scope.launch {
        val prepared = withContext(Dispatchers.IO) { path?.let { WorkspacePath.prepare(it) } }
        val problem = prepared?.problem
        if (problem != null) {
            state.note = problem
            state.bad = true
            state.busy = false
        } else {
            val dir = prepared?.dir
            WorkspacePrefs.save(context, dir?.absolutePath)
            OpencodeServerService.restart(context)
            val shown = dir?.absolutePath ?: "по умолчанию"
            Toast.makeText(context, "Рабочая папка: $shown", Toast.LENGTH_LONG).show()
            onApplied()
        }
    }
}
