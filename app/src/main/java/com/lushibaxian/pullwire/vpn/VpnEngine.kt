package com.lushibaxian.pullwire.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.lushibaxian.pullwire.PullWireFlags
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.CancelledKeyException
import java.nio.channels.ClosedChannelException
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Userspace IPv4 NAT for a single-app VPN.
 *
 * Threading model (important for stability across many pull-wire ops):
 * - tun-read: read TUN → open/write remote sockets (registrations queued)
 * - select: process registrations + remote→TUN
 * - writer: queue → TUN
 * - All session map mutations for close/reset happen on the select thread
 *   (or via the concurrent maps) to avoid selector corruption.
 *
 * Performance model:
 * - Session keys are ([srcPort], packed dst [Int], [dstPort]) so the hot lookup
 *   path never allocates [java.net.InetAddress] and is lock-free via
 *   [ConcurrentHashMap] (P2/P3).
 * - TUN read, UDP/TCP remote reads, and TUN→remote writes reuse thread- or
 *   session-local buffers instead of allocating per packet (P0).
 * - Outbound (remote→client) packets are built into buffers drawn from a small
 *   free-list pool ([borrowBuild]/[recycleBuild]); the writer returns them
 *   after writing. Reusing one shared build buffer would race the builder and
 *   the writer across the queue, so a pool is the correct structure (P0).
 * - The writer drains each queued [ByteBuffer] through its own backing array —
 *   no extra copy (P1).
 * - Idle reaping is rate-limited instead of running every select iteration,
 *   since select wakes on every packet/wakeup (P4).
 * - TCP backpressure advances the client ACK by the bytes actually accepted
 *   into the pipeline (written to remote + buffered), not just bytes flushed,
 *   so the client does not retransmit already-buffered data (C1).
 */
class VpnEngine(
    private val vpnService: VpnService,
    private val vpnInterface: ParcelFileDescriptor,
    private val onEngineDead: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "VpnEngine"
        private const val TUN_MTU = 1500
        private const val CLEANUP_INTERVAL_MS = 5_000L
        private const val BUILD_BUF_SIZE = Packet.IP4_HEADER_SIZE + Packet.TCP_HEADER_SIZE + TUN_MTU
        private const val POOL_HIGH_WATERMARK = 64
        /** Full stack for UDP open fails at most once per this window. */
        private const val UDP_OPEN_FAIL_LOG_INTERVAL_MS = 30_000L
        /**
         * Hearthstone Pegasus game-server TCP port (client→server).
         * CN client logs show only :3724 for game servers.
         *
         * Do NOT include 1119 — that is a classic Battle.net port; treating it
         * as "game" RST'd the login WebSocket and caused FATAL Bnet on every pull.
         * BattleNet / HTTPS (443 etc.) must stay open across pull-wire.
         */
        private val GAME_TCP_PORTS = intArrayOf(3724)
        private val CLIENT_IP: Int = Packet.packInetAddress(
            java.net.InetAddress.getByName("10.0.0.2")
        )

        /** True if [port] is a Hearthstone game-server port (not BattleNet). */
        fun isGameTcpPort(port: Int): Boolean {
            for (p in GAME_TCP_PORTS) if (p == port) return true
            return false
        }
    }

    private val lastUdpOpenFailLogMs = AtomicLong(0L)
    private val suppressedUdpOpenFails = AtomicLong(0L)

    /** Legacy local flag; prefer [PullWireFlags.dropping] for hot path. */
    val dropping = AtomicBoolean(false)

    private fun isDropping(): Boolean =
        PullWireFlags.dropping.get() || dropping.get()

    private val running = AtomicBoolean(false)
    private val deadNotified = AtomicBoolean(false)

    @Volatile
    private var selector: Selector = Selector.open()

    private val tunOutQueue = LinkedBlockingQueue<ByteBuffer>(2048)
    private val registerQueue = ConcurrentLinkedQueue<RegisterOp>()
    private val closeQueue = ConcurrentLinkedQueue<CloseOp>()

    private val udpSessions = ConcurrentHashMap<UdpKey, UdpSession>()
    private val tcpSessions = ConcurrentHashMap<TcpKey, TcpSession>()

    private var tunThread: Thread? = null
    private var selectThread: Thread? = null
    private var writerThread: Thread? = null

    private val lastTunActivityMs = AtomicLong(System.currentTimeMillis())
    private val lastSelectOkMs = AtomicLong(System.currentTimeMillis())
    // Touched only on the single select thread; no atomics needed.
    private var lastCleanupMs = 0L

    /** Free-list of reusable outbound-packet buffers (P0). */
    private val buildPool = ConcurrentLinkedQueue<ByteBuffer>()

    private fun borrowBuild(): ByteBuffer =
        buildPool.poll() ?: ByteBuffer.allocate(BUILD_BUF_SIZE).order(ByteOrder.BIG_ENDIAN)

    private fun recycleBuild(buf: ByteBuffer) {
        if (buf.capacity() == BUILD_BUF_SIZE && buildPool.size < POOL_HIGH_WATERMARK) {
            buildPool.offer(buf)
        }
    }

    private sealed class RegisterOp {
        data class Udp(val key: UdpKey, val channel: DatagramChannel) : RegisterOp()
        data class TcpConnect(val key: TcpKey, val channel: SocketChannel, val session: TcpSession) : RegisterOp()
    }

    private sealed class CloseOp {
        data class Udp(val key: UdpKey) : CloseOp()
        data class Tcp(val key: TcpKey, val sendRst: Boolean) : CloseOp()
        data class ResetAll(val sendRst: Boolean) : CloseOp()
        /** Close only game-server sessions; leave BattleNet / other TCP alone. */
        data class ResetGame(val sendRst: Boolean) : CloseOp()
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        deadNotified.set(false)
        Log.i(TAG, "engine start")
        lastTunActivityMs.set(System.currentTimeMillis())
        lastSelectOkMs.set(System.currentTimeMillis())
        lastCleanupMs = System.currentTimeMillis()
        writerThread = thread("vpn-writer") { writerLoop() }
        selectThread = thread("vpn-select") { selectLoop() }
        tunThread = thread("vpn-tun-read") { tunReadLoop() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        Log.i(TAG, "engine stop")
        try {
            selector.wakeup()
        } catch (_: Exception) {
        }
        // Unblock writer with a one-off sentinel (not pooled).
        tunOutQueue.offer(ByteBuffer.allocate(0))
        closeAllSessionsNow(sendRst = false)
        try {
            selector.close()
        } catch (_: Exception) {
        }
        tunThread = null
        selectThread = null
        writerThread = null
    }

    /**
     * Soft health: threads still alive and selector open.
     */
    fun isHealthy(): Boolean {
        if (!running.get()) return false
        val tun = tunThread
        val sel = selectThread
        val wr = writerThread
        if (tun == null || !tun.isAlive) return false
        if (sel == null || !sel.isAlive) return false
        if (wr == null || !wr.isAlive) return false
        return try {
            selector.isOpen
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Queue a full session reset on the select thread (safe).
     * Use after real network handoff when all sockets may be dead.
     * Prefer [resetGameSessions] for intentional pull-wire.
     */
    fun resetSessions(sendRst: Boolean = true) {
        closeQueue.offer(CloseOp.ResetAll(sendRst))
        // Drop pending TUN writes from old sessions (all may be stale).
        tunOutQueue.clear()
        try {
            selector.wakeup()
        } catch (_: Exception) {
        }
        Log.i(TAG, "sessions reset queued (sendRst=$sendRst)")
    }

    /**
     * Pull-wire only: RST/close Hearthstone **game** TCP sessions (port 3724).
     * BattleNet WebSocket and other traffic stay open so post-match login is not
     * forced into FATAL `ERROR_SDK_SOCKET_CLOSED` / `ERROR_SDK_TASK_CANCELLED`.
     */
    fun resetGameSessions(sendRst: Boolean = true) {
        closeQueue.offer(CloseOp.ResetGame(sendRst))
        // Do NOT clear tunOutQueue — BattleNet replies may already be queued.
        try {
            selector.wakeup()
        } catch (_: Exception) {
        }
        Log.i(TAG, "game sessions reset queued (sendRst=$sendRst)")
    }

    private fun thread(name: String, block: () -> Unit): Thread =
        Thread({
            try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "$name crashed: ${t.message}", t)
            } finally {
                if (running.get()) {
                    Log.w(TAG, "$name exited while running")
                    notifyDead()
                }
            }
        }, name).also {
            it.isDaemon = true
            it.start()
        }

    private fun notifyDead() {
        if (!running.get()) return
        if (!deadNotified.compareAndSet(false, true)) return
        running.set(false)
        try {
            onEngineDead?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "onEngineDead: ${e.message}")
        }
    }

    private fun tunReadLoop() {
        val input = FileInputStream(vpnInterface.fileDescriptor)
        val packet = ByteArray(TUN_MTU)
        // Reusable buffer for one parsed packet (P0): no per-packet copy/array.
        val parseBuf = ByteBuffer.allocate(TUN_MTU).order(ByteOrder.BIG_ENDIAN)
        try {
            while (running.get()) {
                val length = try {
                    input.read(packet)
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "tun read error: ${e.message}")
                    break
                }
                if (length <= 0) {
                    Thread.sleep(2)
                    continue
                }
                lastTunActivityMs.set(System.currentTimeMillis())
                parseBuf.clear()
                parseBuf.put(packet, 0, length)
                parseBuf.flip()
                // While pulling: blackhole only game-server traffic. BattleNet
                // and other sockets keep flowing so login survives the pull.
                if (isDropping()) {
                    val ip = Packet.parseIp4(parseBuf)
                    if (ip != null && isGameBoundFromClient(parseBuf, ip)) continue
                    parseBuf.rewind()
                }
                handleTunPacket(parseBuf)
            }
        } catch (e: Exception) {
            if (running.get()) Log.w(TAG, "tun read ended: ${e.message}")
        }
    }

    private fun writerLoop() {
        val output = FileOutputStream(vpnInterface.fileDescriptor)
        try {
            while (running.get()) {
                val buf = tunOutQueue.take()
                if (!running.get()) break
                if (!buf.hasRemaining()) continue // sentinel
                try {
                    // P1: write the ByteBuffer's own backing-array slice directly,
                    // no per-packet allocation or extra memcpy.
                    if (buf.hasArray()) {
                        output.write(buf.array(), buf.arrayOffset() + buf.position(), buf.remaining())
                    } else {
                        val arr = ByteArray(buf.remaining())
                        buf.get(arr)
                        output.write(arr)
                    }
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "tun write: ${e.message}")
                } finally {
                    // Return pooled build buffers to the free-list (P0).
                    recycleBuild(buf)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (running.get()) Log.w(TAG, "writer ended: ${e.message}")
        }
    }

    private fun enqueueToTun(packet: ByteBuffer) {
        // Selective blackhole is applied at the game-session sources
        // (tun-read for client→game, readTcp for game→client). Do not drop
        // all outbound here — that used to kill BattleNet during pull.
        if (!running.get()) {
            recycleBuild(packet)
            return
        }
        if (!tunOutQueue.offer(packet)) {
            // Prefer dropping outbound-to-client under pressure over blocking.
            Log.w(TAG, "tun out queue full, drop")
            recycleBuild(packet)
        }
    }

    private fun handleTunPacket(buf: ByteBuffer) {
        val ip = Packet.parseIp4(buf) ?: return
        when (ip.protocol) {
            Packet.PROTO_UDP -> handleUdpFromTun(buf, ip)
            Packet.PROTO_TCP -> handleTcpFromTun(buf, ip)
            else -> Unit
        }
    }

    /** Client→server packet destined to a game-server TCP port. */
    private fun isGameBoundFromClient(buf: ByteBuffer, ip: Packet.Ip4): Boolean {
        if (ip.protocol != Packet.PROTO_TCP) return false
        val tcp = Packet.parseTcp(buf, ip) ?: return false
        return isGameTcpPort(tcp.destPort)
    }

    // ---------------- UDP ----------------

    private data class UdpKey(
        val srcPort: Int,
        val dst: Int,
        val dstPort: Int
    )

    private class UdpSession(
        val key: UdpKey,
        val channel: DatagramChannel
    ) {
        @Volatile
        var lastActive = System.currentTimeMillis()
        // Reusable scratch for TUN→remote writes (tun-read thread).
        val writeScratch: ByteArray = ByteArray(TUN_MTU)
        // Reusable buffer/scratch for remote→TUN reads (select thread).
        val readBuf: ByteBuffer = ByteBuffer.allocate(TUN_MTU)
        val readScratch: ByteArray = ByteArray(TUN_MTU)

        fun closeQuietly() {
            try {
                channel.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun logUdpOpenFail(dst: Int, port: Int, e: Exception) {
        val now = System.currentTimeMillis()
        val last = lastUdpOpenFailLogMs.get()
        if (now - last >= UDP_OPEN_FAIL_LOG_INTERVAL_MS &&
            lastUdpOpenFailLogMs.compareAndSet(last, now)
        ) {
            val skipped = suppressedUdpOpenFails.getAndSet(0L)
            val extra = if (skipped > 0) " (+$skipped suppressed)" else ""
            Log.w(
                TAG,
                "udp open fail: ${e.message} (open/connect to ${Packet.formatIp(dst)}:$port)$extra",
                e
            )
        } else {
            suppressedUdpOpenFails.incrementAndGet()
        }
    }

    private fun handleUdpFromTun(buf: ByteBuffer, ip: Packet.Ip4) {
        val udp = Packet.parseUdp(buf, ip) ?: return
        // Skip broadcast/multicast — connect() to 255.255.255.255 or 224.0.0.0/4
        // always throws EACCES. Hearthstone sends periodic LAN discovery
        // broadcasts; silently ignoring them avoids log noise and wasted sessions.
        if (Packet.isBroadcastOrMulticast(ip.destination)) return
        val key = UdpKey(udp.sourcePort, ip.destination, udp.destPort)

        var session = udpSessions[key]
        if (session == null) {
            try {
                val ch = DatagramChannel.open()
                ch.configureBlocking(false)
                val protectedOk = vpnService.protect(ch.socket())
                if (!protectedOk) {
                    Log.w(TAG, "protect() failed for udp (vpn revoked?)")
                    ch.close()
                    return
                }
                ch.connect(InetSocketAddress(Packet.inetAddress(ip.destination), udp.destPort))
                val created = UdpSession(key, ch)
                // putIfAbsent is the single writer for this key; the first winner
                // also gets to register with the selector.
                val winner = udpSessions.putIfAbsent(key, created)
                if (winner != null) {
                    ch.close()
                    session = winner
                } else {
                    session = created
                    registerQueue.offer(RegisterOp.Udp(key, ch))
                    selector.wakeup()
                }
            } catch (e: Exception) {
                // connect() EACCES usually means protect() routing is broken
                // (underlying set to the VPN itself). Rate-limit stack traces so
                // a flood of failed opens cannot wash critical service logs.
                logUdpOpenFail(ip.destination, udp.destPort, e)
                return
            }
        }

        val s = session
        s.lastActive = System.currentTimeMillis()
        val payloadLen = udp.payloadLength
        if (payloadLen <= 0) return
        try {
            // Reuse per-session scratch instead of allocating (P0).
            Packet.copyPayload(buf, udp.payloadOffset, payloadLen, s.writeScratch)
            val n = s.channel.write(ByteBuffer.wrap(s.writeScratch, 0, payloadLen))
            if (n < 0) {
                closeQueue.offer(CloseOp.Udp(key))
                selector.wakeup()
            }
        } catch (e: Exception) {
            Log.w(TAG, "udp write fail: ${e.message}")
            closeQueue.offer(CloseOp.Udp(key))
            selector.wakeup()
        }
    }

    // ---------------- TCP ----------------

    private data class TcpKey(
        val srcPort: Int,
        val dst: Int,
        val dstPort: Int
    )

    private enum class TcpState {
        CONNECTING,
        ESTABLISHED,
        LAST_ACK,
        CLOSED
    }

    private class TcpSession(
        val key: TcpKey,
        val channel: SocketChannel,
        var state: TcpState,
        var mySeq: Long,
        var clientNextSeq: Long,
        var myAck: Long
    ) {
        val readBuf = ByteBuffer.allocate(TUN_MTU)
        val pendingOut = ByteBuffer.allocate(TUN_MTU * 4)
        // Reusable scratch for remote→TUN reads (select thread).
        val readScratch: ByteArray = ByteArray(TUN_MTU)
        @Volatile
        var lastActive = System.currentTimeMillis()

        /**
         * Guards [pendingOut], [channel] writes (flushTcpPending / write /
         * shutdownOutput), and the ACK state ([clientNextSeq]/[myAck]) that is
         * derived from write results. The TUN→remote write happens on the
         * tun-read thread; the OP_WRITE drain (writeTcpPending) happens on the
         * select thread. Both must take this lock so a backpressure-flush from
         * one thread cannot interleave with a payload write from the other.
         */
        val lock = Any()

        fun closeQuietly() {
            try {
                channel.close()
            } catch (_: Exception) {
            }
            state = TcpState.CLOSED
        }
    }

    private fun handleTcpFromTun(buf: ByteBuffer, ip: Packet.Ip4) {
        val tcp = Packet.parseTcp(buf, ip) ?: return
        val key = TcpKey(tcp.sourcePort, ip.destination, tcp.destPort)

        if (tcp.isRST) {
            closeQueue.offer(CloseOp.Tcp(key, sendRst = false))
            selector.wakeup()
            return
        }

        var session = tcpSessions[key]

        if (session == null) {
            if (!tcp.isSYN || tcp.isACK) return
            try {
                val ch = SocketChannel.open()
                ch.configureBlocking(false)
                if (!vpnService.protect(ch.socket())) {
                    Log.w(TAG, "protect() failed for tcp")
                    ch.close()
                    sendRst(ip, tcp)
                    return
                }
                val connected = ch.connect(InetSocketAddress(Packet.inetAddress(ip.destination), tcp.destPort))
                val mySeq = Random.nextInt().toLong() and 0xFFFFFFFFL
                val clientNext = (tcp.seq + 1) and 0xFFFFFFFFL
                val created = TcpSession(
                    key = key,
                    channel = ch,
                    state = if (connected) TcpState.ESTABLISHED else TcpState.CONNECTING,
                    mySeq = mySeq,
                    clientNextSeq = clientNext,
                    myAck = clientNext
                )
                val winner = tcpSessions.putIfAbsent(key, created)
                if (winner != null) {
                    ch.close()
                    return
                }
                session = created
                registerQueue.offer(RegisterOp.TcpConnect(key, ch, created))
                selector.wakeup()
                if (connected) {
                    sendSynAck(created)
                }
            } catch (e: Exception) {
                Log.w(TAG, "tcp open fail ${Packet.formatIp(ip.destination)}:${tcp.destPort}: ${e.message}")
                sendRst(ip, tcp)
            }
            return
        }

        val s = session
        s.lastActive = System.currentTimeMillis()

        if (tcp.isFIN) {
            s.clientNextSeq = (tcp.seq + 1 + tcp.payloadLength) and 0xFFFFFFFFL
            s.myAck = s.clientNextSeq
            enqueueClientTcp(s, flags = 0x10)
            // Half-close the remote send direction so the server learns the
            // game stopped sending. Done under the session lock so it cannot
            // race a concurrent payload write / OP_WRITE drain.
            try {
                synchronized(s.lock) {
                    flushTcpPendingLocked(s)
                    s.channel.shutdownOutput()
                }
            } catch (_: Exception) {
            }
            enqueueClientTcp(s, flags = 0x11)
            s.mySeq = (s.mySeq + 1) and 0xFFFFFFFFL
            s.state = TcpState.LAST_ACK
            return
        }

        if (tcp.payloadLength > 0) {
            // Strict-ish: accept current expected seq, or retransmit (seq < expected).
            val rel = seqCompare(tcp.seq, s.clientNextSeq)
            if (rel > 0) {
                // Future data — dup-ACK to trigger retransmit from client.
                enqueueClientTcp(s, flags = 0x10)
                return
            }
            if (rel < 0) {
                // Old retransmit — ACK current.
                enqueueClientTcp(s, flags = 0x10)
                return
            }

            val payloadLen = tcp.payloadLength
            val payload = ByteArray(payloadLen)
            Packet.copyPayload(buf, tcp.payloadOffset, payloadLen, payload)
            // Synchronous write on the tun-read thread, under the session lock.
            // ACK correctness depends on this being synchronous: we must know
            // exactly how many bytes the remote actually accepted (written) plus
            // how many we could buffer (pendingOut), and ACK ONLY that. Bytes we
            // could neither write nor buffer are left un-ACKed so the client
            // retransmits them — never ACK bytes that may be dropped, or a
            // server-side handshake corruption forces a re-login.
            try {
                synchronized(s.lock) {
                    // Drain previously buffered bytes first to keep ordering.
                    flushTcpPendingLocked(s)
                    val written = s.channel.write(ByteBuffer.wrap(payload, 0, payloadLen))
                        .coerceAtLeast(0)
                    var buffered = 0
                    if (written < payloadLen) {
                        val left = payloadLen - written
                        if (s.pendingOut.remaining() >= left) {
                            s.pendingOut.put(payload, written, left)
                            buffered = left
                            updateInterestLater(s) // arm OP_WRITE to drain pendingOut
                        }
                        // pendingOut full → `left` bytes are dropped AND NOT acked
                        // → client retransmits them once pendingOut drains.
                    }
                    val accepted = written + buffered
                    if (accepted > 0) {
                        s.clientNextSeq = (s.clientNextSeq + accepted) and 0xFFFFFFFFL
                        s.myAck = s.clientNextSeq
                    }
                }
                enqueueClientTcp(s, flags = 0x10)
            } catch (e: Exception) {
                Log.w(TAG, "tcp write fail: ${e.message}")
                closeQueue.offer(CloseOp.Tcp(key, sendRst = true))
                selector.wakeup()
            }
        } else if (tcp.isACK && s.state == TcpState.LAST_ACK) {
            closeQueue.offer(CloseOp.Tcp(key, sendRst = false))
            selector.wakeup()
        }
    }

    private fun seqCompare(a: Long, b: Long): Int {
        return when {
            a == b -> 0
            ((a - b) and 0xFFFFFFFFL) < 0x80000000L -> 1
            else -> -1
        }
    }

    private val interestQueue = ConcurrentLinkedQueue<TcpSession>()

    private fun updateInterestLater(session: TcpSession) {
        interestQueue.offer(session)
        try {
            selector.wakeup()
        } catch (_: Exception) {
        }
    }

    /**
     * Drain [session.pendingOut] to the remote channel. Caller MUST hold
     * [TcpSession.lock] — both the tun-read payload path and the select-thread
     * OP_WRITE path use this, and they must not interleave.
     */
    private fun flushTcpPendingLocked(session: TcpSession) {
        if (session.pendingOut.position() == 0) return
        session.pendingOut.flip()
        try {
            session.channel.write(session.pendingOut)
        } catch (_: Exception) {
        }
        session.pendingOut.compact()
    }

    /** Build a remote→client TCP packet into a pooled buffer and enqueue it. */
    private fun enqueueClientTcp(
        s: TcpSession,
        flags: Int,
        seq: Long = s.mySeq,
        ack: Long = s.myAck,
        window: Int = 65535,
        payload: ByteArray? = null,
        payloadOffset: Int = 0,
        payloadLength: Int = payload?.size ?: 0,
        bypassDrop: Boolean = false
    ) {
        val buf = borrowBuild()
        Packet.buildIp4Tcp(
            buf,
            src = s.key.dst,
            dst = CLIENT_IP,
            srcPort = s.key.dstPort,
            dstPort = s.key.srcPort,
            seq = seq,
            ack = ack,
            flags = flags,
            window = window,
            payload = payload,
            payloadOffset = payloadOffset,
            payloadLength = payloadLength
        )
        if (bypassDrop) {
            // RST must bypass the drop blackhole so the game notices immediately.
            if (!tunOutQueue.offer(buf)) {
                Log.w(TAG, "RST enqueue failed")
                recycleBuild(buf)
            }
        } else {
            enqueueToTun(buf)
        }
    }

    private fun sendSynAck(session: TcpSession) {
        enqueueClientTcp(session, flags = 0x12)
        session.mySeq = (session.mySeq + 1) and 0xFFFFFFFFL
        session.state = TcpState.ESTABLISHED
    }

    private fun sendRst(ip: Packet.Ip4, tcp: Packet.Tcp) {
        val buf = borrowBuild()
        Packet.buildIp4Tcp(
            buf,
            src = ip.destination,
            dst = ip.source,
            srcPort = tcp.destPort,
            dstPort = tcp.sourcePort,
            seq = 0,
            ack = (tcp.seq + 1) and 0xFFFFFFFFL,
            flags = 0x14,
            window = 0
        )
        // RST to client must bypass pull blackhole (same as sendRstToClient).
        if (!tunOutQueue.offer(buf)) {
            Log.w(TAG, "RST enqueue failed")
            recycleBuild(buf)
        }
    }

    private fun sendRstToClient(session: TcpSession) {
        if (!running.get()) return
        enqueueClientTcp(session, flags = 0x14, window = 0, bypassDrop = true)
    }

    // ---------------- select loop ----------------

    private fun selectLoop() {
        try {
            while (running.get()) {
                drainCloseOps()
                drainRegisters()
                drainInterest()

                val n = try {
                    selector.select(200)
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "select error: ${e.message}")
                    break
                }
                lastSelectOkMs.set(System.currentTimeMillis())

                // Always drain after select (including timeout) for registrations.
                drainCloseOps()
                drainRegisters()
                drainInterest()

                if (n > 0) {
                    val keys = selector.selectedKeys().iterator()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        keys.remove()
                        if (!key.isValid) continue
                        try {
                            when (val att = key.attachment()) {
                                is UdpKey -> if (key.isReadable) readUdp(att)
                                is TcpKey -> {
                                    if (key.isConnectable) finishTcpConnect(att, key)
                                    if (key.isValid && key.isWritable) writeTcpPending(att, key)
                                    if (key.isValid && key.isReadable) readTcp(att, key)
                                }
                            }
                        } catch (e: CancelledKeyException) {
                            // ignore
                        } catch (e: Exception) {
                            Log.w(TAG, "select key error: ${e.message}")
                        }
                    }
                }
                cleanupIdle()
            }
        } catch (e: Exception) {
            if (running.get()) Log.w(TAG, "select loop ended: ${e.message}")
        }
    }

    private fun drainRegisters() {
        while (true) {
            val op = registerQueue.poll() ?: break
            try {
                when (op) {
                    is RegisterOp.Udp -> {
                        if (op.channel.isOpen) {
                            op.channel.register(selector, SelectionKey.OP_READ, op.key)
                        }
                    }
                    is RegisterOp.TcpConnect -> {
                        if (!op.channel.isOpen) continue
                        val ops = if (op.session.state == TcpState.CONNECTING) {
                            SelectionKey.OP_CONNECT
                        } else {
                            var o = SelectionKey.OP_READ
                            if (op.session.pendingOut.position() > 0) {
                                o = o or SelectionKey.OP_WRITE
                            }
                            o
                        }
                        val existing = op.channel.keyFor(selector)
                        if (existing != null && existing.isValid) {
                            existing.interestOps(ops)
                            existing.attach(op.key)
                        } else {
                            op.channel.register(selector, ops, op.key)
                        }
                    }
                }
            } catch (e: ClosedChannelException) {
                // ignore
            } catch (e: Exception) {
                Log.w(TAG, "register fail: ${e.message}")
            }
        }
    }

    private fun drainInterest() {
        while (true) {
            val s = interestQueue.poll() ?: break
            try {
                val key = s.channel.keyFor(selector) ?: continue
                if (!key.isValid) continue
                var ops = SelectionKey.OP_READ
                if (s.pendingOut.position() > 0) ops = ops or SelectionKey.OP_WRITE
                if (s.state == TcpState.CONNECTING) ops = SelectionKey.OP_CONNECT
                key.interestOps(ops)
            } catch (_: Exception) {
            }
        }
    }

    private fun drainCloseOps() {
        while (true) {
            when (val op = closeQueue.poll() ?: break) {
                is CloseOp.Udp -> closeUdpNow(op.key)
                is CloseOp.Tcp -> closeTcpNow(op.key, op.sendRst)
                is CloseOp.ResetAll -> closeAllSessionsNow(op.sendRst)
                is CloseOp.ResetGame -> closeGameSessionsNow(op.sendRst)
            }
        }
    }

    private fun closeAllSessionsNow(sendRst: Boolean) {
        val tcps = tcpSessions.values.toList()
        val udps = udpSessions.values.toList()
        tcpSessions.clear()
        udpSessions.clear()
        if (sendRst) {
            tcps.forEach { sendRstToClient(it) }
        }
        tcps.forEach { it.closeQuietly() }
        udps.forEach { it.closeQuietly() }
        // Cancel leftover keys
        try {
            val keys = selector.keys()
            for (key in keys) {
                try {
                    key.cancel()
                    key.channel()?.close()
                } catch (_: Exception) {
                }
            }
            // Purge cancelled keys
            selector.selectNow()
        } catch (_: Exception) {
        }
        registerQueue.clear()
        interestQueue.clear()
        Log.i(TAG, "all sessions closed (sendRst=$sendRst)")
    }

    /**
     * Close only TCP sessions whose remote port is a game-server port.
     * Leaves BattleNet / HTTPS / UDP sessions intact.
     */
    private fun closeGameSessionsNow(sendRst: Boolean) {
        val gameKeys = ArrayList<TcpKey>()
        tcpSessions.forEach { (k, _) ->
            if (isGameTcpPort(k.dstPort)) gameKeys.add(k)
        }
        var closed = 0
        val ports = StringBuilder()
        for (key in gameKeys) {
            val s = tcpSessions.remove(key) ?: continue
            if (sendRst) sendRstToClient(s)
            try {
                s.channel.keyFor(selector)?.cancel()
            } catch (_: Exception) {
            }
            s.closeQuietly()
            closed++
            if (ports.isNotEmpty()) ports.append(',')
            ports.append(Packet.formatIp(key.dst)).append(':').append(key.dstPort)
        }
        try {
            selector.selectNow()
        } catch (_: Exception) {
        }
        Log.i(TAG, "game sessions closed count=$closed sendRst=$sendRst targets=[$ports]")
    }

    private fun closeUdpNow(key: UdpKey) {
        val s = udpSessions.remove(key) ?: return
        try {
            s.channel.keyFor(selector)?.cancel()
        } catch (_: Exception) {
        }
        s.closeQuietly()
    }

    private fun closeTcpNow(key: TcpKey, sendRst: Boolean) {
        val s = tcpSessions.remove(key) ?: return
        if (sendRst) sendRstToClient(s)
        try {
            s.channel.keyFor(selector)?.cancel()
        } catch (_: Exception) {
        }
        s.closeQuietly()
    }

    private fun readUdp(udpKey: UdpKey) {
        val session = udpSessions[udpKey] ?: return
        // Reuse per-session buffer (P0).
        val buf = session.readBuf
        buf.clear()
        try {
            val n = session.channel.read(buf)
            if (n <= 0) return
            buf.flip()
            val payloadLen = buf.remaining()
            session.lastActive = System.currentTimeMillis()
            // Pull only targets game TCP; keep UDP (DNS etc.) flowing for BattleNet.
            buf.get(session.readScratch, 0, payloadLen)
            val out = borrowBuild()
            Packet.buildIp4Udp(
                out,
                src = udpKey.dst,
                dst = CLIENT_IP,
                srcPort = udpKey.dstPort,
                dstPort = udpKey.srcPort,
                payload = session.readScratch,
                payloadLength = payloadLen
            )
            enqueueToTun(out)
        } catch (_: Exception) {
            closeUdpNow(udpKey)
        }
    }

    private fun finishTcpConnect(tcpKey: TcpKey, key: SelectionKey) {
        val session = tcpSessions[tcpKey] ?: return
        try {
            if (session.channel.finishConnect()) {
                key.interestOps(SelectionKey.OP_READ)
                sendSynAck(session)
            }
        } catch (e: Exception) {
            Log.w(TAG, "tcp connect fail: ${e.message}")
            closeTcpNow(tcpKey, sendRst = true)
        }
    }

    private fun writeTcpPending(tcpKey: TcpKey, key: SelectionKey) {
        val session = tcpSessions[tcpKey] ?: return
        try {
            // Drain pendingOut under the session lock — the tun-read payload
            // path writes to the same channel/pendingOut and must not interleave.
            synchronized(session.lock) {
                flushTcpPendingLocked(session)
                if (session.pendingOut.position() == 0) {
                    key.interestOps(SelectionKey.OP_READ)
                }
            }
        } catch (_: Exception) {
            closeTcpNow(tcpKey, sendRst = true)
        }
    }

    private fun readTcp(tcpKey: TcpKey, key: SelectionKey) {
        val session = tcpSessions[tcpKey] ?: return
        session.readBuf.clear()
        val n = try {
            session.channel.read(session.readBuf)
        } catch (_: Exception) {
            closeTcpNow(tcpKey, sendRst = true)
            return
        }
        if (n < 0) {
            // During pull, suppress FIN to client only for game sessions so
            // BattleNet half-close still reaches the game cleanly.
            val suppressFin = isDropping() && isGameTcpPort(session.key.dstPort)
            if (!suppressFin) {
                enqueueClientTcp(session, flags = 0x11)
                session.mySeq = (session.mySeq + 1) and 0xFFFFFFFFL
            }
            closeTcpNow(tcpKey, sendRst = false)
            return
        }
        if (n == 0) return
        // Blackhole only game-server → client during pull.
        if (isDropping() && isGameTcpPort(session.key.dstPort)) return
        session.readBuf.flip()
        val payloadLen = session.readBuf.remaining()
        session.readBuf.get(session.readScratch, 0, payloadLen)
        session.lastActive = System.currentTimeMillis()
        enqueueClientTcp(
            session,
            flags = 0x18,
            payload = session.readScratch,
            payloadLength = payloadLen
        )
        session.mySeq = (session.mySeq + payloadLen) and 0xFFFFFFFFL
    }

    private fun cleanupIdle() {
        // P4: rate-limit to [CLEANUP_INTERVAL_MS]. select() wakes on every packet
        // and wakeup, so running a full O(n) sweep each iteration was wasteful.
        // This method runs only on the single select thread.
        val now = System.currentTimeMillis()
        if (now - lastCleanupMs < CLEANUP_INTERVAL_MS) return
        lastCleanupMs = now
        // Only reap clearly dead channels. Do NOT close long-lived "quiet"
        // game connections — Hearthstone keepalives can be sparse, and killing
        // them causes spontaneous in-game reconnects without user pull-wire.
        val staleUdp = ArrayList<UdpKey>()
        val staleTcp = ArrayList<TcpKey>()
        udpSessions.forEach { (k, s) ->
            if (!s.channel.isOpen) staleUdp.add(k)
        }
        tcpSessions.forEach { (k, s) ->
            if (!s.channel.isOpen || s.state == TcpState.CLOSED) staleTcp.add(k)
        }
        staleUdp.forEach { closeUdpNow(it) }
        staleTcp.forEach { closeTcpNow(it, sendRst = false) }
    }
}
