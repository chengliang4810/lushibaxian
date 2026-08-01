package com.lushibaxian.pullwire

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Fired after this package is updated in place (adb install -r / store update).
 *
 * Covering install kills the old process while the system VPN may still route
 * Hearthstone into a dead tunnel → game stuck on "reconnecting".
 *
 * Strategy:
 * 1) If user had the tool running, remember that
 * 2) Reclaim residual VPN / clear stale state
 * 3) Auto re-start float ball + tunnel so the game can recover without
 *    the user manually opening this app again
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        val pending = goAsync()
        val wasRunning = Prefs.isFloatRunning(app) || Prefs.isVpnRunning(app)
        Log.i(TAG, "MY_PACKAGE_REPLACED wasRunning=$wasRunning → cleanup + optional restart")

        try {
            // Tear down residual system VPN left by the killed process.
            ServiceRuntime.forceStopAll(app)
        } catch (e: Exception) {
            Log.w(TAG, "cleanup: ${e.message}")
            Prefs.clearRunningFlags(app)
        }

        if (!wasRunning) {
            pending.finish()
            return
        }

        // Give the system a moment to drop the old VPN, then bring tunnel back.
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                // Open a grace window so a concurrent reconcile (app opened /
                // onResume during the restart) does not tear down the services
                // we are about to start: we write prefs=true before the service
                // onCreate flips its static isRunning flag.
                ServiceRuntime.markAutoRestartStarted()
                restartServices(app)
            } catch (e: Exception) {
                Log.w(TAG, "auto-restart failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }, 800)
    }

    private fun restartServices(app: Context) {
        Log.i(TAG, "auto-restart VPN + float after update")
        // Start VPN first, then float (float also starts VPN as a safety).
        PullWireController.startVpn(app)
        val show = Intent(app, FloatBallService::class.java).apply {
            action = FloatBallService.ACTION_SHOW
        }
        ContextCompat.startForegroundService(app, show)
        Prefs.setFloatRunning(app, true)
        Prefs.setVpnRunning(app, true)
    }

    companion object {
        private const val TAG = "PackageReplaced"
    }
}
