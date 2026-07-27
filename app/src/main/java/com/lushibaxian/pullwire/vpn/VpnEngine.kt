package com.lushibaxian.pullwire.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Userspace IPv4 NAT for a single-app VPN.
 * - UDP/TCP are forwarded via protect()'d sockets
 * - When [dropping] is true, outbound packets are discarded (pull-wire)
 * - VPN interface stays up; sessions can recover after drop window
 */
class VpnEngine(
    private val vpnService: VpnService,
    private val vpnInterface: ParcelFileDescriptor
) {
    companion object {
        private const val TAG = "VpnEngine"
        private const val TUN_MTU = 1500
        private val CLIENT_IP: InetAddress = InetAddress.getByName("10.0.0.2")
    }

    val dropping = AtomicBoolean(false)

    private val running = AtomicBoolean(false)
    private val selector: Selector = Selector.open()
    private val tunOutQueue = LinkedBlockingQueue<ByteBuffer>(512)
    private val udpSessions = ConcurrentHashMap<UdpKey, UdpSession>()
    private val tcpSessions = ConcurrentHashMap<TcpKey, TcpSession>()
    private var tunThread: Thread? = null
    private var selectThread: Thread? = null
    private var writerThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Log.i(TAG, "engine start")
        writerThread = Thread({ writerLoop() }, "vpn-writer").also { it.isDaemon = true; it.start() }
        selectThread = Thread({ selectLoop() }, "vpn-select").also { it.isDaemon = true; it.start() }
        tunThread = Thread({ tunReadLoop() }, "vpn-tun-read").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        Log.i(TAG, "engine stop")
        try {
            selector.wakeup()
        } catch (_: Exception) {
        }
        resetSessions(sendRst = false)
        try {
            selector.close()
        } catch (_: Exception) {
        }
        tunThread = null
        selectThread = null
        writerThread = null
    }

    /**
     * Kill all NAT sessions. During pull-wire this forces Hearthstone
     * to open fresh connections after the drop window, instead of hanging
     * on half-open sockets.
     */
    fun resetSessions(sendRst: Boolean = true) {
        if (sendRst) {
            tcpSessions.values.forEach { sendRstToClient(it) }
        }
        udpSessions.values.forEach { it.closeQuietly() }
        tcpSessions.values.forEach { it.closeQuietly() }
        udpSessions.clear()
        tcpSessions.clear()
        try {
            selector.wakeup()
        } catch (_: Exception) {
        }
        Log.i(TAG, "sessions reset (sendRst=$sendRst)")
    }

    private fun tunReadLoop() {
        val input = FileInputStream(vpnInterface.fileDescriptor)
        val packet = ByteArray(TUN_MTU)
        try {
            while (running.get()) {
                val length = input.read(packet)
                if (length <= 0) {
                    Thread.sleep(2)
                    continue
                }
                if (dropping.get()) {
                    // Pull-wire: drop outbound only; keep engine alive.
                    continue
                }
                val buf = ByteBuffer.wrap(packet, 0, length).order(java.nio.ByteOrder.BIG_ENDIAN)
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
                try {
                    val arr = ByteArray(buf.remaining())
                    val p = buf.position()
                    buf.get(arr)
                    buf.position(p)
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
        if (!running.get()) return
        if (!tunOutQueue.offer(packet)) {
            // drop if congested
            Log.w(TAG, "tun out queue full, drop")
        }
    }

    private fun handleTunPacket(buf: ByteBuffer) {
        val ip = Packet.parseIp4(buf) ?: return
        when (ip.protocol) {
            Packet.PROTO_UDP -> handleUdpFromTun(buf, ip)
            Packet.PROTO_TCP -> handleTcpFromTun(buf, ip)
            else -> Unit // ignore ICMP etc.
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
        var session = udpSessions[key]
        if (session == null) {
            try {
                val ch = DatagramChannel.open()
                ch.configureBlocking(false)
                vpnService.protect(ch.socket())
                ch.connect(InetSocketAddress(ip.destination, udp.destPort))
                ch.register(selector, SelectionKey.OP_READ, key)
                session = UdpSession(key, ch)
                udpSessions[key] = session
                selector.wakeup()
            } catch (e: Exception) {
                Log.w(TAG, "udp open fail: ${e.message}")
                return
            }
        }
        session.lastActive = System.currentTimeMillis()
        try {
            val payload = Packet.copyPayload(buf, udp.payloadOffset, udp.payloadLength)
            session.channel.write(ByteBuffer.wrap(payload))
        } catch (e: Exception) {
            Log.w(TAG, "udp write fail: ${e.message}")
            closeUdp(key)
        }
    }

    private fun closeUdp(key: UdpKey) {
        udpSessions.remove(key)?.closeQuietly()
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
        CLOSE_WAIT,
        LAST_ACK,
        CLOSED
    }

    private class TcpSession(
        val key: TcpKey,
        val channel: SocketChannel,
        var state: TcpState,
        /** seq we send to client (HS) */
        var mySeq: Long,
        /** next seq we expect from client */
        var clientNextSeq: Long,
        /** ack number client expects / last acked */
        var myAck: Long
    ) {
        val readBuf = ByteBuffer.allocate(TUN_MTU)
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
            closeTcp(key)
            return
        }

        var session = tcpSessions[key]

        // New connection
        if (session == null) {
            if (!tcp.isSYN || tcp.isACK) return
            try {
                val ch = SocketChannel.open()
                ch.configureBlocking(false)
                vpnService.protect(ch.socket())
                val connected = ch.connect(InetSocketAddress(ip.destination, tcp.destPort))
                val mySeq = Random.nextInt().toLong() and 0xFFFFFFFFL
                val clientNext = (tcp.seq + 1) and 0xFFFFFFFFL
                session = TcpSession(
                    key = key,
                    channel = ch,
                    state = if (connected) TcpState.ESTABLISHED else TcpState.CONNECTING,
                    mySeq = mySeq,
                    clientNextSeq = clientNext,
                    myAck = clientNext
                )
                tcpSessions[key] = session
                val ops = if (connected) SelectionKey.OP_READ else SelectionKey.OP_CONNECT
                ch.register(selector, ops, key)
                selector.wakeup()
                if (connected) {
                    sendSynAck(session)
                }
            } catch (e: Exception) {
                Log.w(TAG, "tcp open fail ${ip.destination}:${tcp.destPort}: ${e.message}")
                sendRst(ip, tcp)
            }
            return
        }

        session.lastActive = System.currentTimeMillis()

        if (tcp.isFIN) {
            // Client closing
            session.clientNextSeq = (tcp.seq + 1 + tcp.payloadLength) and 0xFFFFFFFFL
            session.myAck = session.clientNextSeq
            // ACK the FIN
            enqueueToTun(
                Packet.buildIp4Tcp(
                    src = session.key.dst,
                    dst = CLIENT_IP,
                    srcPort = session.key.dstPort,
                    dstPort = session.key.srcPort,
                    seq = session.mySeq,
                    ack = session.myAck,
                    flags = 0x10, // ACK
                    window = 65535
                )
            )
            try {
                session.channel.shutdownOutput()
            } catch (_: Exception) {
            }
            // Also send FIN to client after remote side done - simplified: FIN+ACK now
            enqueueToTun(
                Packet.buildIp4Tcp(
                    src = session.key.dst,
                    dst = CLIENT_IP,
                    srcPort = session.key.dstPort,
                    dstPort = session.key.srcPort,
                    seq = session.mySeq,
                    ack = session.myAck,
                    flags = 0x11, // FIN+ACK
                    window = 65535
                )
            )
            session.mySeq = (session.mySeq + 1) and 0xFFFFFFFFL
            session.state = TcpState.LAST_ACK
            return
        }

        if (tcp.payloadLength > 0) {
            // Only accept in-order-ish payload
            if (tcp.seq == session.clientNextSeq || session.state == TcpState.ESTABLISHED) {
                val payload = Packet.copyPayload(buf, tcp.payloadOffset, tcp.payloadLength)
                try {
                    val written = session.channel.write(ByteBuffer.wrap(payload))
                    if (written > 0) {
                        session.clientNextSeq = (session.clientNextSeq + written) and 0xFFFFFFFFL
                        session.myAck = session.clientNextSeq
                        // ACK
                        enqueueToTun(
                            Packet.buildIp4Tcp(
                                src = session.key.dst,
                                dst = CLIENT_IP,
                                srcPort = session.key.dstPort,
                                dstPort = session.key.srcPort,
                                seq = session.mySeq,
                                ack = session.myAck,
                                flags = 0x10,
                                window = 65535
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "tcp write fail: ${e.message}")
                    sendRstToClient(session)
                    closeTcp(key)
                }
            }
        } else if (tcp.isACK && session.state == TcpState.LAST_ACK) {
            closeTcp(key)
        }
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
                flags = 0x12, // SYN+ACK
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
                flags = 0x14, // RST+ACK
                window = 0
            )
        )
    }

    private fun sendRstToClient(session: TcpSession) {
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

    private fun closeTcp(key: TcpKey) {
        tcpSessions.remove(key)?.closeQuietly()
    }

    // ---------------- selector (remote -> TUN) ----------------

    private fun selectLoop() {
        try {
            while (running.get()) {
                selector.select(200)
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    if (!key.isValid) continue
                    try {
                        when (val att = key.attachment()) {
                            is UdpKey -> {
                                if (key.isReadable) readUdp(att)
                            }
                            is TcpKey -> {
                                if (key.isConnectable) finishTcpConnect(att, key)
                                if (key.isValid && key.isReadable) readTcp(att, key)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "select key error: ${e.message}")
                    }
                }
                // idle cleanup
                cleanupIdle()
            }
        } catch (e: Exception) {
            if (running.get()) Log.w(TAG, "select loop ended: ${e.message}")
        }
    }

    private fun readUdp(udpKey: UdpKey) {
        val session = udpSessions[udpKey] ?: return
        val buf = ByteBuffer.allocate(TUN_MTU)
        try {
            val n = session.channel.read(buf)
            if (n <= 0) return
            buf.flip()
            val payload = ByteArray(buf.remaining())
            buf.get(payload)
            session.lastActive = System.currentTimeMillis()
            // Even during dropping, allow inbound? Safer to also drop inbound so HS sees dead connection.
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
        } catch (e: Exception) {
            closeUdp(udpKey)
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
            sendRstToClient(session)
            closeTcp(tcpKey)
        }
    }

    private fun readTcp(tcpKey: TcpKey, key: SelectionKey) {
        val session = tcpSessions[tcpKey] ?: return
        session.readBuf.clear()
        val n = try {
            session.channel.read(session.readBuf)
        } catch (e: Exception) {
            sendRstToClient(session)
            closeTcp(tcpKey)
            return
        }
        if (n < 0) {
            // remote closed
            if (!dropping.get()) {
                enqueueToTun(
                    Packet.buildIp4Tcp(
                        src = session.key.dst,
                        dst = CLIENT_IP,
                        srcPort = session.key.dstPort,
                        dstPort = session.key.srcPort,
                        seq = session.mySeq,
                        ack = session.myAck,
                        flags = 0x11, // FIN+ACK
                        window = 65535
                    )
                )
                session.mySeq = (session.mySeq + 1) and 0xFFFFFFFFL
            }
            closeTcp(tcpKey)
            return
        }
        if (n == 0) return
        if (dropping.get()) {
            // Drop inbound during pull-wire so game sees silence.
            return
        }
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
                flags = 0x18, // PSH+ACK
                window = 65535,
                payload = payload
            )
        )
        session.mySeq = (session.mySeq + payload.size) and 0xFFFFFFFFL
    }

    private fun cleanupIdle() {
        val now = System.currentTimeMillis()
        udpSessions.entries.removeIf { (_, s) ->
            if (now - s.lastActive > 60_000) {
                s.closeQuietly()
                true
            } else false
        }
        tcpSessions.entries.removeIf { (_, s) ->
            if (now - s.lastActive > 120_000) {
                s.closeQuietly()
                true
            } else false
        }
    }
}
