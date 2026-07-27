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
        // startService is enough for stop action; service may already be running.
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

    /** Reclaim residual system VPN after kill/update (prefer call from UI). */
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
     * @return null if accepted, otherwise a short reason string.
     */
    fun tryPull(context: Context): String? {
        val now = SystemClock.elapsedRealtime()
        when (state) {
            State.PULLING -> return "正在拔线中"
            State.COOLDOWN -> {
                val left = Prefs.COOLDOWN_MS - (now - lastPullAt)
                if (left > 0) return "冷却中 ${left}ms"
            }
            State.IDLE -> Unit
        }

        val duration = Prefs.nextDurationMs()
        state = State.PULLING
        lastPullAt = now
        notifyState(state)
        Log.i(TAG, "pull start, duration=${duration}ms")

        val intent = Intent(context, PullWireVpnService::class.java).apply {
            action = PullWireVpnService.ACTION_PULL
            putExtra(PullWireVpnService.EXTRA_DURATION_MS, duration)
        }
        ContextCompat.startForegroundService(context, intent)
        return null
    }

    fun onPullFinished(context: Context) {
        if (state != State.PULLING) return
        state = State.COOLDOWN
        notifyState(state)
        Log.i(TAG, "pull finished, enter cooldown")

        val app = context.applicationContext
        android.os.Handler(app.mainLooper).postDelayed({
            if (state == State.COOLDOWN) {
                state = State.IDLE
                notifyState(state)
                Log.i(TAG, "cooldown done → IDLE")
            }
        }, Prefs.COOLDOWN_MS)
    }

    fun onPullFailed(context: Context, reason: String) {
        Log.w(TAG, "pull failed: $reason")
        state = State.IDLE
        notifyState(state)
    }
}
