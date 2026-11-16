package org.opencode.mobile.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

/** Цвет «картинки нет» — тот же приглушённый, что у служебных строк ленты. */
private const val MUTED_ARGB = 0xFF9A9A9A

private val MUTED = Color(MUTED_ARGB)

/**
 * Картинка реплики: файл на диске, нарисованный во всю ширину и до 420 dp высотой.
 *
 * Рисуется по `ContentScale.Fit`, а не по обрезке: у референса важны все поля, а
 * не «красивая» рамка. Тап открывает картинку на весь экран — подписи и мелкие
 * детали на телефоне иначе не разглядеть.
 */
@Composable
internal fun ChatPictureView(picture: ChatPicture, modifier: Modifier = Modifier) {
    val file = remember(picture.path) { ChatPicturePolicy.resolve(picture.path) }
    if (file == null) {
        ChatPictureMissing(picture.path)
    } else {
        ChatPictureBody(file, picture, modifier)
    }
}

@Composable
private fun ChatPictureBody(file: File, picture: ChatPicture, modifier: Modifier = Modifier) {
    val bitmap by
        produceState<Bitmap?>(
            initialValue = ChatPictureCache.peek(file),
            file.path,
            file.length(),
            file.lastModified(),
        ) {
            value = ChatPictureCache.load(file)
        }
    var enlarged by remember(picture.path) { mutableStateOf(false) }
    val bmp = bitmap
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (bmp == null) {
            Text("картинка не открылась: ${picture.path}", color = MUTED, fontSize = 12.sp)
        } else {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = picture.caption ?: "картинка",
                contentScale = ContentScale.Fit,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { enlarged = true }
                        .padding(top = 2.dp),
            )
        }
        picture.caption?.let {
            Text(it, color = MUTED, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (enlarged && bmp != null) {
        PictureDialog(bmp, onDismiss = { enlarged = false })
    }
}

/** Строка вместо картинки: путь вне разрешённых корней, файла нет или это не картинка. */
@Composable
private fun ChatPictureMissing(path: String) {
    Text("картинка недоступна: $path", color = MUTED, fontSize = 12.sp, lineHeight = 16.sp)
}

@Composable
private fun PictureDialog(bitmap: Bitmap, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "картинка",
            contentScale = ContentScale.Fit,
            alignment = Alignment.Center,
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.94f))
                    .clickable { onDismiss() }
                    .padding(8.dp),
        )
    }
}
