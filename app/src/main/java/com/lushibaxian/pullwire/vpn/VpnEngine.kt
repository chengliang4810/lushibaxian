package com.lushibaxian.pullwire.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.CancelledKeyException
import java.nio.channels.ClosedChannelException
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
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
 *   (or under the same lock before wakeup) to avoid selector corruption.
 */
class VpnEngine(
    private val vpnService: VpnService,
    private val vpnInterface: ParcelFileDescriptor,
    private val onEngineDead: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "VpnEngine"
        private const val TUN_MTU = 1500
        private val CLIENT_IP: InetAddress = InetAddress.getByName("10.0.0.2")
    }

    val dropping = AtomicBoolean(false)

    private val running = AtomicBoolean(false)
    private val deadNotified = AtomicBoolean(false)

    @Volatile
    private var selector: Selector = Selector.open()

    private val tunOutQueue = LinkedBlockingQueue<ByteBuffer>(2048)
    private val registerQueue = ConcurrentLinkedQueue<RegisterOp>()
    private val closeQueue = ConcurrentLinkedQueue<CloseOp>()

    private val udpSessions = HashMap<UdpKey, UdpSession>()
    private val tcpSessions = HashMap<TcpKey, TcpSession>()
    private val sessionLock = Any()

    private var tunThread: Thread? = null
    private var selectThread: Thread? = null
    private var writerThread: Thread? = null

    private val lastTunActivityMs = AtomicLong(System.currentTimeMillis())
    private val lastSelectOkMs = AtomicLong(System.currentTimeMillis())

    private sealed class RegisterOp {
        data class Udp(val key: UdpKey, val channel: DatagramChannel) : RegisterOp()
        data class TcpConnect(val key: TcpKey, val channel: SocketChannel, val session: TcpSession) : RegisterOp()
    }

    private sealed class CloseOp {
        data class Udp(val key: UdpKey) : CloseOp()
        data class Tcp(val key: TcpKey, val sendRst: Boolean) : CloseOp()
        data class ResetAll(val sendRst: Boolean) : CloseOp()
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        deadNotified.set(false)
        Log.i(TAG, "engine start")
        lastTunActivityMs.set(System.currentTimeMillis())
        lastSelectOkMs.set(System.currentTimeMillis())
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
        // Unblock writer
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
     * Call at pull start (and optionally end) so Hearthstone reconnects cleanly.
     */
    fun resetSessions(sendRst: Boolean = true) {
        closeQueue.offer(CloseOp.ResetAll(sendRst))
        // Also drop any pending TUN writes from old sessions.
        tunOutQueue.clear()
        try {
            selector.wakeup()
        } catch (_: Exception) {
        }
        Log.i(TAG, "sessions reset queued (sendRst=$sendRst)")
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
                if (dropping.get()) {
                    continue
                }
                val buf = ByteBuffer.wrap(packet.copyOf(length)).order(ByteOrder.BIG_ENDIAN)
                handleTunPacket(buf)
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
                if (!buf.hasRemaining()) continue
                try {
                    val arr = ByteArray(buf.remaining())
                    buf.get(arr)
                    output.write(arr)
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "tun write: ${e.message}")
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (running.get()) Log.w(TAG, "writer ended: ${e.message}")
        }
    }

    private fun enqueueToTun(packet: ByteBuffer) {
        if (!running.get() || dropping.get()) return
        if (!tunOutQueue.offer(packet)) {
            // Prefer dropping outbound-to-client under pressure over blocking.
            Log.w(TAG, "tun out queue full, drop")
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

    // ---------------- UDP ----------------

    private data class UdpKey(
        val srcPort: Int,
        val dst: InetAddress,
        val dstPort: Int
    )

    private class UdpSession(
        val key: UdpKey,
        val channel: DatagramChannel
    ) {
        @Volatile
        var lastActive = System.currentTimeMillis()

        fun closeQuietly() {
            try {
                channel.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleUdpFromTun(buf: ByteBuffer, ip: Packet.Ip4) {
        val udp = Packet.parseUdp(buf, ip) ?: return
        val key = UdpKey(udp.sourcePort, ip.destination, udp.destPort)

        var session: UdpSession?
        synchronized(sessionLock) {
            session = udpSessions[key]
        }

        if (session == null) {
            try {
                val ch = DatagramChannel.open()
                ch.configureBlocking(false)
                if (!vpnService.protect(ch.socket())) {
                    Log.w(TAG, "protect() failed for udp")
                    ch.close()
                    return
                }
                ch.connect(InetSocketAddress(ip.destination, udp.destPort))
                val created = UdpSession(key, ch)
                synchronized(sessionLock) {
                    // Another packet may have created it.
                    val existing = udpSessions[key]
                    if (existing != null) {
                        ch.close()
                        session = existing
                    } else {
                        udpSessions[key] = created
                        session = created
                        registerQueue.offer(RegisterOp.Udp(key, ch))
                        selector.wakeup()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "udp open fail: ${e.message}")
                return
            }
        }

        val s = session ?: return
        s.lastActive = System.currentTimeMillis()
        try {
            val payload = Packet.copyPayload(buf, udp.payloadOffset, udp.payloadLength)
            val n = s.channel.write(ByteBuffer.wrap(payload))
            if (n < 0) closeQueue.offer(CloseOp.Udp(key)).also { selector.wakeup() }
        } catch (e: Exception) {
            Log.w(TAG, "udp write fail: ${e.message}")
            closeQueue.offer(CloseOp.Udp(key))
            selector.wakeup()
        }
    }

    // ---------------- TCP ----------------

    private data class TcpKey(
        val srcPort: Int,
        val dst: InetAddress,
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
        @Volatile
        var lastActive = System.currentTimeMillis()

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

        var session: TcpSession?
        synchronized(sessionLock) {
            session = tcpSessions[key]
        }

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
                val connected = ch.connect(InetSocketAddress(ip.destination, tcp.destPort))
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
                synchronized(sessionLock) {
                    val existing = tcpSessions[key]
                    if (existing != null) {
                        ch.close()
                        return
                    }
                    tcpSessions[key] = created
                }
                registerQueue.offer(RegisterOp.TcpConnect(key, ch, created))
                selector.wakeup()
                if (connected) {
                    sendSynAck(created)
                }
            } catch (e: Exception) {
                Log.w(TAG, "tcp open fail ${ip.destination}:${tcp.destPort}: ${e.message}")
                sendRst(ip, tcp)
            }
            return
        }

        val s = session ?: return
        s.lastActive = System.currentTimeMillis()

        if (tcp.isFIN) {
            s.clientNextSeq = (tcp.seq + 1 + tcp.payloadLength) and 0xFFFFFFFFL
            s.myAck = s.clientNextSeq
            enqueueToTun(
                Packet.buildIp4Tcp(
                    src = s.key.dst,
                    dst = CLIENT_IP,
                    srcPort = s.key.dstPort,
                    dstPort = s.key.srcPort,
                    seq = s.mySeq,
                    ack = s.myAck,
                    flags = 0x10,
                    window = 65535
                )
            )
            try {
                s.channel.shutdownOutput()
            } catch (_: Exception) {
            }
            enqueueToTun(
                Packet.buildIp4Tcp(
                    src = s.key.dst,
                    dst = CLIENT_IP,
                    srcPort = s.key.dstPort,
                    dstPort = s.key.srcPort,
                    seq = s.mySeq,
                    ack = s.myAck,
                    flags = 0x11,
                    window = 65535
                )
            )
            s.mySeq = (s.mySeq + 1) and 0xFFFFFFFFL
            s.state = TcpState.LAST_ACK
            return
        }

        if (tcp.payloadLength > 0) {
            // Strict-ish: accept current expected seq, or retransmit (seq < expected).
            val rel = seqCompare(tcp.seq, s.clientNextSeq)
            if (rel > 0) {
                // Future data — ACK dup to trigger retransmit from client.
                enqueueToTun(
                    Packet.buildIp4Tcp(
                        src = s.key.dst,
                        dst = CLIENT_IP,
                        srcPort = s.key.dstPort,
                        dstPort = s.key.srcPort,
                        seq = s.mySeq,
                        ack = s.myAck,
                        flags = 0x10,
                        window = 65535
                    )
                )
                return
            }
            if (rel < 0) {
                // Old retransmit — ACK current
                enqueueToTun(
                    Packet.buildIp4Tcp(
                        src = s.key.dst,
                        dst = CLIENT_IP,
                        srcPort = s.key.dstPort,
                        dstPort = s.key.srcPort,
                        seq = s.mySeq,
                        ack = s.myAck,
                        flags = 0x10,
                        window = 65535
                    )
                )
                return
            }

            val payload = Packet.copyPayload(buf, tcp.payloadOffset, tcp.payloadLength)
            try {
                // Flush any pending first
                flushTcpPending(s)
                val written = s.channel.write(ByteBuffer.wrap(payload))
                if (written < payload.size) {
                    // Buffer remainder for OP_WRITE
                    val left = payload.size - written.coerceAtLeast(0)
                    if (left > 0 && written >= 0) {
                        if (s.pendingOut.remaining() >= left) {
                            s.pendingOut.put(payload, written.coerceAtLeast(0), left)
                        }
                        updateInterestLater(s)
                    }
                }
                val advanced = written.coerceAtLeast(0)
                if (advanced > 0) {
                    s.clientNextSeq = (s.clientNextSeq + advanced) and 0xFFFFFFFFL
                    s.myAck = s.clientNextSeq
                }
                // Always ACK what we accepted into our pipeline (including buffered).
                // For simplicity ACK only fully written bytes to remote.
                enqueueToTun(
                    Packet.buildIp4Tcp(
                        src = s.key.dst,
                        dst = CLIENT_IP,
                        srcPort = s.key.dstPort,
                        dstPort = s.key.srcPort,
                        seq = s.mySeq,
                        ack = s.myAck,
                        flags = 0x10,
                        window = 65535
                    )
                )
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

    private fun flushTcpPending(session: TcpSession) {
        if (session.pendingOut.position() == 0) return
        session.pendingOut.flip()
        try {
            session.channel.write(session.pendingOut)
        } catch (_: Exception) {
        }
        session.pendingOut.compact()
    }

    private fun sendSynAck(session: TcpSession) {
        enqueueToTun(
            Packet.buildIp4Tcp(
                src = session.key.dst,
                dst = CLIENT_IP,
                srcPort = session.key.dstPort,
                dstPort = session.key.srcPort,
                seq = session.mySeq,
                ack = session.myAck,
                flags = 0x12,
                window = 65535
            )
        )
        session.mySeq = (session.mySeq + 1) and 0xFFFFFFFFL
        session.state = TcpState.ESTABLISHED
    }

    private fun sendRst(ip: Packet.Ip4, tcp: Packet.Tcp) {
        enqueueToTun(
            Packet.buildIp4Tcp(
                src = ip.destination,
                dst = ip.source,
                srcPort = tcp.destPort,
                dstPort = tcp.sourcePort,
                seq = 0,
                ack = (tcp.seq + 1) and 0xFFFFFFFFL,
                flags = 0x14,
                window = 0
            )
        )
    }

    private fun sendRstToClient(session: TcpSession) {
        if (dropping.get()) return
        enqueueToTun(
            Packet.buildIp4Tcp(
                src = session.key.dst,
                dst = CLIENT_IP,
                srcPort = session.key.dstPort,
                dstPort = session.key.srcPort,
                seq = session.mySeq,
                ack = session.myAck,
                flags = 0x14,
                window = 0
            )
        )
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
            }
        }
    }

    private fun closeAllSessionsNow(sendRst: Boolean) {
        val tcps: List<TcpSession>
        val udps: List<UdpSession>
        synchronized(sessionLock) {
            tcps = tcpSessions.values.toList()
            udps = udpSessions.values.toList()
            tcpSessions.clear()
            udpSessions.clear()
        }
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

    private fun closeUdpNow(key: UdpKey) {
        val s = synchronized(sessionLock) { udpSessions.remove(key) } ?: return
        try {
            s.channel.keyFor(selector)?.cancel()
        } catch (_: Exception) {
        }
        s.closeQuietly()
    }

    private fun closeTcpNow(key: TcpKey, sendRst: Boolean) {
        val s = synchronized(sessionLock) { tcpSessions.remove(key) } ?: return
        if (sendRst) sendRstToClient(s)
        try {
            s.channel.keyFor(selector)?.cancel()
        } catch (_: Exception) {
        }
        s.closeQuietly()
    }

    private fun readUdp(udpKey: UdpKey) {
        val session = synchronized(sessionLock) { udpSessions[udpKey] } ?: return
        val buf = ByteBuffer.allocate(TUN_MTU)
        try {
            val n = session.channel.read(buf)
            if (n <= 0) return
            buf.flip()
            val payload = ByteArray(buf.remaining())
            buf.get(payload)
            session.lastActive = System.currentTimeMillis()
            if (dropping.get()) return
            enqueueToTun(
                Packet.buildIp4Udp(
                    src = udpKey.dst,
                    dst = CLIENT_IP,
                    srcPort = udpKey.dstPort,
                    dstPort = udpKey.srcPort,
                    payload = payload
                )
            )
        } catch (_: Exception) {
            closeUdpNow(udpKey)
        }
    }

    private fun finishTcpConnect(tcpKey: TcpKey, key: SelectionKey) {
        val session = synchronized(sessionLock) { tcpSessions[tcpKey] } ?: return
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
        val session = synchronized(sessionLock) { tcpSessions[tcpKey] } ?: return
        try {
            flushTcpPending(session)
            if (session.pendingOut.position() == 0) {
                key.interestOps(SelectionKey.OP_READ)
            }
        } catch (_: Exception) {
            closeTcpNow(tcpKey, sendRst = true)
        }
    }

    private fun readTcp(tcpKey: TcpKey, key: SelectionKey) {
        val session = synchronized(sessionLock) { tcpSessions[tcpKey] } ?: return
        session.readBuf.clear()
        val n = try {
            session.channel.read(session.readBuf)
        } catch (_: Exception) {
            closeTcpNow(tcpKey, sendRst = true)
            return
        }
        if (n < 0) {
            if (!dropping.get()) {
                enqueueToTun(
                    Packet.buildIp4Tcp(
                        src = session.key.dst,
                        dst = CLIENT_IP,
                        srcPort = session.key.dstPort,
                        dstPort = session.key.srcPort,
                        seq = session.mySeq,
                        ack = session.myAck,
                        flags = 0x11,
                        window = 65535
                    )
                )
                session.mySeq = (session.mySeq + 1) and 0xFFFFFFFFL
            }
            closeTcpNow(tcpKey, sendRst = false)
            return
        }
        if (n == 0) return
        if (dropping.get()) return
        session.readBuf.flip()
        val payload = ByteArray(session.readBuf.remaining())
        session.readBuf.get(payload)
        session.lastActive = System.currentTimeMillis()
        enqueueToTun(
            Packet.buildIp4Tcp(
                src = session.key.dst,
                dst = CLIENT_IP,
                srcPort = session.key.dstPort,
                dstPort = session.key.srcPort,
                seq = session.mySeq,
                ack = session.myAck,
                flags = 0x18,
                window = 65535,
                payload = payload
            )
        )
        session.mySeq = (session.mySeq + payload.size) and 0xFFFFFFFFL
    }

    private fun cleanupIdle() {
        // Only reap clearly dead channels. Do NOT close long-lived "quiet"
        // game connections — Hearthstone keepalives can be sparse, and killing
        // them causes spontaneous in-game reconnects without user pull-wire.
        val staleUdp = ArrayList<UdpKey>()
        val staleTcp = ArrayList<TcpKey>()
        synchronized(sessionLock) {
            udpSessions.forEach { (k, s) ->
                if (!s.channel.isOpen) staleUdp.add(k)
            }
            tcpSessions.forEach { (k, s) ->
                if (!s.channel.isOpen || s.state == TcpState.CLOSED) staleTcp.add(k)
            }
        }
        staleUdp.forEach { closeUdpNow(it) }
        staleTcp.forEach { closeTcpNow(it, sendRst = false) }
    }
}
