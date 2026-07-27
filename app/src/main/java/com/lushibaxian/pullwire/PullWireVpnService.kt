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
 * - Pull-wire only sets dropping=true for N ms (no teardown)
 * - Avoids "stuck reconnecting to closed VPN" from scheme B
 */
class PullWireVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.lushibaxian.pullwire.action.START"
        const val ACTION_STOP = "com.lushibaxian.pullwire.action.STOP"
        const val ACTION_PULL = "com.lushibaxian.pullwire.action.PULL"
        const val EXTRA_DURATION_MS = "duration_ms"

        private const val TAG = "PullWireVpn"
        private const val CHANNEL_ID = "pull_wire_vpn"
        private const val NOTIF_ID = 1001

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var vpnInterface: ParcelFileDescriptor? = null
    private var engine: VpnEngine? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "stop requested")
                shutdownAll()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PULL -> {
                if (!isRunning || engine == null) {
                    // Auto-start tunnel then pull (for test button).
                    val err = ensureTunnel()
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

    private fun ensureTunnel(): String? {
        if (engine != null && vpnInterface != null) {
            isRunning = true
            startAsForeground("炉石隧道运行中 · 点悬浮球拔线")
            return null
        }
        return try {
            startAsForeground("正在建立炉石隧道…")
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
            val eng = VpnEngine(this, pfd)
            eng.start()
            engine = eng
            isRunning = true
            Prefs.setVpnRunning(this, true)
            updateNotification("炉石隧道运行中 · 点悬浮球拔线")
            Log.i(TAG, "tunnel up, target=${Prefs.HS_PACKAGE}")
            null
        } catch (e: Exception) {
            teardownEngine()
            "VPN 异常: ${e.message}"
        }
    }

    private fun doPull(durationMs: Long) {
        val eng = engine
        if (eng == null) {
            PullWireController.onPullFailed(this, "隧道未启动")
            return
        }
        Log.i(TAG, "pull drop ${durationMs}ms")
        // 1) Drop new packets  2) RST/close existing sessions so HS must reconnect
        eng.dropping.set(true)
        eng.resetSessions(sendRst = true)
        updateNotification("拔线中… ${durationMs}ms")

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            eng.dropping.set(false)
            updateNotification("炉石隧道运行中 · 点悬浮球拔线")
            Log.i(TAG, "pull drop end, forwarding resumed")
            PullWireController.onPullFinished(this)
        }, durationMs)
    }

    private fun shutdownAll() {
        handler.removeCallbacksAndMessages(null)
        teardownEngine()
        isRunning = false
        Prefs.setVpnRunning(this, false)
    }

    private fun teardownEngine() {
        try {
            engine?.stop()
        } catch (_: Exception) {
        }
        engine = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
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
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked by system")
        handler.removeCallbacksAndMessages(null)
        teardownEngine()
        isRunning = false
        Prefs.setVpnRunning(this, false)
        PullWireController.onPullFailed(this, "VPN 被系统撤销")
        stopForegroundCompat()
        stopSelf()
        super.onRevoke()
    }
}
