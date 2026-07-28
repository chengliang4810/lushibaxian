package com.lushibaxian.pullwire

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hot flags shared between UI click path and VPN engine.
 * Set [dropping] immediately on ball click so TUN packets are discarded
 * without waiting for startForegroundService delivery.
 */
object PullWireFlags {
    val dropping = AtomicBoolean(false)
}
