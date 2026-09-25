package org.opencode.mobile.installer

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
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
        Log.i(TAG, "launch requested package=${spec.packageName} component=${target.componentName}")
        return LaunchedAppSnapshot(
            packageName = target.packageName,
            label = target.label,
            componentName = target.componentName,
            message = "Android accepted the launcher intent",
        )
    }

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
