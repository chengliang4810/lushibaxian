package com.lushibaxian.pullwire

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lushibaxian.pullwire.vpn.VpnEngine

/**
 * Scheme A: persistent VPN for Hearthstone only + temporary packet drop.
 *
 * - VPN stays up while the float ball is running
 * - Traffic is userspace-NAT forwarded (UDP/TCP)
 * - Pull-wire only sets dropping=true for N ms (no teardown of VPN iface)
 * - Engine is rebuilt if worker threads die after repeated pulls
 */
class PullWireVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.lushibaxian.pullwire.action.START"
        const val ACTION_STOP = "com.lushibaxian.pullwire.action.STOP"
        const val ACTION_PULL = "com.lushibaxian.pullwire.action.PULL"
        const val ACTION_CLEANUP = "com.lushibaxian.pullwire.action.CLEANUP"
        const val EXTRA_DURATION_MS = "duration_ms"

        private const val TAG = "PullWireVpn"
        private const val CHANNEL_ID = "pull_wire_vpn"
        private const val NOTIF_ID = 1001
        private const val HEALTH_INTERVAL_MS = 5_000L
        /** Require consecutive failures before rebuild, to avoid false reconnects. */
        private const val HEALTH_FAIL_THRESHOLD = 3

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var vpnInterface: ParcelFileDescriptor? = null
    private var engine: VpnEngine? = null
    private var consecutiveHealthFails = 0
    private var lastRebuildAt = 0L

    private val healthCheck = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val eng = engine
            // Never rebuild mid pull-wire; dropping means intentional silence.
            val pulling = eng?.dropping?.get() == true
            if (!pulling && (eng == null || !eng.isHealthy())) {
                consecutiveHealthFails++
                Log.w(TAG, "engine unhealthy ($consecutiveHealthFails/$HEALTH_FAIL_THRESHOLD)")
                if (consecutiveHealthFails >= HEALTH_FAIL_THRESHOLD) {
                    consecutiveHealthFails = 0
                    rebuildEngineWorkers("health_check")
                }
            } else {
                consecutiveHealthFails = 0
            }
            handler.postDelayed(this, HEALTH_INTERVAL_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "stop requested")
                shutdownAll()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CLEANUP -> {
                Log.i(TAG, "cleanup residual VPN")
                startAsForeground("正在清理网络状态…")
                try {
                    reclaimAndClose()
                } catch (e: Exception) {
                    Log.w(TAG, "cleanup failed: ${e.message}")
                }
                shutdownAll()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PULL -> {
                if (!isRunning || engine == null || engine?.isHealthy() != true) {
                    val err = ensureTunnel(forceRebuild = engine?.isHealthy() == false)
                    if (err != null) {
                        PullWireController.onPullFailed(this, err)
                        return START_STICKY
                    }
                }
                val duration = intent.getLongExtra(
                    EXTRA_DURATION_MS,
                    Prefs.nextDurationMs()
                ).coerceIn(Prefs.MIN_DURATION_MS, Prefs.MAX_DURATION_MS)
                doPull(duration)
                return START_STICKY
            }
            ACTION_START, null -> {
                val err = ensureTunnel()
                if (err != null) {
                    Log.e(TAG, "start failed: $err")
                    updateNotification("VPN 启动失败: $err")
                }
                return START_STICKY
            }
            else -> return START_STICKY
        }
    }

    private fun reclaimAndClose() {
        stopEngineWorkers()
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        try {
            val builder = Builder()
                .setSession("炉石拔线-清理")
                .setMtu(1500)
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
            try {
                builder.addAllowedApplication(Prefs.HS_PACKAGE)
            } catch (_: Exception) {
            }
            val pfd = builder.establish()
            try {
                pfd?.close()
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            Log.w(TAG, "reclaimAndClose: ${e.message}")
        }
    }

    private fun ensureTunnel(forceRebuild: Boolean = false): String? {
        if (!forceRebuild && engine != null && vpnInterface != null && engine?.isHealthy() == true) {
            isRunning = true
            startAsForeground("炉石隧道运行中 · 点悬浮球拔线")
            startHealthLoop()
            return null
        }
        // Only restart userspace engine; never close system VPN iface here.
        // Closing the iface forces Hearthstone offline/reconnect for all players.
        if (forceRebuild || (engine != null && engine?.isHealthy() != true)) {
            Log.i(TAG, "rebuilding engine workers only (force=$forceRebuild)")
            stopEngineWorkers()
        }
        return try {
            startAsForeground("正在建立炉石隧道…")
            if (vpnInterface == null) {
                val builder = Builder()
                    .setSession("炉石拔线")
                    .setMtu(1500)
                    .addAddress("10.0.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .setBlocking(true)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                }

                try {
                    builder.addAllowedApplication(Prefs.HS_PACKAGE)
                } catch (e: Exception) {
                    return "未安装炉石或包名不对: ${Prefs.HS_PACKAGE}"
                }

                val pfd = builder.establish()
                    ?: return "VPN 建立失败（未授权或被其它 VPN 占用）"
                vpnInterface = pfd
            }

            val pfd = vpnInterface
                ?: return "VPN 接口丢失"
            val eng = VpnEngine(this, pfd) {
                // Worker death: schedule cautious rebuild (debounced).
                handler.post { rebuildEngineWorkers("engine_dead") }
            }
            eng.start()
            engine = eng
            consecutiveHealthFails = 0
            isRunning = true
            Prefs.setVpnRunning(this, true)
            updateNotification("炉石隧道运行中 · 点悬浮球拔线")
            startHealthLoop()
            Log.i(TAG, "tunnel up, target=${Prefs.HS_PACKAGE}")
            null
        } catch (e: Exception) {
            stopEngineWorkers()
            try {
                vpnInterface?.close()
            } catch (_: Exception) {
            }
            vpnInterface = null
            "VPN 异常: ${e.message}"
        }
    }

    /**
     * Restart userspace NAT only. Must NOT close [vpnInterface], otherwise
     * Hearthstone sees a full network drop and reconnects by itself.
     */
    private fun rebuildEngineWorkers(reason: String) {
        if (!isRunning && engine == null) return
        val now = System.currentTimeMillis()
        // Debounce: at most one rebuild every 15s to avoid reconnect storms.
        if (now - lastRebuildAt < 15_000L) {
            Log.w(TAG, "skip rebuild ($reason), debounced")
            return
        }
        lastRebuildAt = now
        Log.w(TAG, "rebuildEngineWorkers: $reason")
        stopEngineWorkers()
        val err = ensureTunnel(forceRebuild = false)
        if (err != null) {
            // Interface may be dead; last resort full re-establish.
            Log.e(TAG, "worker rebuild failed: $err, trying full tunnel")
            try {
                vpnInterface?.close()
            } catch (_: Exception) {
            }
            vpnInterface = null
            val err2 = ensureTunnel(forceRebuild = false)
            if (err2 != null) {
                Log.e(TAG, "full rebuild failed: $err2")
                updateNotification("网络转发异常，请停止后重新启动")
            }
        } else {
            updateNotification("炉石隧道运行中 · 点悬浮球拔线")
        }
    }

    private fun stopEngineWorkers() {
        try {
            engine?.stop()
        } catch (_: Exception) {
        }
        engine = null
    }

    private fun startHealthLoop() {
        handler.removeCallbacks(healthCheck)
        handler.postDelayed(healthCheck, HEALTH_INTERVAL_MS)
    }

    private fun doPull(durationMs: Long) {
        var eng = engine
        if (eng == null || !eng.isHealthy()) {
            // Prefer worker-only recovery; do not bounce system VPN.
            rebuildEngineWorkers("before_pull")
            eng = engine
            if (eng == null || !eng.isHealthy()) {
                val err = ensureTunnel(forceRebuild = false)
                if (err != null) {
                    PullWireController.onPullFailed(this, err)
                    return
                }
                eng = engine
            }
        }
        if (eng == null) {
            PullWireController.onPullFailed(this, "隧道未启动")
            return
        }

        Log.i(TAG, "pull drop ${durationMs}ms")
        // Drop traffic only. Do NOT mass-RST all sessions here:
        // that alone can look like spontaneous full reconnects if mis-fired,
        // and is heavier than needed for animation skip.
        eng.dropping.set(true)
        // Soft session clear (no RST flood): close NAT maps so next packets reopen.
        eng.resetSessions(sendRst = false)
        updateNotification("拔线中… ${durationMs}ms")

        // Cancel only previous pull end callback, keep health loop.
        handler.removeCallbacksAndMessages(null)
        startHealthLoop()

        handler.postDelayed({
            val current = engine
            current?.dropping?.set(false)
            updateNotification("炉石隧道运行中 · 点悬浮球拔线")
            Log.i(TAG, "pull drop end, forwarding resumed")
            PullWireController.onPullFinished(this)
            // Do not auto-rebuild after every pull; only health loop handles death.
        }, durationMs)
    }

    private fun shutdownAll() {
        handler.removeCallbacks(healthCheck)
        handler.removeCallbacksAndMessages(null)
        stopEngineWorkers()
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        isRunning = false
        Prefs.setVpnRunning(this, false)
    }

    private fun startAsForeground(content: String) {
        ensureChannel()
        val notification = buildNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(content: String) {
        ensureChannel()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(content))
    }

    private fun buildNotification(content: String): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        nm.createNotificationChannel(channel)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        shutdownAll()
        Prefs.setFloatRunning(this, false)
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked by system")
        handler.removeCallbacks(healthCheck)
        handler.removeCallbacksAndMessages(null)
        stopEngineWorkers()
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        isRunning = false
        Prefs.setVpnRunning(this, false)
        Prefs.setFloatRunning(this, false)
        PullWireController.onPullFailed(this, "VPN 被系统撤销")
        stopForegroundCompat()
        stopSelf()
        super.onRevoke()
    }
}
