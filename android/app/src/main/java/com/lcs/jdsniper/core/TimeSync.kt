package com.lcs.jdsniper.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 纯 Socket NTP 校时（不引第三方库）。offset = 服务端时间 - 本机时间。
 * 取多源中位数，单源异常不会污染结果。
 */
object TimeSync {

    data class Result(
        val offsetMs: Long,
        val source: String,
        val samples: List<Long>,
        val at: Long
    )

    private val HOSTS = listOf(
        "ntp.aliyun.com",
        "cn.pool.ntp.org",
        "time.windows.com"
    )

    fun sync(): Result? {
        val samples = ArrayList<Long>()
        var last = "ntp"
        for (h in HOSTS) {
            val o = query(h)
            if (o != null) {
                samples.add(o)
                last = h
            }
        }
        if (samples.isEmpty()) return null
        samples.sort()
        val median = samples[samples.size / 2]
        return Result(median, last, samples, System.currentTimeMillis())
    }

    private fun query(host: String): Long? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = 1500
            val buf = ByteArray(48)
            buf[0] = 0x1B // LI=0, VN=3, Mode=3 (client)
            val addr = InetAddress.getByName(host)
            val t1 = System.currentTimeMillis()
            socket.send(DatagramPacket(buf, buf.size, addr, 123))
            val recv = DatagramPacket(ByteArray(48), 48)
            socket.receive(recv)
            val t4 = System.currentTimeMillis()
            val data = recv.data
            val t2 = readTimestamp(data, 32)
            val t3 = readTimestamp(data, 40)
            if (t2 == 0L || t3 == 0L) return null
            return ((t2 - t1) + (t3 - t4)) / 2L
        } catch (e: Exception) {
            return null
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun readTimestamp(b: ByteArray, off: Int): Long {
        if (off + 8 > b.size) return 0L
        val sec = ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)
        val frac = ((b[off + 4].toLong() and 0xFF) shl 24) or
            ((b[off + 5].toLong() and 0xFF) shl 16) or
            ((b[off + 6].toLong() and 0xFF) shl 8) or
            (b[off + 7].toLong() and 0xFF)
        if (sec == 0L) return 0L
        val ms = (frac * 1000L) ushr 32
        return (sec - 2208988800L) * 1000L + ms
    }
}
