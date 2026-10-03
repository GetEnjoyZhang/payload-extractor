package test

import com.sun.net.httpserver.HttpServer
import com.zcode.payloadextractor.core.Extractor
import com.zcode.payloadextractor.core.PayloadReader
import com.zcode.payloadextractor.core.RemoteZip
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ---------- protobuf 编码助手 ----------
private fun varint(v0: Long): ByteArray {
    var v = v0
    val out = ArrayList<Byte>()
    while (true) {
        if (v and 0x7f.inv() == 0L) { out.add(v.toByte()); break }
        out.add(((v and 0x7f) or 0x80).toByte())
        v = v ushr 7
    }
    return out.toByteArray()
}
private fun fVar(num: Int, v: Long) = varint(((num shl 3) or 0).toLong()) + varint(v)
private fun fBytes(num: Int, b: ByteArray) = varint(((num shl 3) or 2).toLong()) + varint(b.size.toLong()) + b
private fun fStr(num: Int, s: String) = fBytes(num, s.toByteArray())
private fun ext(s: Long, n: Long) = fVar(1, s) + fVar(2, n)

// ---------- 合成 payload ----------
private fun buildPayload(withUnsupportedOp: Boolean = false): ByteArray {
    val bs = 4096L
    fun xzOf(b: ByteArray): ByteArray {
        val os = ByteArrayOutputStream()
        val xz = XZOutputStream(os, LZMA2Options())
        xz.write(b); xz.close()
        return os.toByteArray()
    }
    fun bz2Of(b: ByteArray): ByteArray {
        val os = ByteArrayOutputStream()
        val bz = BZip2CompressorOutputStream(os)
        bz.write(b); bz.close()
        return os.toByteArray()
    }
    val aData = ByteArray((4 * bs).toInt()) { 'A'.code.toByte() }
    val bData = ByteArray((2 * bs).toInt()) { 'B'.code.toByte() }
    val cData = ByteArray((2 * bs).toInt()) { 'C'.code.toByte() }
    val bXz = xzOf(bData)
    val cBz2 = bz2Of(cData)

    var off = 0L
    fun op(type: Int, dst: Pair<Long, Long>, data: ByteArray?): Pair<ByteArray, Int> {
        val b = StringBuilder()
        var bytes = byteArrayOf()
        bytes += fVar(1, type.toLong())
        val realOff = if (data != null) off else -1L
        if (data != null) { bytes += fVar(2, realOff); bytes += fVar(3, data.size.toLong()) }
        bytes += fBytes(5, ext(dst.first, dst.second))
        if (data != null) off += data.size
        return Pair(bytes, 0)
    }
    val (op1, _) = op(0, Pair(0, 4), aData)                 // REPLACE
    val (op2, _) = op(8, Pair(4, 2), bXz)                  // REPLACE_XZ
    val op3 = fVar(1, 6) + fBytes(5, ext(6, 2))            // ZERO
    val (op4, _) = op(1, Pair(8, 2), cBz2)                 // REPLACE_BZ
    val partition = fStr(1, "boot") +
        fBytes(8, op1) + fBytes(8, op2) + fBytes(8, op3) + fBytes(8, op4)
    val manifest = fVar(2, bs) + fBytes(6, partition)

    // 数据区
    val data = aData + bXz + cBz2

    val head = "CrAU".toByteArray() +
        java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(2).array() +
        java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(manifest.size.toLong()).array() +
        java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(0).array()
    return head + manifest + data
}

// ---------- zip 打包 ----------
private fun zipOf(entries: Map<String, ByteArray>, deflate: Boolean): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { z ->
        // 添加一个干扰条目，模拟真实 OTA 包
        val rd = ZipEntry("META-INF/com/android/metadata")
        z.putNextEntry(rd)
        z.write("ota-type=FULL\n".toByteArray())
        z.closeEntry()
        for ((name, data) in entries) {
            val e = ZipEntry(name)
            if (!deflate) {
                e.method = ZipEntry.STORED
                e.size = data.size.toLong()
                e.crc = CRC32().apply { update(data) }.value
            }
            z.putNextEntry(e)
            z.write(data)
            z.closeEntry()
        }
    }
    return bos.toByteArray()
}

// ---------- 本地 HTTP Range 服务器 ----------
private fun serve(data: ByteArray): String {
    val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext("/") { ex ->
        try {
            val range = ex.requestHeaders.getFirst("Range")
            if (range != null && range.startsWith("bytes=")) {
                val m = Regex("bytes=(\\d+)-(\\d*)").find(range)!!
                val start = m.groupValues[1].toLong()
                val end = if (m.groupValues[2].isEmpty()) data.size - 1L
                else minOf(m.groupValues[2].toLong(), data.size - 1L)
                val body = data.sliceArray(start.toInt()..end.toInt())
                ex.responseHeaders.add("Content-Range", "bytes $start-$end/${data.size}")
                ex.sendResponseHeaders(206, if (body.isEmpty()) -1 else body.size.toLong())
                ex.responseBody.use { it.write(body) }
            } else {
                ex.sendResponseHeaders(200, data.size.toLong())
                ex.responseBody.use { it.write(data) }
            }
        } catch (_: Exception) { } finally { ex.close() }
    }
    s.executor = Executors.newFixedThreadPool(4) { r ->
        Thread(r).apply { isDaemon = true }
    }
    s.start()
    return "http://127.0.0.1:${s.address.port}/ota.zip"
}

private fun p(s: String) { println(s); System.out.flush() }

private fun hex(b: ByteArray, off: Int, len: Int) =
    b.sliceArray(off until off + len).joinToString(" ") { "%02x".format(it) }

private fun verifyBoot(out: ByteArray) {
    val bs = 4096
    try {
        check(out.size == 10 * bs) { "大小不符: ${out.size}" }
        check((0 until 4 * bs).all { out[it] == 'A'.code.toByte() }) { "块0-3 不是 A" }
        check((4 * bs until 6 * bs).all { out[it] == 'B'.code.toByte() }) { "块4-5 不是 B" }
        check((6 * bs until 8 * bs).all { out[it] == 0.toByte() }) { "块6-7 不是零" }
        check((8 * bs until 10 * bs).all { out[it] == 'C'.code.toByte() }) { "块8-9 不是 C" }
        p("  ✓ 合成分区重建内容正确 (${out.size} 字节)")
    } catch (e: IllegalStateException) {
        p("  ✗ 校验失败: ${e.message}")
        p("  [0]   ${hex(out, 0, 16)}")
        p("  [16376] ${hex(out, 16376, 16)}")
        p("  [16384] ${hex(out, 16384, 16)}")
        p("  [24568] ${hex(out, 24568, 16)}")
        p("  [24576] ${hex(out, 24576, 16)}")
        p("  [32760] ${hex(out, 32760, 16)}")
        p("  [32768] ${hex(out, 32768, 16)}")
        p("  [40952] ${hex(out, 40952, 16)}")
        throw e
    }
}

fun main(args: Array<String>) {
    // ---- 测试1: stored payload.bin 随机访问路径 ----
    p("[T1] payload.bin (stored) 随机访问提取")
    val payload = buildPayload()
    val url1 = serve(zipOf(mapOf("payload.bin" to payload), deflate = false))
    run {
        val z = RemoteZip(url1); z.open()
        val e = z.listEntries().first { it.name == "payload.bin" }
        check(e.randomAccess) { "stored 条目应可随机访问" }
        val mf = PayloadReader.parse { off, len -> z.readEntryBytes(e, off, len) }
        check(mf.partitions.size == 1 && mf.partitions[0].name == "boot") { "分区解析错误" }
        val boot = mf.partitions[0]
        check(boot.size == 40960L) { "boot 大小 ${boot.size}" }
        val out = java.io.File("build_t1_boot.img")
        Extractor.extractPartition(z, e, mf, boot, out) { ph, c, t ->
            p("    $ph $c/$t")
        }
        verifyBoot(out.readBytes())
        out.delete()
    }

    // ---- 测试2: deflated payload.bin 流式路径 ----
    p("[T2] payload.bin (deflate) 流式提取")
    val url2 = serve(zipOf(mapOf("payload.bin" to payload), deflate = true))
    run {
        val z = RemoteZip(url2); z.open()
        val e = z.listEntries().first { it.name == "payload.bin" }
        check(!e.randomAccess) { "deflate 条目应走流式路径" }
        val mf = PayloadReader.parse { off, len -> z.readEntryBytes(e, off, len) }
        val out = java.io.File("build_t2_boot.img")
        Extractor.extractPartition(z, e, mf, mf.partitions[0], out) { ph, c, t ->
            p("    $ph $c/$t")
        }
        verifyBoot(out.readBytes())
        out.delete()
    }

    // ---- 测试3: 增量包明确报错 ----
    p("[T3] 含 PUFFDIFF 的增量包应报错")
    run {
        val z = RemoteZip(url1); z.open()
        val e = z.listEntries().first { it.name == "payload.bin" }
        val mf = PayloadReader.parse { off, len -> z.readEntryBytes(e, off, len) }
        mf.partitions[0].ops.add(
            PayloadReader.Op().apply { type = 9; dst.add(longArrayOf(10, 2)) }
        )
        try {
            Extractor.extractPartition(z, e, mf, mf.partitions[0], java.io.File("build_t3.img")) { _, _, _ -> }
            throw AssertionError("应当抛出 UnsupportedPayloadException")
        } catch (ex: Extractor.UnsupportedPayloadException) {
            p("  ✓ 正确拒绝: ${ex.message}")
        }
    }

    // ---- 测试4: 直接提取普通条目 (deflate) ----
    p("[T4] 普通条目提取 (boot.img deflated)")
    run {
        val fake = ByteArray(100663296) { (it % 251).toByte() }
        val url = serve(zipOf(mapOf("boot.img" to fake), deflate = true))
        val z = RemoteZip(url); z.open()
        val e = z.listEntries().first { it.name == "boot.img" }
        val out = java.io.File("build_t4_boot.img")
        Extractor.extractEntry(z, e, out) { ph, c, t -> p("    $ph $c/$t") }
        check(out.length() == fake.size.toLong()) { "大小不符" }
        check(out.readBytes().sliceArray(0 until 1000).contentEquals(fake.sliceArray(0 until 1000))) { "内容不符" }
        out.delete()
        p("  ✓ 普通条目解压正确 (96MB)")
    }

    // ---- 测试5: 真实 URL (可传入参数则执行) ----
    if (args.isNotEmpty()) {
        p("[T5] 真实 OTA URL: ${args[0]}")
        val z = RemoteZip(args[0]); z.open()
        p("  总大小: ${z.totalSize / 1048576} MB, 条目数: ${z.listEntries().size}")
        val boot = z.listEntries().first { it.name == "boot.img" }
        val out = java.io.File("build_t5_boot.img")
        Extractor.extractEntry(z, boot, out) { ph, c, t -> print("\r    $ph $c/$t") }
        println()
        val head = out.readBytes().sliceArray(0 until 8).decodeToString()
        check(head == "ANDROID!") { "boot.img 魔数错误: $head" }
        check(out.length() == 100663296L) { "boot.img 大小错误: ${out.length()}" }
        p("  ✓ 真实包 boot.img 提取正确 (${out.length()} 字节, magic=$head)")
        out.delete()
    }

    p("全部测试通过 ✅")
}
