package com.lushibaxian.pullwire.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Verifies the packet codec after the Int-address refactor:
 * - packed-address round trip,
 * - valid IP header checksum (sum of all 16-bit words incl. checksum == 0xFFFF),
 * - valid TCP checksum (same property over pseudo-header + TCP segment),
 * - parse(build(...)) round trips for UDP and TCP (incl. payload).
 *
 * These are the invariants the NAT hot path depends on; if a checksum is wrong
 * the kernel/remote will silently drop every packet.
 */
class PacketTest {

    private fun ipChecksumValid(buf: ByteBuffer, headerLen: Int): Boolean {
        var sum = 0
        for (i in 0 until headerLen step 2) {
            sum += buf.getShort(i).toInt() and 0xFFFF
        }
        // Fold carries.
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum == 0xFFFF
    }

    private fun tcpChecksumValid(
        buf: ByteBuffer,
        src: Int,
        dst: Int,
        ipHeaderLen: Int,
        tcpLen: Int
    ): Boolean {
        var sum = 0
        sum += (src ushr 16) and 0xFFFF
        sum += src and 0xFFFF
        sum += (dst ushr 16) and 0xFFFF
        sum += dst and 0xFFFF
        sum += Packet.PROTO_TCP
        sum += tcpLen
        val start = ipHeaderLen
        val end = start + tcpLen
        var i = start
        while (i + 1 < end) {
            sum += buf.getShort(i).toInt() and 0xFFFF
            i += 2
        }
        if (i < end) sum += (buf.get(i).toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum == 0xFFFF
    }

    @Test fun packedAddressRoundTrips() {
        val addrs = listOf("10.0.0.2", "192.168.1.100", "8.8.8.8", "223.5.5.5", "255.255.255.255", "0.0.0.0")
        for (s in addrs) {
            val inet = InetAddress.getByName(s)
            val packed = Packet.packInetAddress(inet)
            assertEquals(inet, Packet.inetAddress(packed))
        }
    }

    @Test fun broadcastAndMulticastDetected() {
        fun packed(s: String) = Packet.packInetAddress(InetAddress.getByName(s))
        assertTrue(Packet.isBroadcastOrMulticast(packed("255.255.255.255")))
        assertTrue(Packet.isBroadcastOrMulticast(packed("224.0.0.1")))
        assertTrue(Packet.isBroadcastOrMulticast(packed("239.255.255.250")))
        assertFalse(Packet.isBroadcastOrMulticast(packed("8.8.8.8")))
        assertFalse(Packet.isBroadcastOrMulticast(packed("10.0.0.2")))
        assertFalse(Packet.isBroadcastOrMulticast(packed("192.168.1.1")))
        // 223.x is class C unicast, not multicast (224–239).
        assertFalse(Packet.isBroadcastOrMulticast(packed("223.5.5.5")))
    }

    @Test fun gameTcpPortsRecognized() {
        assertTrue(VpnEngine.isGameTcpPort(3724))
        // 1119 is classic Battle.net — must NOT be treated as game.
        assertFalse(VpnEngine.isGameTcpPort(1119))
        assertFalse(VpnEngine.isGameTcpPort(443))
        assertFalse(VpnEngine.isGameTcpPort(80))
        assertFalse(VpnEngine.isGameTcpPort(53))
    }

    @Test fun buildUdpHasValidChecksumsAndParsesBack() {
        val src = Packet.packInetAddress(InetAddress.getByName("93.184.216.34"))
        val dst = Packet.packInetAddress(InetAddress.getByName("10.0.0.2"))
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val buf = ByteBuffer.allocate(Packet.IP4_HEADER_SIZE + Packet.UDP_HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)

        Packet.buildIp4Udp(buf, src, dst, srcPort = 5000, dstPort = 53, payload = payload)

        assertTrue("IP checksum", ipChecksumValid(buf, Packet.IP4_HEADER_SIZE))

        val ip = Packet.parseIp4(buf)
        org.junit.Assert.assertNotNull(ip)
        assertEquals(Packet.PROTO_UDP, ip!!.protocol)
        assertEquals(src, ip.source)
        assertEquals(dst, ip.destination)

        val udp = Packet.parseUdp(buf, ip)
        assertNotNull(udp)
        assertEquals(53, udp!!.destPort)
        assertEquals(5000, udp.sourcePort)
        assertEquals(payload.size, udp.payloadLength)

        val out = ByteArray(payload.size)
        Packet.copyPayload(buf, udp.payloadOffset, udp.payloadLength, out)
        assertEquals(payload.toList(), out.toList())
    }

    @Test fun buildTcpHasValidChecksumsAndParsesBack() {
        val src = Packet.packInetAddress(InetAddress.getByName("93.184.216.34"))
        val dst = Packet.packInetAddress(InetAddress.getByName("10.0.0.2"))
        val payload = "hello-hs".toByteArray()
        val buf = ByteBuffer.allocate(Packet.IP4_HEADER_SIZE + Packet.TCP_HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)

        Packet.buildIp4Tcp(
            buf, src, dst,
            srcPort = 443, dstPort = 49999,
            seq = 0x1000L, ack = 0x2000L,
            flags = 0x18, window = 65535,
            payload = payload
        )

        assertTrue("IP checksum", ipChecksumValid(buf, Packet.IP4_HEADER_SIZE))
        assertTrue(
            "TCP checksum",
            tcpChecksumValid(buf, src, dst, Packet.IP4_HEADER_SIZE, Packet.TCP_HEADER_SIZE + payload.size)
        )

        val ip = Packet.parseIp4(buf)!!
        assertEquals(Packet.PROTO_TCP, ip.protocol)
        val tcp = Packet.parseTcp(buf, ip)!!
        assertEquals(443, tcp.sourcePort)
        assertEquals(49999, tcp.destPort)
        assertEquals(0x1000L, tcp.seq)
        assertEquals(0x2000L, tcp.ack)
        assertEquals(0x18, tcp.flags)
        assertEquals(payload.size, tcp.payloadLength)

        val out = ByteArray(payload.size)
        Packet.copyPayload(buf, tcp.payloadOffset, tcp.payloadLength, out)
        assertEquals(payload.toList(), out.toList())
    }

    @Test fun parseRejectsBadPackets() {
        // Too short for an IP header.
        assertNull(Packet.parseIp4(ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)))
        // Wrong version (IPv6 first nibble = 6).
        val bad = ByteBuffer.allocate(40).order(ByteOrder.BIG_ENDIAN)
        bad.put(0, 0x60.toByte())
        assertNull(Packet.parseIp4(bad))
    }

    @Test fun buildOverwritesBufferContentsEachCall() {
        // A pooled buffer reused across packets must be fully overwritten each
        // build (no stale tail bytes leaking). buildIp4* calls buf.clear() first.
        val src = Packet.packInetAddress(InetAddress.getByName("1.2.3.4"))
        val dst = Packet.packInetAddress(InetAddress.getByName("5.6.7.8"))
        val buf = ByteBuffer.allocate(Packet.IP4_HEADER_SIZE + Packet.TCP_HEADER_SIZE + 100)
            .order(ByteOrder.BIG_ENDIAN)
        // Fill with garbage first.
        java.util.Arrays.fill(buf.array(), 0xCC.toByte())

        Packet.buildIp4Tcp(buf, src, dst, 1, 2, 0L, 0L, 0x10, 0)

        val ip = Packet.parseIp4(buf)!!
        // totalLength must reflect exactly a header-only TCP packet, regardless
        // of the 0xCC garbage sitting past the end of the buffer's limit.
        assertEquals(Packet.IP4_HEADER_SIZE + Packet.TCP_HEADER_SIZE, ip.totalLength)
        // IP payload length = TCP header (20), TCP data payload = 0.
        assertEquals(Packet.TCP_HEADER_SIZE, ip.payloadLength)
        val tcp = Packet.parseTcp(buf, ip)!!
        assertEquals(0, tcp.payloadLength)
        assertNotEquals("checksum must be non-zero (computed)", 0, buf.getShort(10).toInt() and 0xFFFF)
    }
}
