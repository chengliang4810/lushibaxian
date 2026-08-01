package com.lushibaxian.pullwire

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Detects whether our services are actually alive, and heals stale VPN / prefs
 * left behind by force-stop, process death, or in-place app updates.
 *
 * Liveness is read from each service's own static [isRunning] flag rather than
 * [android.app.ActivityManager.getRunningServices]: that API is deprecated and,
 * since API 26, only reports the calling app's own services (and is unreliable
 * on many OEM ROMs anyway). Our process is the host of both services, so the
 * static flags reflect real [android.app.Service.onCreate]/[onDestroy] calls.
 */
object ServiceRuntime {
    private const val TAG = "ServiceRuntime"
    private const val NOTIF_VPN = 1001
    private const val NOTIF_FLOAT = 1002

    /**
     * Wall-clock ms when an auto-restart (e.g. after in-place update) was kicked
     * off. While inside [AUTO_RESTART_GRACE_MS] of this, [reconcile] will NOT
     * force-stop even if prefs say running but the service static flag is still
     * false — there is a window between writing prefs=true and the service's
     * onCreate flipping its [isRunning] flag. Without this, opening the app (or
     * onResume reconcile) during that window would tear down the just-started
     * tunnel. Touched only on the main thread.
     */
    private const val AUTO_RESTART_GRACE_MS = 4_000L
    @Volatile
    private var autoRestartStartedAt: Long = 0L

    fun markAutoRestartStarted() {
        autoRestartStartedAt = System.currentTimeMillis()
    }

    private fun inAutoRestartGrace(): Boolean {
        val t = autoRestartStartedAt
        return t > 0L && System.currentTimeMillis() - t < AUTO_RESTART_GRACE_MS
    }

    fun isFloatAlive(context: Context): Boolean {
        // Touch the static flag only; no ActivityManager query.
        val pref = Prefs.isFloatRunning(context)
        return pref && FloatBallService.isRunning
    }

    fun isVpnAlive(context: Context): Boolean {
        val pref = Prefs.isVpnRunning(context)
        return pref && PullWireVpnService.isRunning
    }

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
        val floatAlive = FloatBallService.isRunning
        val vpnEngineUp = PullWireVpnService.isRunning

        Log.i(
            TAG,
            "reconcile($reason) prefs(float=$prefFloat,vpn=$prefVpn) " +
                "alive(float=$floatAlive,engine=$vpnEngineUp) " +
                "activeCleanup=$activeCleanup"
        )

        // Healthy path.
        if (prefFloat && floatAlive && vpnEngineUp) {
            return false
        }

        // Completely idle and clean.
        if (!prefFloat && !prefVpn && !floatAlive && !vpnEngineUp) {
            return false
        }

        // Float is up but VPN engine not yet / died — try kick-start once when active.
        if (prefFloat && floatAlive && !vpnEngineUp && activeCleanup) {
            Log.i(TAG, "float alive without VPN engine → restart VPN")
            PullWireController.startVpn(app)
            return false
        }

        val stalePrefs = (prefFloat || prefVpn) && (!floatAlive || !vpnEngineUp)
        val orphanService = (!prefFloat && !prefVpn) && (floatAlive || vpnEngineUp)

        if (!stalePrefs && !orphanService) {
            return false
        }

        Log.w(TAG, "repairing stale runtime state ($reason)")
        // Grace window: a just-kicked-off auto-restart (update / boot) writes
        // prefs=true before the service's onCreate flips its static flag. Do not
        // tear it down during that window — let the service come up, and let the
        // next reconcile re-evaluate. We still return true so callers know the
        // state is not yet healthy.
        if (activeCleanup && inAutoRestartGrace()) {
            Log.i(TAG, "in auto-restart grace window ($reason) → defer force-stop")
            return true
        }
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
