package org.opencode.mobile.installer

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.util.Locale

data class LaunchableAppSnapshot(
    val packageName: String,
    val label: String,
    val componentName: String,
    val versionName: String,
)

data class LaunchedAppSnapshot(
    val packageName: String,
    val label: String,
    val componentName: String,
    val message: String,
    /**
     * Разрешено ли приложению запускать activity из фона. Это единственный честный сигнал,
     * доступный здесь: он говорит, сможет ли Android пустить activity вообще, а не запустилась ли
     * она - про это знает только сама цель (у Яндекс.Музыки - её MediaSession).
     */
    val backgroundLaunchAllowed: Boolean,
)

/**
 * Ограниченный контроллер launcher-приложений: только PackageManager и MAIN/LAUNCHER Intent.
 * Произвольные package component, URI, shell-команды и UI-автоматизация сюда не попадают.
 */
object InstalledAppController {
    private const val TAG = "InstalledAppController"

    private lateinit var context: Context

    @Synchronized
    fun initialize(context: Context) {
        if (::context.isInitialized) return
        this.context = context.applicationContext
    }

/**
     * Выдано ли приложению право рисовать поверх других окон - то самое, что снимает
     * background activity launch.
     *
     * Через contextOrThrow(), а не напрямую: этот метод зовётся из композиции
     * (DiagnosticsScreen), и UninitializedPropertyAccessException там уронил бы экран целиком.
     */
    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(contextOrThrow())

    fun listApps(spec: AppListSpec): List<LaunchableAppSnapshot> {
        val normalizedQuery = spec.query?.lowercase(Locale.ROOT)
        return launchableApps()
            .asSequence()
            .filter { app ->
                normalizedQuery == null ||
                    app.label.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                    app.packageName.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                    app.componentName.lowercase(Locale.ROOT).contains(normalizedQuery)
            }.distinctBy { app -> app.packageName }
            .take(spec.limit)
            .toList()
    }

    fun launchApp(spec: AppLaunchSpec): LaunchedAppSnapshot {
        val installedApps = launchableApps().filter { app -> app.packageName == spec.packageName }
        require(installedApps.isNotEmpty()) {
            "app is not installed, disabled, or has no exported launcher activity: ${spec.packageName}"
        }
        val preferredComponent = context.packageManager.getLaunchIntentForPackage(spec.packageName)?.component
        val target =
            installedApps.firstOrNull { app ->
                app.componentName == preferredComponent?.flattenToString()
            } ?: installedApps.first()
        val component =
            ComponentName.unflattenFromString(target.componentName)
                ?: error("Android returned a malformed launcher component")
        startLauncherIntent(component)
        // startActivity only reports what the package manager and Android's permission checks
        // said. It does NOT tell us the start actually happened: when the caller has no visible
        // window, Android's background activity launch (BAL) rules drop the start with
        // `result code=102 (BAL_BLOCK)` and still return success to us. Measured 01.10.2026
        // while serving in the background: Yandex Music stayed dead for three full attempts and
        // every one of them answered 200 {"ok":true}, so the caller waited out its whole budget
        // for a launch that had never happened.
        //
        // We deliberately do NOT claim to know whether the app came up. Every signal we can reach
        // from here is either forbidden or absent:
        //   * /proc/<pid>/cmdline is unreadable for another uid - measured 01.10.2026 on this
        //     device: /proc is mounted `hidepid=invisible`, so `cat /proc/<ym-pid>/cmdline` from our
        //     own uid answers "Permission denied" while the process is plainly alive (pidof sees it).
        //     A scan would return false for a live app, i.e. lie in the exact case we need it.
        //   * ActivityManager.getRunningAppProcesses returns only our own package since API 21.
        //   * UsageStatsManager would need a second Special Access grant on top of the first one.
        // So the answer is "intent accepted", and the real proof of life is the target's own
        // observable state - for Yandex Music that is the MediaSession, read via /v1/media/status.
        val backgroundAllowed = canDrawOverlays()
        val message =
            if (backgroundAllowed) {
                "Android accepted the launcher intent and background launches are allowed"
            } else {
                "startActivity was accepted but the app may not start: Android background " +
                    "activity launch (BAL) blocks it because the caller has no visible window. " +
                    "Grant SYSTEM_ALERT_WINDOW (Diagnostics -> Фоновый запуск приложений) " +
                    "or start the app by hand, then confirm with its own session status"
            }
        Log.i(TAG, "launch requested package=${spec.packageName} component=${target.componentName} background_allowed=$backgroundAllowed")
        return LaunchedAppSnapshot(
            packageName = target.packageName,
            label = target.label,
            componentName = target.componentName,
            message = message,
            backgroundLaunchAllowed = backgroundAllowed,
        )
    }

    // Жив ли процесс пакета, по нему СУДИТЬ НЕЛЬЗЯ, и код такого определения намеренно не содержит.
    // Android 9+ монтирует /proc с hidepid, из-за чего /proc/<чужой-pid>/cmdline отдаёт EACCES даже
    // когда процесс жив (проверено на этом устройстве: pidof видит pid, cat cmdline - Permission
    // denied). ActivityManager.getRunningAppProcesses с API 21 отдаёт только своё приложение.
    // Любой такой «детект» возвращал бы false для живое приложения - то есть врал бы именно там,
    // где правда нужнее всего. Признак жизни берите у цели: у Яндекс.Музыки это MediaSession.

    private fun startLauncherIntent(component: ComponentName) {
        val intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            throw IllegalArgumentException("launcher activity is unavailable: ${component.flattenToString()}")
        } catch (error: SecurityException) {
            throw IllegalStateException("Android blocked launching ${component.packageName}", error)
        }
    }

    @Suppress("DEPRECATION")
    private fun launchableApps(): List<LaunchableAppSnapshot> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packageManager = contextOrThrow().packageManager
        val activities =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0L),
                )
            } else {
                packageManager.queryIntentActivities(intent, 0)
            }
        return activities
            .mapNotNull { resolveInfo -> resolveInfo.toLaunchableAppOrNull() }
            .distinctBy { app -> app.componentName }
            .sortedWith(
                compareBy<LaunchableAppSnapshot> { app -> app.label.lowercase(Locale.ROOT) }
                    .thenBy { app -> app.packageName }
                    .thenBy { app -> app.componentName },
            )
    }

    private fun ResolveInfo.toLaunchableAppOrNull(): LaunchableAppSnapshot? {
        val activity = activityInfo
        val application = activity?.applicationInfo
        return if (activity == null || application == null) {
            null
        } else if (!activity.enabled || !application.enabled || !activity.exported) {
            null
        } else {
            val label =
                runCatching { context.packageManager.getApplicationLabel(application).toString() }
                    .getOrNull()
                    ?.trim()
                    .orEmpty()
            LaunchableAppSnapshot(
                packageName = activity.packageName,
                label = label.ifBlank { activity.packageName },
                componentName = ComponentName(activity.packageName, activity.name).flattenToString(),
                versionName =
                    runCatching {
                        context.packageManager.getPackageInfo(activity.packageName, 0).versionName
                    }.getOrNull()
                        .orEmpty(),
            )
        }
    }

    private fun contextOrThrow(): Context =
        if (::context.isInitialized) {
            context
        } else {
            error("InstalledAppController is not initialized")
        }
}
