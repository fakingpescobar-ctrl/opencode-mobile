package org.opencode.mobile.media

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Снимок экрана по запросу агента.
 *
 * Зачем через службу доступности: обычный `screencap` из оболочки агента отвечает
 * «Failed to take screenshot» — оболочка живёт в `untrusted_app` и кадра экрана не
 * видит. У службы доступности с `canTakeScreenshot` есть системный вызов
 * [AccessibilityService.takeScreenshot], и он не требует подтверждения на каждый
 * снимок (MediaProjection требует диалог каждый раз — для «пришли, как выглядит»
 * это неприемлемо).
 *
 * Исходов два и они не схлопываются: файл записан — это [Saved] с размерами,
 * иначе [Failed] с причиной. «Сняли» без файла не бывает.
 */
sealed interface ScreenshotOutcome {
    data class Saved(
        val file: File,
        val width: Int,
        val height: Int,
        val bytes: Long,
    ) : ScreenshotOutcome

    data class Failed(
        val reason: String,
    ) : ScreenshotOutcome
}

/**
 * Куда класть снимки: Download.
 *
 * Это не вкус: лента чата имеет право читать картинки только из рабочей папки агента
 * и из Download ([org.opencode.mobile.ui.chat.ChatPicturePolicy]). Снимок в приватной
 * папке приложения агент не смог бы показать, а «снял, но не видно» хуже отказа.
 */
const val SCREENSHOT_DIR = "/storage/emulated/0/Download"

private const val NAME_LIMIT = 40
private const val COMPRESS_QUALITY = 100

/** Как долго ждём систему: на живом устройстве снимок приходит за десятки миллисекунд. */
const val SCREENSHOT_TIMEOUT_MS = 5_000L

/**
 * Имя файла снимка.
 *
 * Запрошенное имя чистится до букв, цифр, `-` и `_`: `..`, `/` и переводы строк в имени
 * означали бы запись не туда, куда сказано. Пустое или подозрительное имя заменяется
 * меткой времени — файл всё равно нужен, а спорить о имени не о чем.
 */
internal fun screenshotName(
    requested: String?,
    nowMs: Long,
): String {
    val clean =
        requested
            ?.trim()
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            ?.take(NAME_LIMIT)
            ?.takeIf { it.isNotEmpty() }
    return "shot-${clean ?: nowMs}.png"
}

/** Полный путь снимка: всегда внутри [SCREENSHOT_DIR]. */
internal fun screenshotFile(
    requested: String?,
    nowMs: Long,
): File = File(SCREENSHOT_DIR, screenshotName(requested, nowMs))

/**
 * Снимает экран в [dest] и возвращает исход. Блокирует поток до исхода или до
 * [timeoutMs] — мост и так отдаёт каждый запрос своему потоку.
 */
internal fun AccessibilityService.captureScreenTo(
    dest: File,
    timeoutMs: Long = SCREENSHOT_TIMEOUT_MS,
): ScreenshotOutcome =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        ScreenshotOutcome.Failed("снимок экрана требует Android 11 (API 30)")
    } else {
        shoot(dest, timeoutMs)
    }

/**
 * Почему система отказала в снимке — словами.
 *
 * Проверить право заранее нечем: `canTakeScreenshot` существует только как атрибут
 * конфигурации службы, метода-геттера у `AccessibilityService` нет. Зато система
 * называет причину кодом, и «нет права» — это отдельный код, а не общая ошибка:
 * после обновления APK телефон может держать старый набор прав службы, и тогда
 * лечится это выключением-включением службы, а не повтором запроса.
 */
@RequiresApi(Build.VERSION_CODES.R)
private fun screenshotRefusal(errorCode: Int): String =
    when (errorCode) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            "слишком часто: система не даёт снимать экран дважды подряд, повторите через полсекунды"

        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            "у службы доступности нет права снимать экран: выключите и включите её заново в настройках"

        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
            "на экране защищённое окно (пароль, банк): система запрещает снимок"

        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
            "система не знает такой дисплей"

        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW ->
            "окно, которое просили снять, недоступно"

        else -> "система отказала в снимке: код $errorCode"
    }

@RequiresApi(Build.VERSION_CODES.R)
private fun AccessibilityService.shoot(
    dest: File,
    timeoutMs: Long,
): ScreenshotOutcome {
    val outcome = AtomicReference<ScreenshotOutcome>(ScreenshotOutcome.Failed("система не ответила за $timeoutMs мс"))
    val settled = CountDownLatch(1)
    takeScreenshot(
        Display.DEFAULT_DISPLAY,
        mainExecutor,
        object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                outcome.set(saveScreen(result, dest))
                settled.countDown()
            }

            override fun onFailure(errorCode: Int) {
                outcome.set(ScreenshotOutcome.Failed(screenshotRefusal(errorCode)))
                settled.countDown()
            }
        },
    )
    settled.await(timeoutMs, TimeUnit.MILLISECONDS)
    return outcome.get()
}

/** Снимок → PNG-файл. Буфер закрывается всегда: он держит память графического драйвера. */
@RequiresApi(Build.VERSION_CODES.R)
private fun saveScreen(
    result: AccessibilityService.ScreenshotResult,
    dest: File,
): ScreenshotOutcome {
    val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
    result.hardwareBuffer.close()
    if (bitmap == null) {
        return ScreenshotOutcome.Failed("система вернула снимок, который не читается")
    }
    return try {
        dest.parentFile?.mkdirs()
        val written =
            FileOutputStream(dest).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, COMPRESS_QUALITY, out)
            }
        if (!written || dest.length() <= 0L) {
            ScreenshotOutcome.Failed("снимок не записался: ${dest.path}")
        } else {
            ScreenshotOutcome.Saved(dest, bitmap.width, bitmap.height, dest.length())
        }
    } finally {
        bitmap.recycle()
    }
}
