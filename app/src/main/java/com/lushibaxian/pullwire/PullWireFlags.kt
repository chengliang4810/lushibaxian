package com.lushibaxian.pullwire

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Hot flags shared between UI click path and VPN engine.
 * Set [dropping] immediately on ball click so TUN packets are discarded
 * without waiting for startForegroundService delivery.
 */
object PullWireFlags {
    val dropping = AtomicBoolean(false)

    /** ElapsedRealtime when [dropping] was last set true; 0 if currently false. */
    val dropArmedAtElapsedMs = AtomicLong(0L)

    fun armDrop() {
        dropArmedAtElapsedMs.set(android.os.SystemClock.elapsedRealtime())
        dropping.set(true)
    }

    fun clearDrop() {
        dropping.set(false)
        dropArmedAtElapsedMs.set(0L)
    }
}
