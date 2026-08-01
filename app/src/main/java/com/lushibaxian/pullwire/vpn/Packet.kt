package com.lushibaxian.pullwire.vpn

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal IPv4 packet helpers for TUN userspace NAT.
 *
 * Performance note: addresses are carried as packed [Int] (network order in the
 * high/low bits — see [packInetAddress] / [inetAddress]) rather than
 * [InetAddress]. The NAT hot path parses thousands of packets/second; allocating
 * two `InetAddress` objects (with internal validation + byte-array copies) per
 * packet, and hashing them as session-map keys, shows up as steady GC churn and
 * map lookup overhead. [Int] is a primitive: no allocation, cheap equals/hash.
 */
object Packet {
    const val IP4_HEADER_SIZE = 20
    const val TCP_HEADER_SIZE = 20
    const val UDP_HEADER_SIZE = 8

    const val PROTO_TCP = 6
    const val PROTO_UDP = 17
    const val PROTO_ICMP = 1

    data class Ip4(
        val version: Int,
        val headerLength: Int,
        val totalLength: Int,
        val protocol: Int,
        val source: Int,
        val destination: Int,
        val payloadOffset: Int,
        val payloadLength: Int
    )

    data class Udp(
        val sourcePort: Int,
        val destPort: Int,
        val length: Int,
        val payloadOffset: Int,
        val payloadLength: Int
    )

    data class Tcp(
        val sourcePort: Int,
        val destPort: Int,
        val seq: Long,
        val ack: Long,
        val dataOffset: Int,
        val flags: Int,
        val window: Int,
        val payloadOffset: Int,
        val payloadLength: Int
    ) {
        val isFIN get() = flags and 0x01 != 0
        val isSYN get() = flags and 0x02 != 0
        val isRST get() = flags and 0x04 != 0
        val isPSH get() = flags and 0x08 != 0
        val isACK get() = flags and 0x10 != 0
    }

    fun parseIp4(buf: ByteBuffer): Ip4? {
        if (buf.remaining() < IP4_HEADER_SIZE) return null
        val pos = buf.position()
        val versionIhl = buf.get(pos).toInt() and 0xFF
        val version = versionIhl ushr 4
        if (version != 4) return null
        val ihl = (versionIhl and 0x0F) * 4
        if (ihl < IP4_HEADER_SIZE || buf.remaining() < ihl) return null
        val totalLength = buf.getShort(pos + 2).toInt() and 0xFFFF
        if (totalLength < ihl || totalLength > buf.remaining()) return null
        val protocol = buf.get(pos + 9).toInt() and 0xFF
        val src = buf.getInt(pos + 12)
        val dst = buf.getInt(pos + 16)
        return Ip4(
            version = version,
            headerLength = ihl,
            totalLength = totalLength,
            protocol = protocol,
            source = src,
            destination = dst,
            payloadOffset = pos + ihl,
            payloadLength = totalLength - ihl
        )
    }

    fun parseUdp(buf: ByteBuffer, ip: Ip4): Udp? {
        if (ip.payloadLength < UDP_HEADER_SIZE) return null
        val off = ip.payloadOffset
        val srcPort = buf.getShort(off).toInt() and 0xFFFF
        val dstPort = buf.getShort(off + 2).toInt() and 0xFFFF
        val len = buf.getShort(off + 4).toInt() and 0xFFFF
        val payloadLen = (len - UDP_HEADER_SIZE).coerceAtLeast(0)
        return Udp(
            sourcePort = srcPort,
            destPort = dstPort,
            length = len,
            payloadOffset = off + UDP_HEADER_SIZE,
            payloadLength = payloadLen.coerceAtMost(ip.payloadLength - UDP_HEADER_SIZE)
        )
    }

    fun parseTcp(buf: ByteBuffer, ip: Ip4): Tcp? {
        if (ip.payloadLength < TCP_HEADER_SIZE) return null
        val off = ip.payloadOffset
        val srcPort = buf.getShort(off).toInt() and 0xFFFF
        val dstPort = buf.getShort(off + 2).toInt() and 0xFFFF
        val seq = buf.getInt(off + 4).toLong() and 0xFFFFFFFFL
        val ack = buf.getInt(off + 8).toLong() and 0xFFFFFFFFL
        val dataOffset = ((buf.get(off + 12).toInt() and 0xF0) ushr 4) * 4
        if (dataOffset < TCP_HEADER_SIZE || dataOffset > ip.payloadLength) return null
        val flags = buf.get(off + 13).toInt() and 0xFF
        val window = buf.getShort(off + 14).toInt() and 0xFFFF
        val payloadLen = ip.payloadLength - dataOffset
        return Tcp(
            sourcePort = srcPort,
            destPort = dstPort,
            seq = seq,
            ack = ack,
            dataOffset = dataOffset,
            flags = flags,
            window = window,
            payloadOffset = off + dataOffset,
            payloadLength = payloadLen
        )
    }

    /**
     * Build an IPv4/UDP packet. [src]/[dst] are packed addresses ([packInetAddress]).
     * Writes into [buf] at [buf.position()] and flips, leaving [buf] ready to read.
     */
    fun buildIp4Udp(
        buf: ByteBuffer,
        src: Int,
        dst: Int,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        payloadOffset: Int = 0,
        payloadLength: Int = payload.size
    ) {
        val udpLen = UDP_HEADER_SIZE + payloadLength
        val total = IP4_HEADER_SIZE + udpLen
        buf.clear()
        // IP
        buf.put(0x45.toByte())
        buf.put(0)
        buf.putShort(total.toShort())
        buf.putShort(0) // id
        buf.putShort(0x4000.toShort()) // don't fragment
        buf.put(64.toByte()) // ttl
        buf.put(PROTO_UDP.toByte())
        buf.putShort(0) // checksum placeholder
        buf.putInt(src)
        buf.putInt(dst)
        // UDP
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0) // udp checksum optional for IPv4
        if (payloadLength > 0) {
            buf.put(payload, payloadOffset, payloadLength)
        }
        buf.flip()
        writeIpChecksum(buf)
    }

    fun buildIp4Tcp(
        buf: ByteBuffer,
        src: Int,
        dst: Int,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int,
        payload: ByteArray? = null,
        payloadOffset: Int = 0,
        payloadLength: Int = payload?.size ?: 0
    ) {
        val tcpLen = TCP_HEADER_SIZE + payloadLength
        val total = IP4_HEADER_SIZE + tcpLen
        buf.clear()
        buf.put(0x45.toByte())
        buf.put(0)
        buf.putShort(total.toShort())
        buf.putShort(0)
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(PROTO_TCP.toByte())
        buf.putShort(0)
        buf.putInt(src)
        buf.putInt(dst)
        // TCP
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt(seq.toInt())
        buf.putInt(ack.toInt())
        buf.put(((TCP_HEADER_SIZE / 4) shl 4).toByte()) // data offset
        buf.put(flags.toByte())
        buf.putShort(window.toShort())
        buf.putShort(0) // checksum
        buf.putShort(0) // urgent
        if (payload != null && payloadLength > 0) {
            buf.put(payload, payloadOffset, payloadLength)
        }
        buf.flip()
        writeIpChecksum(buf)
        writeTcpChecksum(buf, src, dst, tcpLen)
    }

    private fun writeIpChecksum(buf: ByteBuffer) {
        buf.putShort(10, 0)
        var sum = 0
        for (i in 0 until IP4_HEADER_SIZE step 2) {
            sum += buf.getShort(i).toInt() and 0xFFFF
        }
        while (sum ushr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        buf.putShort(10, (sum.inv() and 0xFFFF).toShort())
    }

    private fun writeTcpChecksum(
        buf: ByteBuffer,
        src: Int,
        dst: Int,
        tcpLength: Int
    ) {
        buf.putShort(IP4_HEADER_SIZE + 16, 0)
        var sum = 0
        // pseudo header (packed addresses → two 16-bit words each)
        sum += (src ushr 16) and 0xFFFF
        sum += src and 0xFFFF
        sum += (dst ushr 16) and 0xFFFF
        sum += dst and 0xFFFF
        sum += PROTO_TCP
        sum += tcpLength
        val start = IP4_HEADER_SIZE
        val end = start + tcpLength
        var i = start
        while (i + 1 < end) {
            sum += buf.getShort(i).toInt() and 0xFFFF
            i += 2
        }
        if (i < end) {
            sum += (buf.get(i).toInt() and 0xFF) shl 8
        }
        while (sum ushr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        buf.putShort(IP4_HEADER_SIZE + 16, (sum.inv() and 0xFFFF).toShort())
    }

    /**
     * Copy [length] bytes from [buf] starting at [offset] into [out] at [outPos].
     * Restores the buffer's position afterwards. No allocation.
     */
    fun copyPayload(buf: ByteBuffer, offset: Int, length: Int, out: ByteArray, outPos: Int = 0) {
        val p = buf.position()
        buf.position(offset)
        buf.get(out, outPos, length)
        buf.position(p)
    }

    /** Pack an IPv4 [InetAddress] (4 bytes) into a 32-bit int. */
    fun packInetAddress(addr: InetAddress): Int {
        val b = addr.address
        return ((b[0].toInt() and 0xFF) shl 24) or
            ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or
            (b[3].toInt() and 0xFF)
    }

    /** Unpack a 32-bit int into an IPv4 [InetAddress]. */
    fun inetAddress(packed: Int): InetAddress {
        val b = ByteArray(4)
        b[0] = (packed ushr 24).toByte()
        b[1] = (packed ushr 16).toByte()
        b[2] = (packed ushr 8).toByte()
        b[3] = packed.toByte()
        return InetAddress.getByAddress(b)
    }

    /** Format a packed IPv4 int as a dotted string (e.g. `223.5.5.5`), no alloc. */
    fun formatIp(packed: Int): String {
        return "${(packed ushr 24) and 0xFF}." +
            "${(packed ushr 16) and 0xFF}." +
            "${(packed ushr 8) and 0xFF}." +
            "${packed and 0xFF}"
    }

    /** True if a packed IPv4 address is broadcast or multicast — connect() to
     * these always throws EACCES and should be silently skipped. */
    fun isBroadcastOrMulticast(packed: Int): Boolean {
        val first = (packed ushr 24) and 0xFF
        // 255.255.255.255 (limited broadcast) or 224.0.0.0/4 (multicast)
        return first == 255 || (first in 224..239)
    }
}
