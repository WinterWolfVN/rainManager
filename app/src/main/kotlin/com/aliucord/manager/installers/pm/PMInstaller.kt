package com.aliucord.manager.installers.pm

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.*
import android.content.pm.PackageInstaller.SessionParams
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.aliucord.manager.installers.Installer
import com.aliucord.manager.util.isMiui
import dev.raincord.manager.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

class PMInstaller(
    private val context: Application,
) : Installer {
    private val _packageInstaller = context.packageManager.packageInstaller

    init {
        for (session in _packageInstaller.mySessions) {
            Log.d(BuildConfig.TAG, "Deleting old PackageInstaller session ${session.sessionId}")
            _packageInstaller.abandonSession(session.sessionId)
        }
    }

    override fun install(apks: List<File>, silent: Boolean) {
        startInstall(createInstallSession(silent), apks, false)
    }

    override suspend fun waitInstall(apks: List<File>, silent: Boolean) = suspendCancellableCoroutine { continuation ->
        val sessionId = createInstallSession(silent)

        val relayReceiver = PMResultReceiver(
            sessionId = sessionId,
            isUninstall = false,
            onResult = { continuation.resume(it) },
        )

        ContextCompat.registerReceiver(
            context,
            relayReceiver,
            PMResultReceiver.intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        continuation.invokeOnCancellation { error ->
            context.unregisterReceiver(relayReceiver)
            context.packageManager.packageInstaller.abandonSession(sessionId)
        }

        startInstall(sessionId, apks, relay = true)
    }

    override suspend fun waitUninstall(packageName: String) = suspendCancellableCoroutine { continuation ->
        val callbackIntent = Intent(context, PMIntentReceiver::class.java)
            .putExtra(PMIntentReceiver.EXTRA_SESSION_ID, -1)
            .putExtra(PMIntentReceiver.EXTRA_RELAY_ENABLED, true)

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.getBroadcast(context, 0, callbackIntent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            PendingIntent.getBroadcast(context, 0, callbackIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val pendingIntent = flags

        val relayReceiver = PMResultReceiver(
            sessionId = -1,
            isUninstall = true,
            onResult = { continuation.resume(it) },
        )

        ContextCompat.registerReceiver(
            context,
            relayReceiver,
            PMResultReceiver.intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        continuation.invokeOnCancellation { error ->
            context.unregisterReceiver(relayReceiver)
        }

        _packageInstaller.uninstall(packageName, pendingIntent.intentSender)
    }

    private fun createInstallSession(silent: Boolean): Int {
        val params = SessionParams(SessionParams.MODE_FULL_INSTALL).apply {
            setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO)

            if (Build.VERSION.SDK_INT >= 24) setOriginatingUid(Process.myUid())
            if (Build.VERSION.SDK_INT >= 26) setInstallReason(PackageManager.INSTALL_REASON_USER)

            if (Build.VERSION.SDK_INT >= 31) {
                setInstallScenario(PackageManager.INSTALL_SCENARIO_FAST)
                if (silent && !isMiui()) {
                    setRequireUserAction(SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }

            if (Build.VERSION.SDK_INT >= 34) setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER)
        }

        return context.packageManager.packageInstaller.createSession(params)
    }

    private fun startInstall(sessionId: Int, apks: List<File>, relay: Boolean) {
        val callbackIntent = Intent(context, PMIntentReceiver::class.java)
            .putExtra(PMIntentReceiver.EXTRA_SESSION_ID, sessionId)
            .putExtra(PMIntentReceiver.EXTRA_RELAY_ENABLED, relay)

        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.getBroadcast(context, 0, callbackIntent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            PendingIntent.getBroadcast(context, 0, callbackIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        _packageInstaller.openSession(sessionId).use { session ->
            val bufferSize = 2 * 1024 * 1024

            for (apk in apks) {
                session.openWrite(apk.name, 0, apk.length()).use { outStream ->
                    apk.inputStream().use { it.copyTo(outStream, bufferSize) }
                    session.fsync(outStream)
                }
            }

            session.commit(pendingIntent.intentSender)
        }
    }
}
