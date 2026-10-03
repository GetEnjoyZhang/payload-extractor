package com.zcode.payloadextractor.core

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.brotli.dec.BrotliInputStream
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class OperationCancelledException : IOException("操作已取消")

/** 提取输出的抽象：本地文件或 SAF 文档的 FileChannel（由调用方管理生命周期回调）。 */
abstract class OutputImage : Closeable {
    abstract fun setLength(len: Long)
    abstract fun writeAt(pos: Long, data: ByteArray, off: Int, len: Int)

    companion object {
        fun of(file: File): OutputImage = FileImage(file)
        fun of(channel: FileChannel, onClose: (() -> Unit)? = null): OutputImage =
            ChannelImage(channel, onClose)
    }
}

private class FileImage(file: File) : OutputImage() {
    private val raf = RandomAccessFile(file, "rw")
    override fun setLength(len: Long) {
        raf.setLength(0)
        raf.setLength(len)
    }
    override fun writeAt(pos: Long, data: ByteArray, off: Int, len: Int) {
        raf.seek(pos)
        raf.write(data, off, len)
    }
    override fun close() = raf.close()
}

private class ChannelImage(
    private val ch: FileChannel,
    private val onClose: (() -> Unit)?
) : OutputImage() {
    override fun setLength(len: Long) {
        ch.truncate(0)
        if (len > 0) ch.write(ByteBuffer.wrap(byteArrayOf(0)), len - 1)
    }
    override fun writeAt(pos: Long, data: ByteArray, off: Int, len: Int) {
        ch.write(ByteBuffer.wrap(data, off, len), pos)
    }
    override fun close() {
        try { ch.close() } finally { try { onClose?.invoke() } catch (_: Exception) {} }
    }
}

/**
 * 从 OTA zip 中提取：
 * - 任意条目（直接解压保存）
 * - payload.bin 中的分区（只下载该分区用到的数据块，重建出原始镜像）
 *
 * 支持的操作: REPLACE / REPLACE_BZ / REPLACE_XZ / REPLACE_BROTLI / ZERO / DISCARD。
 * 含 MOVE/BSDIFF/SOURCE_COPY/SOURCE_BSDIFF/PUFFDIFF 的增量包需要旧镜像，明确报错。
 */
object Extractor {

    class UnsupportedPayloadException(msg: String) : IOException(msg)

    // MOVE BSDIFF SOURCE_COPY SOURCE_BSDIFF PUFFDIFF
    private val SOURCE_OPS = setOf(2, 3, 4, 5, 9)
    private val ZERO_OPS = setOf(6, 7) // ZERO DISCARD
    private const val MAX_CHUNK = 48L * 1024 * 1024
    private const val MAX_WINDOW = 32L * 1024 * 1024

    /** 提取 zip 里的普通条目（boot.img / vbmeta.img 等）。 */
    fun extractEntry(
        zip: RemoteZip, e: RemoteZip.Entry, out: OutputImage,
        onProgress: (phase: String, cur: Long, total: Long) -> Unit,
        checkCancel: () -> Unit = {}
    ) {
        zip.streamEntry(e, { c, _ -> onProgress("下载", c, e.csize) }, { ins ->
            var pos = 0L
            val buf = ByteArray(1 shl 16)
            while (true) {
                checkCancel()
                val n = ins.read(buf)
                if (n < 0) break
                out.writeAt(pos, buf, 0, n)
                pos += n
            }
        }, checkCancel)
    }

    /** 提取 payload.bin 里的分区并重建为原始镜像。 */
    fun extractPartition(
        zip: RemoteZip, entry: RemoteZip.Entry,
        mf: PayloadReader.Manifest, part: PayloadReader.Partition,
        out: OutputImage, onProgress: (String, Long, Long) -> Unit,
        checkCancel: () -> Unit = {}
    ) {
        if (part.ops.any { it.type in SOURCE_OPS })
            throw UnsupportedPayloadException(
                "分区 ${part.name} 含差量操作(MOVE/BSDIFF/PUFFDIFF等)，这是增量包，需要配合底包才能重建"
            )
        if (part.size <= 0) throw IOException("分区 ${part.name} 大小为 0")

        // AbsOp 的 start/end 以 payload.bin 条目内部为坐标系（不含 zip 偏移）；
        // dOff 只在随机访问路径构造 Range 时使用
        val dOff = zip.dataOffset(entry)
        val base = mf.dataStart
        val dataOps = part.ops
            .filter { it.dataLength > 0 && it.type !in ZERO_OPS }
            .map { AbsOp(it, base + it.dataOffset, base + it.dataOffset + it.dataLength) }
            .sortedBy { it.start }
        val total = dataOps.sumOf { it.end - it.start }

        out.use { o ->
            o.setLength(part.size)
            if (entry.randomAccess) {
                // 按 op 分批 Range 下载（每批不超过 MAX_CHUNK），range 用 zip 绝对偏移
                var i = 0
                var done = 0L
                while (i < dataOps.size) {
                    checkCancel()
                    var j = i
                    val start = dataOps[i].start
                    var end = dataOps[i].end
                    while (j + 1 < dataOps.size && dataOps[j + 1].end - start <= MAX_CHUNK) {
                        j++
                        end = dataOps[j].end
                    }
                    val buf = zip.readRange(dOff + start, dOff + end - 1, checkCancel)
                    for (k in i..j) applyOp(dataOps[k], buf, start, o, mf.blockSize)
                    done += (i..j).sumOf { dataOps[it].end - dataOps[it].start }
                    onProgress("下载+写入", done, total)
                    i = j + 1
                }
            } else {
                // payload.bin 在 zip 里是 deflate 存储: 从头顺序流式解压，捕获所需窗口
                streamPartition(zip, entry, dataOps, o, mf.blockSize, onProgress, total, checkCancel)
            }
        }
    }

    private class AbsOp(val op: PayloadReader.Op, val start: Long, val end: Long)

    private class Window(val start: Long, var end: Long) {
        val ops = ArrayList<AbsOp>()
    }

    private fun applyOp(
        a: AbsOp, buf: ByteArray, bufStart: Long,
        o: OutputImage, blockSize: Long
    ) {
        if (a.op.type in ZERO_OPS) return // 输出文件初始即为全零
        val off = (a.start - bufStart).toInt()
        val len = (a.end - a.start).toInt()
        val raw = if (off == 0 && len == buf.size) buf else buf.copyOfRange(off, off + len)

        // 流式解压写入 dst extents，内存占用与 op 大小无关
        val src: InputStream = when (a.op.type) {
            0 -> ByteArrayInputStream(raw)                                      // REPLACE
            1 -> BZip2CompressorInputStream(ByteArrayInputStream(raw))          // REPLACE_BZ
            8 -> XZInputStream(ByteArrayInputStream(raw))                       // REPLACE_XZ
            11 -> BrotliInputStream(ByteArrayInputStream(raw))                  // REPLACE_BROTLI
            else -> throw IOException("不支持的操作类型 ${a.op.type}")
        }
        src.use { ins ->
            val block = ByteArray(1 shl 16)
            for (ext in a.op.dst) {
                val bytes = ext[1] * blockSize
                var pos = ext[0] * blockSize
                var got = 0L
                while (got < bytes) {
                    val n = ins.read(block, 0, minOf(block.size.toLong(), bytes - got).toInt())
                    if (n < 0) throw IOException("op 数据解压后不足（type=${a.op.type}）")
                    o.writeAt(pos, block, 0, n)
                    pos += n
                    got += n
                }
            }
            if (ins.read() >= 0) throw IOException("op 数据解压后超出预期（type=${a.op.type}）")
        }
    }

    private fun streamPartition(
        zip: RemoteZip, entry: RemoteZip.Entry, ops: List<AbsOp>,
        o: OutputImage, blockSize: Long,
        onProgress: (String, Long, Long) -> Unit, total: Long,
        checkCancel: () -> Unit = {}
    ) {
        // 合并相邻窗口（限制单个窗口大小，避免流式路径内存爆炸）
        val windows = ArrayList<Window>()
        for (op in ops) {
            val last = windows.lastOrNull()
            if (last != null && op.start <= last.end && op.end - last.start <= MAX_WINDOW) {
                if (op.end > last.end) last.end = op.end
                last.ops.add(op)
            } else {
                val w = Window(op.start, op.end)
                w.ops.add(op)
                windows.add(w)
            }
        }
        var done = 0L
        var wi = 0
        zip.streamEntry(entry, { c, _ -> onProgress("下载(流式)", c, entry.csize) }, { ins ->
            val buf = ByteArray(1 shl 16)
            var pos = 0L
            while (wi < windows.size) {
                val w = windows[wi]
                // 跳到窗口起点
                while (pos < w.start) {
                    checkCancel()
                    val n = ins.read(buf, 0, (w.start - pos).coerceAtMost(buf.size.toLong()).toInt())
                    if (n < 0) throw IOException("payload 数据流提前结束")
                    pos += n
                }
                // 捕获窗口内容
                val data = ByteArray((w.end - w.start).toInt())
                var got = 0
                while (got < data.size) {
                    checkCancel()
                    val n = ins.read(data, got, data.size - got)
                    if (n < 0) throw IOException("payload 数据流提前结束")
                    got += n
                }
                pos = w.end
                for (op in w.ops) applyOp(op, data, w.start, o, blockSize)
                done += data.size
                onProgress("解压+写入", done, total)
                wi++
            }
        }, checkCancel)
    }
}
