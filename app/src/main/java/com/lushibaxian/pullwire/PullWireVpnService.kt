package com.lushibaxian.pullwire

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
        /**
         * Grace window after the tunnel (re)starts during which network-change
         * callbacks are NOT treated as a real handoff (registration noise).
         */
        private const val TUNNEL_STABLE_GRACE_MS = 8_000L

        /**
         * Safety: if [PullWireFlags.dropping] stays true longer than this, force
         * clear it. Covers lost pull-end callbacks / process thrash that would
         * otherwise blackhole Hearthstone forever ("无网络").
         */
        private const val DROP_STUCK_MAX_MS = 3_000L

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var vpnInterface: ParcelFileDescriptor? = null
    private var engine: VpnEngine? = null
    private var consecutiveHealthFails = 0
    private var lastRebuildAt = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Coalesces rapid network-change recoveries. Some OEM ROMs fire
     * onAvailable several times back-to-back during a Wi‑Fi↔cellular handoff.
     * We schedule recovery with a small delay and a monotonic token so only
     * the most recent change actually runs.
     */
    private var networkRecoverToken = 0
    private val networkRecoverRunnable = Runnable {
        if (!isRunning) return@Runnable
        val pending = pendingNetwork ?: return@Runnable
        pendingNetwork = null
        doRecoverAfterNetworkChange(pending.reason, pending.preferred)
    }
    private class PendingRecovery(val reason: String, val preferred: Network?)
    @Volatile private var pendingNetwork: PendingRecovery? = null

    /**
     * Tracks the physical network currently bound as VPN underlying.
     * Used so we only reset NAT sessions when the *bound* transport actually
     * changes — not when a secondary Wi‑Fi/cell network merely appears.
     */
    private var tunnelStableSinceMs = 0L
    @Volatile private var boundUnderlyingNetId: Int = -1
    @Volatile private var boundUnderlyingTransport: Int = -1

    private val healthCheck = object : Runnable {
        override fun run() {
            if (!isRunning) return
            // Stuck-drop watchdog: never leave blackhole on after pull window.
            if (PullWireFlags.dropping.get()) {
                val armedAt = PullWireFlags.dropArmedAtElapsedMs.get()
                val age = if (armedAt > 0L) {
                    android.os.SystemClock.elapsedRealtime() - armedAt
                } else {
                    DROP_STUCK_MAX_MS + 1
                }
                if (age > DROP_STUCK_MAX_MS) {
                    Log.w(TAG, "dropping stuck ${age}ms → force clear")
                    PullWireFlags.clearDrop()
                    try {
                        engine?.dropping?.set(false)
                    } catch (_: Exception) {
                    }
                }
            }
            val eng = engine
            // Never rebuild mid pull-wire; dropping means intentional silence.
            val pulling = PullWireFlags.dropping.get()
            if (!pulling && (eng == null || !eng.isHealthy())) {
                consecutiveHealthFails++
                Log.w(TAG, "engine unhealthy ($consecutiveHealthFails/$HEALTH_FAIL_THRESHOLD)")
                if (consecutiveHealthFails >= HEALTH_FAIL_THRESHOLD) {
                    consecutiveHealthFails = 0
                    rebuildEngineWorkers("health_check", force = true)
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
                // Bind protect() sockets to a real physical network immediately.
                // Never leave underlying pointing at the VPN itself (routing loop).
                bindUnderlyingToPhysical("tunnel_establish", preferred = null)
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
            markTunnelStable()
            registerNetworkCallback()
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
     * Listen for **physical** network changes only (NOT_VPN).
     *
     * Do NOT use [ConnectivityManager.registerDefaultNetworkCallback]: after the
     * VPN is up the "default" network is often our own VPN agent. Passing that
     * into [setUnderlyingNetworks] creates `underlying={[vpn]}` — a permanent
     * protect() routing loop (EACCES on every connect, Hearthstone "no network").
     *
     * Also: with a multi-network request, onAvailable fires when *any* physical
     * net appears (e.g. Wi‑Fi scan while on cellular). We only recover when the
     * best physical network actually differs from the one we already bound.
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post { onPhysicalNetworkEvent("available", network) }
            }

            override fun onLost(network: Network) {
                handler.post { onPhysicalNetworkEvent("lost", network) }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities
            ) {
                if (!isUsableUnderlying(caps)) return
                // Only care about validated nets becoming preferred, or the
                // currently-bound net changing capability (rare).
                handler.post { onPhysicalNetworkEvent("caps", network) }
            }
        }
        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(req, cb)
            networkCallback = cb
            Log.i(TAG, "network callback registered (physical only)")
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback failed: ${e.message}")
            networkCallback = null
        }
    }

    private fun currentTransport(caps: NetworkCapabilities): Int {
        // Prefer WIFI when both are present on the same Network (rare).
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
            else -> 0
        }
    }

    /** True if [caps] describe a real physical network we may set as underlying. */
    private fun isUsableUnderlying(caps: NetworkCapabilities): Boolean {
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Pick a physical network for [setUnderlyingNetworks].
     * Never returns a VPN network — that would loop protect() into tun0.
     *
     * Preference: [preferred] (if usable) → validated Wi‑Fi → validated cellular
     * → any usable internet net → null.
     */
    private fun resolvePhysicalUnderlying(preferred: Network?): Network? {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return null

        fun usable(n: Network): NetworkCapabilities? {
            val caps = cm.getNetworkCapabilities(n) ?: return null
            return if (isUsableUnderlying(caps)) caps else null
        }

        preferred?.let { if (usable(it) != null) return it }

        val candidates = mutableListOf<Pair<Network, NetworkCapabilities>>()
        for (n in cm.allNetworks) {
            val caps = usable(n) ?: continue
            candidates.add(n to caps)
        }
        if (candidates.isEmpty()) return null

        fun score(caps: NetworkCapabilities): Int {
            var s = 0
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) s += 100
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) s += 30
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) s += 25
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) s += 10
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) s += 5
            return s
        }

        return candidates.maxByOrNull { score(it.second) }?.first
    }

    private fun networkNetId(n: Network): Int = n.toString().toIntOrNull() ?: n.hashCode()

    /**
     * Point the VPN's protect() path at a physical network.
     * Updates [boundUnderlyingNetId] / [boundUnderlyingTransport].
     * @return description of what was applied (for logs)
     */
    private fun bindUnderlyingToPhysical(reason: String, preferred: Network?): String {
        val cm = getSystemService(ConnectivityManager::class.java)
        val physical = resolvePhysicalUnderlying(preferred)
        return try {
            if (physical != null) {
                setUnderlyingNetworks(arrayOf(physical))
                boundUnderlyingNetId = networkNetId(physical)
                val caps = cm?.getNetworkCapabilities(physical)
                boundUnderlyingTransport = if (caps != null) currentTransport(caps) else 0
                "underlying=$physical transport=$boundUnderlyingTransport ($reason)"
            } else {
                // null = "system picks default physical"; never pass VPN self.
                setUnderlyingNetworks(null)
                boundUnderlyingNetId = -1
                boundUnderlyingTransport = 0
                "underlying=null/system ($reason, no physical candidate)"
            }
        } catch (e: Exception) {
            Log.w(TAG, "setUnderlyingNetworks failed: ${e.message}")
            "underlying=FAILED ${e.message}"
        }
    }

    /**
     * Decide whether a physical-network callback warrants rebind + session reset.
     * Secondary nets appearing while the bound one is still fine must be ignored —
     * otherwise mid-match RST kills cause DisconnectAfterFailedPings.
     */
    private fun onPhysicalNetworkEvent(kind: String, eventNetwork: Network) {
        if (!isRunning) return
        if (!tunnelStable()) {
            Log.i(TAG, "physical $kind $eventNetwork ignored (tunnel not stable)")
            return
        }
        val best = resolvePhysicalUnderlying(preferred = null)
        if (best == null) {
            // No physical net left — still rebind to null so protect() is not
            // stuck on a dead Network object.
            if (boundUnderlyingNetId != -1) {
                Log.i(TAG, "physical $kind → no candidate, rebind null")
                scheduleNetworkRecovery("no_physical_after_$kind", null)
            }
            return
        }
        val bestId = networkNetId(best)
        if (bestId == boundUnderlyingNetId) {
            // Bound net still best — ignore secondary wifi/cell noise.
            return
        }
        val cm = getSystemService(ConnectivityManager::class.java)
        val bestCaps = cm?.getNetworkCapabilities(best)
        val bestT = if (bestCaps != null) currentTransport(bestCaps) else 0
        Log.i(
            TAG,
            "physical $kind: bound=$boundUnderlyingNetId→$bestId " +
                "transport=$boundUnderlyingTransport→$bestT → recover"
        )
        scheduleNetworkRecovery("best_${boundUnderlyingNetId}to${bestId}_$kind", best)
    }

    private fun markTunnelStable() {
        tunnelStableSinceMs = System.currentTimeMillis()
    }

    private fun tunnelStable(): Boolean =
        tunnelStableSinceMs > 0L &&
            System.currentTimeMillis() - tunnelStableSinceMs >= TUNNEL_STABLE_GRACE_MS

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
        networkCallback = null
        boundUnderlyingNetId = -1
        boundUnderlyingTransport = -1
    }

    /**
     * Schedule (or reschedule) a coalesced network recovery. Rapid successive
     * changes overwrite [pendingNetwork] and bump the token, so only the latest
     * fires.
     */
    private fun scheduleNetworkRecovery(reason: String, preferred: Network?) {
        handler.removeCallbacks(networkRecoverRunnable)
        pendingNetwork = PendingRecovery(reason, preferred)
        val token = ++networkRecoverToken
        val delay = if (PullWireFlags.dropping.get()) {
            Log.i(TAG, "network change during pull, defer recovery")
            1000L
        } else {
            500L
        }
        handler.postDelayed({
            if (token == networkRecoverToken) networkRecoverRunnable.run()
        }, delay)
    }

    /**
     * Rebind protect() to the current best physical network. Only reset NAT
     * sessions when the bound network id actually changes — rebinding the same
     * net must not RST live game sockets.
     */
    private fun doRecoverAfterNetworkChange(reason: String, preferred: Network?) {
        val prevId = boundUnderlyingNetId
        val applied = bindUnderlyingToPhysical(reason, preferred = preferred)
        val changed = boundUnderlyingNetId != prevId
        if (changed) {
            Log.w(TAG, "recover: $reason → $applied + reset sessions (bound changed)")
            try {
                engine?.resetSessions(sendRst = true)
            } catch (_: Exception) {
            }
        } else {
            Log.i(TAG, "recover: $reason → $applied (bound unchanged, keep sessions)")
        }
        // Never leave pull blackhole on after a network recovery path.
        PullWireFlags.clearDrop()
        try {
            engine?.dropping?.set(false)
        } catch (_: Exception) {
        }
    }

    /**
     * Restart userspace NAT only. Must NOT close [vpnInterface], otherwise
     * Hearthstone sees a full network drop and reconnects by itself.
     */
    private fun rebuildEngineWorkers(reason: String, force: Boolean = false) {
        if (!isRunning && engine == null) return
        val now = System.currentTimeMillis()
        // Debounce: at most one rebuild every 15s to avoid reconnect storms.
        // Network switches / explicit recovery may force.
        if (!force && now - lastRebuildAt < 15_000L) {
            Log.w(TAG, "skip rebuild ($reason), debounced")
            return
        }
        lastRebuildAt = now
        Log.w(TAG, "rebuildEngineWorkers: $reason force=$force")
        val wasDropping = PullWireFlags.dropping.get()
        stopEngineWorkers()
        val err = ensureTunnel(forceRebuild = false)
        if (wasDropping) {
            // Preserve intentional pull blackhole across rebuild.
            PullWireFlags.armDrop()
        }
        if (err != null) {
            // Interface may be dead; last resort full re-establish.
            Log.e(TAG, "worker rebuild failed: $err, trying full tunnel")
            try {
                vpnInterface?.close()
            } catch (_: Exception) {
            }
            vpnInterface = null
            val err2 = ensureTunnel(forceRebuild = false)
            if (wasDropping) PullWireFlags.armDrop()
            if (err2 != null) {
                Log.e(TAG, "full rebuild failed: $err2")
                updateNotification("网络转发异常，请停止后重新启动")
            }
        } else if (!wasDropping) {
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
        // Drop is already armed in PullWireController.tryPull (hot path).
        PullWireFlags.armDrop()

        if (eng == null || !eng.isHealthy()) {
            // Prefer worker-only recovery; do not bounce system VPN mid-match.
            rebuildEngineWorkers("before_pull", force = true)
            eng = engine
            if (eng == null || !eng.isHealthy()) {
                val err = ensureTunnel(forceRebuild = false)
                if (err != null) {
                    PullWireFlags.clearDrop()
                    PullWireController.onPullFailed(this, err)
                    return
                }
                eng = engine
            }
            // Re-arm after rebuild.
            PullWireFlags.armDrop()
        }
        if (eng == null) {
            PullWireFlags.clearDrop()
            PullWireController.onPullFailed(this, "隧道未启动")
            return
        }

        Log.i(TAG, "pull drop ${durationMs}ms")
        // Close NAT sessions once at pull start (with RST so game notices).
        // During the window: pure blackhole. At end: only lift the blackhole
        // and keep the engine — do not RST again or the reconnect itself dies.
        eng.resetSessions(sendRst = true)
        updateNotification("拔线中… ${durationMs}ms")

        handler.removeCallbacks(pullEndRunnable)
        // Hard safety: never blackhole longer than DROP_STUCK_MAX_MS even if
        // durationMs is misconfigured or the first callback is cancelled.
        val endDelay = durationMs.coerceAtMost(DROP_STUCK_MAX_MS)
        handler.postDelayed(pullEndRunnable, endDelay)
        startHealthLoop()
    }

    private val pullEndRunnable = Runnable {
        // Always lift blackhole first — this is the #1 cause of permanent
        // "正在重新连接" if it stays true after the pull window.
        PullWireFlags.clearDrop()
        try {
            engine?.dropping?.set(false)
        } catch (_: Exception) {
        }
        // Soft clear only if engine is dead; healthy engine should accept
        // the game's new connections without another session wipe.
        if (engine == null || engine?.isHealthy() != true) {
            Log.w(TAG, "engine unhealthy after pull → rebuild")
            rebuildEngineWorkers("after_pull", force = true)
            PullWireFlags.clearDrop()
        }
        updateNotification("炉石隧道运行中 · 点悬浮球拔线")
        Log.i(TAG, "pull drop end, forwarding resumed (drop=${PullWireFlags.dropping.get()})")
        PullWireController.onPullFinished(this)
    }

    private fun shutdownAll() {
        handler.removeCallbacks(healthCheck)
        handler.removeCallbacks(pullEndRunnable)
        handler.removeCallbacks(networkRecoverRunnable)
        pendingNetwork = null
        unregisterNetworkCallback()
        PullWireFlags.clearDrop()
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
        handler.removeCallbacks(pullEndRunnable)
        handler.removeCallbacks(networkRecoverRunnable)
        pendingNetwork = null
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        PullWireFlags.clearDrop()
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
