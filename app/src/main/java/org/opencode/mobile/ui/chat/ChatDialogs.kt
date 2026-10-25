package org.opencode.mobile.ui.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Диалог подтверждения необратимого действия. Одна форма на два случая —
// удаление всех сессий и сброс зависшей: до переноса это были две почти
// одинаковые копии AlertDialog, и цвета с кнопками в них разъезжались при
// любой правке.
@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1C1C1C),
        titleContentColor = Color(0xFFE6E6E6),
        textContentColor = Color(0xFFBDBDBD),
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = Color(0xFFFF6F5A)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = Color(0xFFBDBDBD)) }
        },
    )
}
