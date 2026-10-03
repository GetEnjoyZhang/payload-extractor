package com.zcode.payloadextractor.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 在线解析 payload.bin (ChromeOS update payload v2)。
 * 只下载 payload 头部 + manifest（protobuf），随后可按分区按需下载数据块。
 */
object PayloadReader {

    class Op {
        var type = 0
        var dataOffset = 0L
        var dataLength = 0L
        val src = ArrayList<LongArray>() // 每项 [startBlock, numBlocks]
        val dst = ArrayList<LongArray>()
    }

    class Partition {
        var name = ""
        val ops = ArrayList<Op>()
        var size = 0L // 分区大小（字节）：优先用 manifest 中的 ImageInfo.size
        var imageHash: ByteArray? = null // 新分区内容的 SHA-256（用于校验）
    }

    class Manifest {
        var blockSize = 4096L
        var version = 0L
        var manifestLen = 0L
        var metaSigLen = 0L
        var bigEndianHeader = false
        var dataStart = 0L // payload.bin 内分区数据区的起始偏移
        val partitions = ArrayList<Partition>()
        fun find(name: String): Partition? = partitions.firstOrNull { it.name == name }
    }

    fun parse(fetch: (offset: Long, length: Int) -> ByteArray): Manifest {
        val h = fetch(0, 24)
        if (h.size < 24 || h[0] != 0x43.toByte() || h[1] != 0x72.toByte() ||
            h[2] != 0x41.toByte() || h[3] != 0x55.toByte()
        ) throw IOException("payload 魔数错误（不是 CrAU）")

        // 标准小端；小米等厂商写的是大端，自动探测
        val le = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        var version = le.getLong(4)
        var manifestLen = le.getLong(12)
        var metaSigLen = le.getInt(20).toLong() and 0xffffffffL
        var bigEndian = false
        if (version !in 1..2) {
            val be = ByteBuffer.wrap(h).order(ByteOrder.BIG_ENDIAN)
            version = be.getLong(4)
            manifestLen = be.getLong(12)
            metaSigLen = be.getInt(20).toLong() and 0xffffffffL
            bigEndian = true
        }
        if (version !in 1..2) throw IOException("不支持的 payload 版本 $version（需要 v1/v2）")
        if (manifestLen <= 0 || manifestLen > 64L * 1024 * 1024)
            throw IOException("manifest 大小异常: $manifestLen")

        // v1 头部没有 metadata 签名长度字段（头 16 字节）
        if (version == 1L) metaSigLen = 0

        val mf = parseManifest(fetch(24, manifestLen.toInt()))
        mf.version = version
        mf.manifestLen = manifestLen
        mf.metaSigLen = metaSigLen
        mf.bigEndianHeader = bigEndian
        mf.dataStart = 24 + manifestLen + metaSigLen
        return mf
    }

    // ---------- 极简 protobuf (wire format) 解析 ----------

    private class Field(val num: Int, val longVal: Long, val bytesVal: ByteArray)

    private val EMPTY = ByteArray(0)

    private fun fields(buf: ByteArray): List<Field> {
        val out = ArrayList<Field>()
        var p = 0
        while (p < buf.size) {
            val (tag, np) = readVarint(buf, p)
            val num = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            p = np
            when (wire) {
                0 -> { val (v, np2) = readVarint(buf, p); out.add(Field(num, v, EMPTY)); p = np2 }
                1 -> {
                    if (p + 8 > buf.size) throw IOException("protobuf fixed64 越界")
                    out.add(Field(num, ByteBuffer.wrap(buf, p, 8).order(ByteOrder.LITTLE_ENDIAN).long, EMPTY))
                    p += 8
                }
                2 -> {
                    val (l, np2) = readVarint(buf, p)
                    val len = l.toInt()
                    p = np2
                    if (p + len > buf.size) throw IOException("protobuf 长度字段越界")
                    out.add(Field(num, 0, buf.copyOfRange(p, p + len)))
                    p += len
                }
                5 -> {
                    if (p + 4 > buf.size) throw IOException("protobuf fixed32 越界")
                    out.add(Field(num, ByteBuffer.wrap(buf, p, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong(), EMPTY))
                    p += 4
                }
                else -> throw IOException("protobuf wire type $wire 不支持")
            }
        }
        return out
    }

    private fun readVarint(buf: ByteArray, start: Int): Pair<Long, Int> {
        var shift = 0
        var result = 0L
        var p = start
        while (true) {
            if (p >= buf.size) throw IOException("varint 越界")
            val b = buf[p].toInt() and 0xff
            result = result or ((b and 0x7f).toLong() shl shift)
            p++
            if (b and 0x80 == 0) return Pair(result, p)
            shift += 7
            if (shift > 63) throw IOException("varint 过长")
        }
    }

    // ---------- manifest 结构 ----------

    private fun parseManifest(buf: ByteArray): Manifest {
        val mf = Manifest()
        var bsOld = -1L
        var bsNew = -1L
        for (f in fields(buf)) when (f.num) {
            // 旧 schema: block_size=2；新 schema (小米/新AOSP): block_size=3
            2 -> if (f.longVal > 0) bsOld = f.longVal
            3 -> if (f.longVal > 0) bsNew = f.longVal
            // 旧 schema: partitions=6；新 schema: partitions=13
            6, 13 -> parsePartition(f.bytesVal, mf)
        }
        mf.blockSize = when {
            bsOld >= 512 -> bsOld
            bsNew >= 512 -> bsNew
            else -> 4096L
        }
        if (mf.partitions.isEmpty()) throw IOException("manifest 中没有分区")
        return mf
    }

    private fun parsePartition(buf: ByteArray, mf: Manifest) {
        val p = Partition()
        var imgSize = 0L
        for (f in fields(buf)) when (f.num) {
            1 -> p.name = String(f.bytesVal, Charsets.UTF_8)
            // ImageInfo: 新分区大小 + 内容哈希（SHA-256），用于完成后校验
            7 -> for (g in fields(f.bytesVal)) when (g.num) {
                1 -> imgSize = g.longVal
                2 -> p.imageHash = g.bytesVal
            }
            8 -> p.ops.add(parseOp(f.bytesVal))
        }
        if (p.name.isNotEmpty()) {
            val maxEnd = p.ops.flatMap { it.dst }
                .maxOfOrNull { (it[0] + it[1]) * mf.blockSize } ?: 0L
            p.size = if (imgSize > maxEnd) imgSize else maxEnd
            mf.partitions.add(p)
        }
    }

    private fun parseOp(buf: ByteArray): Op {
        val op = Op()
        for (f in fields(buf)) when (f.num) {
            1 -> op.type = f.longVal.toInt()
            2 -> op.dataOffset = f.longVal
            3 -> op.dataLength = f.longVal
            4 -> op.src.add(parseExtent(f.bytesVal))
            // dst extents: 旧 schema 字段 5；新 schema (小米/新AOSP) 字段 6
            5, 6 -> op.dst.add(parseExtent(f.bytesVal))
        }
        return op
    }

    private fun parseExtent(buf: ByteArray): LongArray {
        var s = 0L
        var n = 0L
        for (f in fields(buf)) when (f.num) {
            1 -> s = f.longVal
            2 -> n = f.longVal
        }
        return longArrayOf(s, n)
    }
}
