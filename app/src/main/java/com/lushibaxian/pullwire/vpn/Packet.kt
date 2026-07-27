package com.lushibaxian.pullwire.vpn

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal IPv4 packet helpers for TUN userspace NAT.
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
        val source: InetAddress,
        val destination: InetAddress,
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
        val src = ByteArray(4)
        val dst = ByteArray(4)
        buf.position(pos + 12)
        buf.get(src)
        buf.get(dst)
        buf.position(pos)
        return Ip4(
            version = version,
            headerLength = ihl,
            totalLength = totalLength,
            protocol = protocol,
            source = InetAddress.getByAddress(src),
            destination = InetAddress.getByAddress(dst),
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

    fun buildIp4Udp(
        src: InetAddress,
        dst: InetAddress,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        payloadOffset: Int = 0,
        payloadLength: Int = payload.size
    ): ByteBuffer {
        val udpLen = UDP_HEADER_SIZE + payloadLength
        val total = IP4_HEADER_SIZE + udpLen
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        // IP
        buf.put(0x45.toByte())
        buf.put(0)
        buf.putShort(total.toShort())
        buf.putShort(0) // id
        buf.putShort(0x4000.toShort()) // don't fragment
        buf.put(64.toByte()) // ttl
        buf.put(PROTO_UDP.toByte())
        buf.putShort(0) // checksum placeholder
        buf.put(src.address)
        buf.put(dst.address)
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
        return buf
    }

    fun buildIp4Tcp(
        src: InetAddress,
        dst: InetAddress,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int,
        payload: ByteArray? = null,
        payloadOffset: Int = 0,
        payloadLength: Int = payload?.size ?: 0
    ): ByteBuffer {
        val tcpLen = TCP_HEADER_SIZE + payloadLength
        val total = IP4_HEADER_SIZE + tcpLen
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x45.toByte())
        buf.put(0)
        buf.putShort(total.toShort())
        buf.putShort(0)
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(PROTO_TCP.toByte())
        buf.putShort(0)
        buf.put(src.address)
        buf.put(dst.address)
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
        return buf
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
        src: InetAddress,
        dst: InetAddress,
        tcpLength: Int
    ) {
        buf.putShort(IP4_HEADER_SIZE + 16, 0)
        var sum = 0
        // pseudo header
        val s = src.address
        val d = dst.address
        sum += ((s[0].toInt() and 0xFF) shl 8) or (s[1].toInt() and 0xFF)
        sum += ((s[2].toInt() and 0xFF) shl 8) or (s[3].toInt() and 0xFF)
        sum += ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
        sum += ((d[2].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF)
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

    fun copyPayload(buf: ByteBuffer, offset: Int, length: Int): ByteArray {
        val out = ByteArray(length)
        val p = buf.position()
        buf.position(offset)
        buf.get(out, 0, length)
        buf.position(p)
        return out
    }
}
