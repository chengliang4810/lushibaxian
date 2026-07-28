package com.lushibaxian.pullwire

import android.content.Context
import kotlin.random.Random

object Prefs {
    private const val NAME = "pull_wire"

    const val HS_PACKAGE = "com.blizzard.wtcg.hearthstone.cn.baidu_sem_dev"

    /**
     * Drop window. Cellular often reacts to ~200ms silence; Wi‑Fi TCP can
     * retransmit longer, so we keep a slightly longer base + RST on pull.
     */
    const val BASE_DURATION_MS = 450L
    const val JITTER_MS = 80L
    const val MIN_DURATION_MS = BASE_DURATION_MS - JITTER_MS // 370
    const val MAX_DURATION_MS = BASE_DURATION_MS + JITTER_MS // 530

    /**
     * Absolute lock from the moment user clicks pull: ignore further clicks
     * so multi-tap does not stack into a reconnect storm on slow Wi‑Fi notice.
     */
    const val LOCKOUT_MS = 4500L

    private fun sp(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /**
     * Random duration in [150, 250] ms so consecutive pulls are not identical.
     */
    fun nextDurationMs(): Long {
        val offset = Random.nextLong(-JITTER_MS, JITTER_MS + 1)
        return (BASE_DURATION_MS + offset).coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
    }

    fun getBallX(context: Context, fallback: Int): Int =
        sp(context).getInt("ball_x", fallback)

    fun getBallY(context: Context, fallback: Int): Int =
        sp(context).getInt("ball_y", fallback)

    fun setBallPosition(context: Context, x: Int, y: Int) {
        sp(context).edit()
            .putInt("ball_x", x)
            .putInt("ball_y", y)
            .apply()
    }

    fun isFloatRunning(context: Context): Boolean =
        sp(context).getBoolean("float_running", false)

    fun setFloatRunning(context: Context, running: Boolean) {
        sp(context).edit().putBoolean("float_running", running).apply()
    }

    fun isVpnRunning(context: Context): Boolean =
        sp(context).getBoolean("vpn_running", false)

    fun setVpnRunning(context: Context, running: Boolean) {
        sp(context).edit().putBoolean("vpn_running", running).apply()
    }

    /** Clear both runtime flags after crash / update / force-stop recovery. */
    fun clearRunningFlags(context: Context) {
        sp(context).edit()
            .putBoolean("float_running", false)
            .putBoolean("vpn_running", false)
            .apply()
    }
}
