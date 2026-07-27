package com.lushibaxian.pullwire

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Detects whether our services are actually alive, and heals stale VPN / prefs
 * left behind by force-stop, process death, or in-place app updates.
 */
object ServiceRuntime {
    private const val TAG = "ServiceRuntime"
    private const val NOTIF_VPN = 1001
    private const val NOTIF_FLOAT = 1002

    fun isServiceAlive(context: Context, serviceClass: Class<*>): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val running = am.getRunningServices(Int.MAX_VALUE) ?: return false
        val name = serviceClass.name
        return running.any { it.service.className == name }
    }

    fun isFloatAlive(context: Context): Boolean =
        isServiceAlive(context, FloatBallService::class.java)

    fun isVpnAlive(context: Context): Boolean =
        isServiceAlive(context, PullWireVpnService::class.java) && PullWireVpnService.isRunning

    /**
     * Align SharedPreferences with real process state.
     *
     * @param activeCleanup when true (UI / receiver in a startable context),
     * also stop services and reclaim residual system VPN.
     * @return true if a stale/broken state was repaired
     */
    fun reconcile(
        context: Context,
        reason: String,
        activeCleanup: Boolean = true
    ): Boolean {
        val app = context.applicationContext
        val prefFloat = Prefs.isFloatRunning(app)
        val prefVpn = Prefs.isVpnRunning(app)
        val floatAlive = isFloatAlive(app)
        val vpnServiceAlive = isServiceAlive(app, PullWireVpnService::class.java)
        val vpnEngineUp = PullWireVpnService.isRunning

        Log.i(
            TAG,
            "reconcile($reason) prefs(float=$prefFloat,vpn=$prefVpn) " +
                "alive(float=$floatAlive,vpnSvc=$vpnServiceAlive,engine=$vpnEngineUp) " +
                "activeCleanup=$activeCleanup"
        )

        // Healthy path.
        if (prefFloat && floatAlive && vpnServiceAlive && vpnEngineUp) {
            return false
        }

        // Completely idle and clean.
        if (!prefFloat && !prefVpn && !floatAlive && !vpnServiceAlive && !vpnEngineUp) {
            return false
        }

        // Float is up but VPN engine not yet / died — try kick-start once when active.
        if (prefFloat && floatAlive && !vpnEngineUp && activeCleanup) {
            Log.i(TAG, "float alive without VPN engine → restart VPN")
            PullWireController.startVpn(app)
            return false
        }

        val stalePrefs = (prefFloat || prefVpn) && (!floatAlive || !vpnServiceAlive || !vpnEngineUp)
        val orphanService = (!prefFloat && !prefVpn) && (floatAlive || vpnServiceAlive)

        if (!stalePrefs && !orphanService) {
            return false
        }

        Log.w(TAG, "repairing stale runtime state ($reason)")
        if (activeCleanup) {
            forceStopAll(app)
        } else {
            // Application cold start: only clear flags; full reclaim when UI opens.
            Prefs.clearRunningFlags(app)
            clearNotifications(app)
        }
        return true
    }

    /**
     * Hard stop + reclaim residual system VPN.
     * Call from Activity / user-visible context when possible.
     */
    fun forceStopAll(context: Context) {
        val app = context.applicationContext
        Prefs.clearRunningFlags(app)

        try {
            app.startService(
                Intent(app, FloatBallService::class.java).apply {
                    action = FloatBallService.ACTION_HIDE
                }
            )
        } catch (_: Exception) {
        }

        try {
            val cleanup = Intent(app, PullWireVpnService::class.java).apply {
                action = PullWireVpnService.ACTION_CLEANUP
            }
            try {
                ContextCompat.startForegroundService(app, cleanup)
            } catch (_: Exception) {
                try {
                    app.startService(
                        Intent(app, PullWireVpnService::class.java).apply {
                            action = PullWireVpnService.ACTION_STOP
                        }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "stop vpn failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cleanup vpn failed: ${e.message}")
        }

        clearNotifications(app)
        Log.i(TAG, "forceStopAll done")
    }

    private fun clearNotifications(context: Context) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.cancel(NOTIF_VPN)
            nm.cancel(NOTIF_FLOAT)
        } catch (_: Exception) {
        }
    }
}
