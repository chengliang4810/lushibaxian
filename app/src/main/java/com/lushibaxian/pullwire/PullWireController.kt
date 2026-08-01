package com.lushibaxian.pullwire

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Global pull-wire state machine.
 * IDLE → PULLING → COOLDOWN → IDLE
 *
 * Scheme A: pull only toggles packet drop on the persistent VPN.
 */
object PullWireController {
    private const val TAG = "PullWireController"

    enum class State {
        IDLE,
        PULLING,
        COOLDOWN
    }

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    private var lastPullAt: Long = 0L

    interface Listener {
        fun onStateChanged(state: State)
    }

    private val listeners = mutableSetOf<Listener>()

    fun addListener(listener: Listener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun notifyState(state: State) {
        val copy: List<Listener>
        synchronized(listeners) { copy = listeners.toList() }
        copy.forEach { it.onStateChanged(state) }
    }

    fun startVpn(context: Context) {
        val intent = Intent(context, PullWireVpnService::class.java).apply {
            action = PullWireVpnService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopVpn(context: Context) {
        val intent = Intent(context, PullWireVpnService::class.java).apply {
            action = PullWireVpnService.ACTION_STOP
        }
        try {
            context.startService(intent)
        } catch (_: Exception) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "stopVpn failed: ${e.message}")
            }
        }
    }

    fun cleanupVpn(context: Context) {
        val intent = Intent(context, PullWireVpnService::class.java).apply {
            action = PullWireVpnService.ACTION_CLEANUP
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.w(TAG, "cleanupVpn failed: ${e.message}")
            stopVpn(context)
        }
    }

    /**
     * @return null if accepted, otherwise a short reason (may be empty for silent ignore).
     */
    fun tryPull(context: Context): String? {
        val now = SystemClock.elapsedRealtime()

        // Absolute lock from first click — critical on Wi‑Fi where game may
        // only show reconnect seconds later; multi-tap would stack storms.
        if (lastPullAt > 0L && now - lastPullAt < Prefs.LOCKOUT_MS) {
            return when (state) {
                State.PULLING -> "正在拔线中"
                State.COOLDOWN -> "请稍候"
                State.IDLE -> "请稍候"
            }
        }
        if (state == State.PULLING) return "正在拔线中"

        val duration = Prefs.nextDurationMs()
        state = State.PULLING
        lastPullAt = now
        notifyState(state)

        // Hot path: drop immediately, before service intent is delivered.
        PullWireFlags.armDrop()
        Log.i(TAG, "pull start, duration=${duration}ms (drop armed immediately)")

        val intent = Intent(context, PullWireVpnService::class.java).apply {
            action = PullWireVpnService.ACTION_PULL
            putExtra(PullWireVpnService.EXTRA_DURATION_MS, duration)
        }
        // Prefer startService if VPN FGS already running (avoids FGS start lag).
        try {
            context.startService(intent)
        } catch (_: Exception) {
            ContextCompat.startForegroundService(context, intent)
        }
        return null
    }

    fun onPullFinished(context: Context) {
        if (state != State.PULLING) {
            // Still clear drop if service finished late.
            PullWireFlags.clearDrop()
            return
        }
        state = State.COOLDOWN
        notifyState(state)
        Log.i(TAG, "pull finished, enter cooldown")

        val app = context.applicationContext
        val now = SystemClock.elapsedRealtime()
        val remainLock = (Prefs.LOCKOUT_MS - (now - lastPullAt)).coerceAtLeast(0L)
        android.os.Handler(app.mainLooper).postDelayed({
            if (state == State.COOLDOWN) {
                state = State.IDLE
                notifyState(state)
                Log.i(TAG, "cooldown done → IDLE")
            }
        }, remainLock)
    }

    fun onPullFailed(context: Context, reason: String) {
        Log.w(TAG, "pull failed: $reason")
        PullWireFlags.clearDrop()
        state = State.IDLE
        notifyState(state)
    }
}
