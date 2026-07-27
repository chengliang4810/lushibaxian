package com.lushibaxian.pullwire

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Draggable overlay ball.
 * - Tap: trigger pull-wire
 * - Drag: move position (saved on release)
 * - Shows network latency under the action label
 */
class FloatBallService : Service(), PullWireController.Listener, LatencyProbe.Listener {

    companion object {
        const val ACTION_SHOW = "com.lushibaxian.pullwire.action.SHOW"
        const val ACTION_HIDE = "com.lushibaxian.pullwire.action.HIDE"
        private const val CHANNEL_ID = "pull_wire_float"
        private const val NOTIF_ID = 1002
        private const val CLICK_SLOP_DP = 8f
        private const val CLICK_MAX_MS = 400L
    }

    private lateinit var windowManager: WindowManager
    private var ballView: View? = null
    private var ballRoot: LinearLayout? = null
    private var ballText: TextView? = null
    private var ballLatency: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val latencyTicker = object : Runnable {
        override fun run() {
            LatencyProbe.refreshAsync()
            mainHandler.postDelayed(this, LatencyProbe.INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        PullWireController.addListener(this)
        LatencyProbe.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                stopLatencyTicker()
                removeBall()
                Prefs.setFloatRunning(this, false)
                PullWireController.stopVpn(this)
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startAsForeground()
                Prefs.setFloatRunning(this, true)
                PullWireController.startVpn(this)
                if (ballView == null) {
                    showBall()
                }
                startLatencyTicker()
            }
        }
        return START_STICKY
    }

    private fun startLatencyTicker() {
        mainHandler.removeCallbacks(latencyTicker)
        LatencyProbe.refreshAsync()
        mainHandler.postDelayed(latencyTicker, LatencyProbe.INTERVAL_MS)
    }

    private fun stopLatencyTicker() {
        mainHandler.removeCallbacks(latencyTicker)
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun showBall() {
        if (ballView != null) return

        val view = LayoutInflater.from(this).inflate(R.layout.view_float_ball, null)
        ballRoot = view.findViewById(R.id.ballRoot)
        ballText = view.findViewById(R.id.tvBall)
        ballLatency = view.findViewById(R.id.tvBallLatency)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)

        val sizePx = (60 * metrics.density).toInt()
        val defaultX = metrics.widthPixels - sizePx - (16 * metrics.density).toInt()
        val defaultY = (metrics.heightPixels * 0.35f).toInt()

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.getBallX(this@FloatBallService, defaultX)
            y = Prefs.getBallY(this@FloatBallService, defaultY)
        }

        var downX = 0f
        var downY = 0f
        var startParamX = 0
        var startParamY = 0
        var downTime = 0L
        var dragging = false
        val slop = CLICK_SLOP_DP * metrics.density

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startParamX = params.x
                    startParamY = params.y
                    downTime = System.currentTimeMillis()
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = (startParamX + dx).toInt()
                            .coerceIn(0, metrics.widthPixels - sizePx)
                        params.y = (startParamY + dy).toInt()
                            .coerceIn(0, metrics.heightPixels - sizePx)
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        Prefs.setBallPosition(this, params.x, params.y)
                    } else {
                        val elapsed = System.currentTimeMillis() - downTime
                        if (elapsed <= CLICK_MAX_MS) {
                            onBallClick()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(view, params)
            ballView = view
            layoutParams = params
            applyState(PullWireController.state)
            renderLatency(LatencyProbe.lastRttMs())
        } catch (e: Exception) {
            Toast.makeText(this, "悬浮球显示失败: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun onBallClick() {
        val err = PullWireController.tryPull(this)
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "拔线中…", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeBall() {
        ballView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        ballView = null
        ballRoot = null
        ballText = null
        ballLatency = null
        layoutParams = null
    }

    override fun onStateChanged(state: PullWireController.State) {
        mainHandler.post { applyState(state) }
    }

    override fun onRtt(rttMs: Long) {
        mainHandler.post { renderLatency(rttMs) }
    }

    private fun renderLatency(rttMs: Long) {
        val tv = ballLatency ?: return
        if (rttMs < 0) {
            tv.text = "— ms"
            tv.setTextColor(ContextCompat.getColor(this, R.color.white))
            tv.alpha = 0.7f
            return
        }
        tv.alpha = 1f
        tv.text = "${rttMs}ms"
        val colorRes = when {
            rttMs < 80 -> R.color.signal_live
            rttMs < 150 -> R.color.accent_copper
            else -> R.color.signal_warn
        }
        tv.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun applyState(state: PullWireController.State) {
        val tv = ballText ?: return
        val colorRes = when (state) {
            PullWireController.State.IDLE -> R.color.ball_idle
            PullWireController.State.PULLING -> R.color.ball_pulling
            PullWireController.State.COOLDOWN -> R.color.ball_cooldown
        }
        val label = when (state) {
            PullWireController.State.IDLE -> getString(R.string.float_ball_label)
            PullWireController.State.PULLING -> "断"
            PullWireController.State.COOLDOWN -> "…"
        }
        tv.text = label

        val target = ballRoot ?: tv
        val bg = target.background
        if (bg is GradientDrawable) {
            bg.setColor(ContextCompat.getColor(this, colorRes))
        } else {
            (target.background?.mutate() as? GradientDrawable)
                ?.setColor(ContextCompat.getColor(this, colorRes))
                ?: run {
                    val d = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(ContextCompat.getColor(this@FloatBallService, colorRes))
                    }
                    target.background = d
                }
        }
    }

    private fun startAsForeground() {
        ensureChannel()
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

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
        stopLatencyTicker()
        PullWireController.removeListener(this)
        LatencyProbe.removeListener(this)
        removeBall()
        Prefs.setFloatRunning(this, false)
        // If process/service dies without explicit Stop, tear down VPN so
        // Hearthstone is not left on a dead tunnel.
        try {
            PullWireController.stopVpn(this)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
