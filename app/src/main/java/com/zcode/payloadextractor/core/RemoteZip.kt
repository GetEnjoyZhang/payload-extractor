package com.zcode.payloadextractor.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * 通过 HTTP Range 请求在线读取远程 ZIP：
 * - open(): 下载文件尾部解析 EOCD/ZIP64 中央目录，列出全部条目
 * - readRange()/readEntryBytes(): 按字节区间读取
 * - streamEntry(): 流式读取条目内容（deflate 自动解压），支持进度回调
 */
class RemoteZip(val url: String) {

    class Entry(val name: String, val method: Int, val csize: Long, val usize: Long, val lho: Long) {
        val randomAccess: Boolean get() = method == 0
        override fun toString() = name
    }

    var totalSize = -1L
        private set

    private val entries = ArrayList<Entry>()

    fun listEntries(): List<Entry> = entries

    private fun conn(): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 120000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) PayloadExtractor/1.0")
        return c
    }

    private fun streamRange(start: Long, endIncl: Long): InputStream {
        val c = conn()
        c.setRequestProperty("Range", "bytes=$start-$endIncl")
        c.connect()
        if (c.responseCode != 206) {
            val code = try { c.responseCode } catch (_: Exception) { -1 }
            c.disconnect()
            throw IOException("服务器不支持 Range 分段下载 (HTTP $code)")
        }
        if (totalSize < 0) {
            c.getHeaderField("Content-Range")?.let {
                totalSize = it.substringAfterLast('/').toLongOrNull() ?: -1L
            }
        }
        return c.inputStream
    }

    fun readRange(start: Long, endIncl: Long): ByteArray {
        streamRange(start, endIncl).use { ins ->
            val expect = endIncl - start + 1
            val out = ByteArrayOutputStream(expect.toInt().coerceAtLeast(64))
            val buf = ByteArray(BUF)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            if (out.size().toLong() != expect)
                throw IOException("分段下载数据不完整: 期望 $expect 实际 ${out.size()}")
            return out.toByteArray()
        }
    }

    /** 解析中央目录。 */
    fun open() {
        // 1. 探测: 服务器是否支持 Range
        val probe = conn()
        probe.setRequestProperty("Range", "bytes=0-0")
        probe.connect()
        if (probe.responseCode != 206) {
            val code = try { probe.responseCode } catch (_: Exception) { -1 }
            probe.disconnect()
            throw IOException("该服务器不支持断点续传(HTTP $code)，无法在线提取")
        }
        val cr = probe.getHeaderField("Content-Range")
            ?: throw IOException("响应缺少 Content-Range 头")
        totalSize = cr.substringAfterLast('/').toLongOrNull()
            ?: throw IOException("无法解析文件大小: $cr")
        try { probe.inputStream.close() } catch (_: Exception) {}
        probe.disconnect()
        if (totalSize < 64) throw IOException("文件太小，不是有效的 zip")

        // 2. 尾部找 EOCD (含 ZIP64)
        val tailLen = minOf(70000L, totalSize)
        val tail = readRange(totalSize - tailLen, totalSize - 1)

        var eocdPos = -1
        var i = tail.size - 22
        while (i >= 0) {
            if (sigAt(tail, i, SIG_EOCD)) { eocdPos = i; break }
            i--
        }
        if (eocdPos < 0) throw IOException("未找到 ZIP 目录(EOCD)，可能不是 zip 文件")

        val eb = ByteBuffer.wrap(tail, eocdPos + 4, 18).order(ByteOrder.LITTLE_ENDIAN)
        eb.short; eb.short; eb.short
        val cdEntries = eb.short.toInt() and 0xffff
        var cdSize = eb.int.toLong() and 0xffffffffL
        var cdOff = eb.int.toLong() and 0xffffffffL

        if (cdOff == 0xffffffffL || cdSize == 0xffffffffL || cdEntries == 0xffff) {
            // ZIP64: 定位器 -> ZIP64 EOCD
            var locPos = -1
            var j = tail.size - 20
            while (j >= 0) {
                if (sigAt(tail, j, SIG_ZIP64_LOC)) { locPos = j; break }
                j--
            }
            if (locPos < 0) throw IOException("ZIP64 定位器未找到")
            val z64Off = ByteBuffer.wrap(tail, locPos + 8, 8)
                .order(ByteOrder.LITTLE_ENDIAN).long
            val z = readRange(z64Off, z64Off + 55)
            if (!sigAt(z, 0, SIG_ZIP64_EOCD)) throw IOException("ZIP64 EOCD 校验失败")
            cdSize = ByteBuffer.wrap(z, 40, 8).order(ByteOrder.LITTLE_ENDIAN).long
            cdOff = ByteBuffer.wrap(z, 48, 8).order(ByteOrder.LITTLE_ENDIAN).long
        }

        // 3. 下载中央目录并解析条目
        val cd = readRange(cdOff, cdOff + cdSize - 1)
        var p = 0
        while (p + 46 <= cd.size && sigAt(cd, p, SIG_CDH)) {
            val b = ByteBuffer.wrap(cd, p + 4, 42).order(ByteOrder.LITTLE_ENDIAN)
            b.short // version made
            b.short // version needed
            b.short // flags
            val method = b.short.toInt() and 0xffff
            b.short; b.short // time, date
            b.int             // crc
            var csize = b.int.toLong() and 0xffffffffL
            var usize = b.int.toLong() and 0xffffffffL
            val nlen = b.short.toInt() and 0xffff
            val elen = b.short.toInt() and 0xffff
            val clen = b.short.toInt() and 0xffff
            b.short // disk
            b.short // internal attr
            b.int   // external attr
            var lho = b.int.toLong() and 0xffffffffL

            val name = String(cd, p + 46, nlen, Charsets.UTF_8)
            val extra = cd.copyOfRange(p + 46 + nlen, p + 46 + nlen + elen)

            // ZIP64 扩展字段
            if (usize == 0xffffffffL || csize == 0xffffffffL || lho == 0xffffffffL) {
                var q = 0
                while (q + 4 <= extra.size) {
                    val id = ByteBuffer.wrap(extra, q, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
                    val sz = ByteBuffer.wrap(extra, q + 2, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
                    if (id == 0x0001) {
                        var r = q + 4
                        if (usize == 0xffffffffL && r + 8 <= q + 4 + sz) {
                            usize = ByteBuffer.wrap(extra, r, 8).order(ByteOrder.LITTLE_ENDIAN).long; r += 8
                        }
                        if (csize == 0xffffffffL && r + 8 <= q + 4 + sz) {
                            csize = ByteBuffer.wrap(extra, r, 8).order(ByteOrder.LITTLE_ENDIAN).long; r += 8
                        }
                        if (lho == 0xffffffffL && r + 8 <= q + 4 + sz) {
                            lho = ByteBuffer.wrap(extra, r, 8).order(ByteOrder.LITTLE_ENDIAN).long; r += 8
                        }
                        break
                    }
                    q += 4 + sz
                }
            }
            entries.add(Entry(name, method, csize, usize, lho))
            p += 46 + nlen + elen + clen
        }
        if (entries.isEmpty()) throw IOException("ZIP 目录中没有条目")
    }

    /** 条目数据区在 zip 中的绝对偏移（需要再读一次本地文件头）。 */
    fun dataOffset(e: Entry): Long {
        val lh = readRange(e.lho, e.lho + 29)
        if (!sigAt(lh, 0, SIG_LFH)) throw IOException("本地文件头校验失败: ${e.name}")
        val nb = ByteBuffer.wrap(lh, 26, 4).order(ByteOrder.LITTLE_ENDIAN)
        val nlen = nb.short.toInt() and 0xffff
        val elen = nb.short.toInt() and 0xffff
        return e.lho + 30 + nlen + elen
    }

    /** 读取条目内部 [off, off+len) 的一段数据（解压后的）。payload.bin manifest 用。 */
    fun readEntryBytes(e: Entry, off: Long, len: Int): ByteArray {
        val dOff = dataOffset(e)
        return when (e.method) {
            0 -> readRange(dOff + off, dOff + off + len - 1)
            8 -> {
                var skipped = 0L
                val out = ByteArrayOutputStream(len)
                streamRange(dOff, dOff + e.csize - 1).use { raw ->
                    InflaterInputStream(raw, Inflater(true), BUF).use { ins ->
                        val buf = ByteArray(BUF)
                        while (out.size() < len) {
                            val want = if (skipped < off)
                                ((off - skipped).coerceAtMost(BUF.toLong())).toInt()
                            else (len - out.size()).coerceAtMost(BUF)
                            val n = ins.read(buf, 0, want)
                            if (n < 0) throw IOException("条目数据提前结束: ${e.name}")
                            if (skipped < off) skipped += n else out.write(buf, 0, n)
                        }
                    }
                }
                out.toByteArray()
            }
            else -> throw IOException("不支持的 zip 压缩方式 method=$e.method (${e.name})")
        }
    }

    /** 流式读取整个条目，onCompressed 汇报压缩侧进度，body 收到解压后的流。 */
    fun <T> streamEntry(
        e: Entry,
        onCompressed: (Long, Long) -> Unit,
        body: (InputStream) -> T
    ): T {
        val dOff = dataOffset(e)
        streamRange(dOff, dOff + e.csize - 1).use { raw ->
            val counting = CountingStream(raw, onCompressed)
            val src: InputStream = if (e.method == 8)
                InflaterInputStream(counting, Inflater(true), BUF) else counting
            return body(src)
        }
    }

    /** 提取整个条目到输出流。 */
    fun extractEntry(e: Entry, out: OutputStream, onProgress: (Long, Long) -> Unit) {
        streamEntry(e, { c, _ -> onProgress(c, e.csize) }) { ins -> ins.copyTo(out, BUF) }
        out.flush()
    }

    private class CountingStream(
        ins: InputStream,
        private val onCount: (Long, Long) -> Unit
    ) : InputStream() {
        private val src: InputStream = ins
        var count = 0L
            private set
        private val one = ByteArray(1)

        override fun read(): Int {
            val n = src.read(one, 0, 1)
            if (n > 0) { count += n; onCount(count, -1) }
            return if (n == 1) one[0].toInt() and 0xff else -1
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = src.read(b, off, len)
            if (n > 0) { count += n; onCount(count, -1) }
            return n
        }
        override fun close() = src.close()
    }

    companion object {
        private const val BUF = 1 shl 16
        private const val SIG_EOCD = 0x06054b50
        private const val SIG_CDH = 0x02014b50
        private const val SIG_LFH = 0x04034b50
        private const val SIG_ZIP64_LOC = 0x07064b50
        private const val SIG_ZIP64_EOCD = 0x06064b50

        private fun sigAt(b: ByteArray, p: Int, sig: Int): Boolean =
            p + 4 <= b.size &&
                b[p].toInt() == (sig and 0xff) &&
                b[p + 1].toInt() == ((sig shr 8) and 0xff) &&
                b[p + 2].toInt() == ((sig shr 16) and 0xff) &&
                b[p + 3].toInt() == ((sig shr 24) and 0xff)
    }
}
