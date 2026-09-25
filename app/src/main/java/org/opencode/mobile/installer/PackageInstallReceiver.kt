package org.opencode.mobile.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Получает финальный статус PackageInstaller без root и shell. */
class PackageInstallReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ApkInstaller.STATUS_ACTION) return
        val jobId = intent.getStringExtra(ApkInstaller.EXTRA_JOB_ID) ?: return
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        val pendingIntent = pendingIntent(intent)
        ApkInstaller.onInstallerStatus(jobId, status, message, packageName, pendingIntent)
    }

    @Suppress("DEPRECATION")
    private fun pendingIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
}
