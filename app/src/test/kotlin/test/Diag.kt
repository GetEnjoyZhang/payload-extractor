package test

import com.sun.net.httpserver.HttpServer
import com.zcode.payloadextractor.core.RemoteZip
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

fun main() {
    val bs = 4096L
    val bData = ByteArray((2 * bs).toInt()) { 'B'.code.toByte() }
    val bXz = ByteArrayOutputStream().also { os ->
        val xz = XZOutputStream(os, LZMA2Options()); xz.write(bData); xz.close()
    }.toByteArray()

    val op1 = fVar(1, 0L) + fVar(2, 0L) + fVar(3, 16384L) + fBytes(5, ext(0, 4))
    val op2 = fVar(1, 8L) + fVar(2, 16384L) + fVar(3, bXz.size.toLong()) + fBytes(5, ext(4, 2))
    val partition = fStr(1, "boot") + fBytes(8, op1) + fBytes(8, op2)
    val manifest = fVar(2, bs) + fBytes(6, partition)
    val aData = ByteArray(16384) { 'A'.code.toByte() }
    val payload = "CrAU".toByteArray() +
        java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(2).array() +
        java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(manifest.size.toLong()).array() +
        java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(0).array() +
        manifest + aData + bXz

    println("payload.bin 期望总长 = ${payload.size} (24 + manifest=${manifest.size} + data=${16384 + bXz.size})")

    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { z ->
        val rd = ZipEntry("META-INF/com/android/metadata")
        z.putNextEntry(rd); z.write("ota-type=FULL\n".toByteArray()); z.closeEntry()
        val e = ZipEntry("payload.bin")
        z.putNextEntry(e); z.write(payload); z.closeEntry()
    }
    val zipBytes = bos.toByteArray()

    val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext("/") { ex ->
        val range = ex.requestHeaders.getFirst("Range")
        if (range != null && range.startsWith("bytes=")) {
            val m = Regex("bytes=(\\d+)-(\\d*)").find(range)!!
            val start = m.groupValues[1].toLong()
            val end = if (m.groupValues[2].isEmpty()) zipBytes.size - 1L
            else minOf(m.groupValues[2].toLong(), zipBytes.size - 1L)
            val body = zipBytes.sliceArray(start.toInt()..end.toInt())
            ex.responseHeaders.add("Content-Range", "bytes $start-$end/${zipBytes.size}")
            ex.sendResponseHeaders(206, if (body.isEmpty()) -1 else body.size.toLong())
            ex.responseBody.use { it.write(body) }
        } else {
            ex.sendResponseHeaders(200, zipBytes.size.toLong())
            ex.responseBody.use { it.write(zipBytes) }
        }
        ex.close()
    }
    s.executor = Executors.newFixedThreadPool(4) { r -> Thread(r).apply { isDaemon = true } }
    s.start()

    val z = RemoteZip("http://127.0.0.1:${s.address.port}/ota.zip")
    z.open()
    val e = z.listEntries().first { it.name == "payload.bin" }
    val dOff = z.dataOffset(e)
    println("entry: lho=${e.lho} csize=${e.csize} usize=${e.usize} dOff=$dOff")
    println("dOff+csize=${dOff + e.csize} vs zip 总长=${zipBytes.size}")

    // 完整流式解压
    var total = 0L
    val chunks = ArrayList<Int>()
    z.streamEntry(e, { _, _ -> }, { ins ->
        val buf = ByteArray(4096)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (chunks.size < 40) chunks.add(n)
        }
    })
    println("流式解压总长 = $total (期望 usize=${e.usize})")
    println("前几次 read 返回: $chunks")
}
