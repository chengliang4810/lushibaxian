package com.lushibaxian.pullwire

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/**
 * Measures generic network RTT via TCP connect.
 *
 * Note: this is NOT Hearthstone game-server latency. Our VPN only tunnels HS,
 * and the game protocol does not expose easy RTT without packet timing analysis.
 * This probe reflects the device's general path quality (useful as a health signal).
 */
object LatencyProbe {
    const val INTERVAL_MS = 2_000L

    fun interface Listener {
        fun onRtt(rttMs: Long)
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "latency-probe").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()

    /** -1 = unknown / failed, otherwise ms */
    private val lastRttMs = AtomicLong(-1L)

    @Volatile
    private var inFlight: Future<*>? = null

    fun lastRttMs(): Long = lastRttMs.get()

    fun addListener(listener: Listener) {
        listeners.add(listener)
        val cached = lastRttMs.get()
        if (cached >= 0) {
            mainHandler.post { listener.onRtt(cached) }
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun refreshAsync(onDone: ((Long) -> Unit)? = null) {
        if (inFlight?.isDone == false) return
        inFlight = executor.submit {
            val rtt = measureOnce()
            lastRttMs.set(rtt)
            mainHandler.post {
                listeners.forEach { it.onRtt(rtt) }
                onDone?.invoke(rtt)
            }
        }
    }

    private fun measureOnce(): Long {
        // Ali DNS is reachable in CN; TCP/53 connect RTT is a decent proxy.
        val hosts = listOf(
            "223.5.5.5" to 53,
            "1.1.1.1" to 53,
            "8.8.8.8" to 53
        )
        for ((host, port) in hosts) {
            val rtt = tcpConnectRtt(host, port, timeoutMs = 2000)
            if (rtt >= 0) return rtt
        }
        return -1L
    }

    private fun tcpConnectRtt(host: String, port: Int, timeoutMs: Int): Long {
        val start = SystemClock.elapsedRealtime()
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            (SystemClock.elapsedRealtime() - start).coerceAtLeast(1L)
        } catch (_: Exception) {
            -1L
        }
    }
}
