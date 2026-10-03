package test

import com.zcode.payloadextractor.core.Extractor
import com.zcode.payloadextractor.core.PayloadReader
import com.zcode.payloadextractor.core.RemoteZip
import java.io.File

fun main() {
    val url = "https://bkt-sgp-miui-ota-update-alisgp.oss-ap-southeast-1.aliyuncs.com/V14.0.11.0.TLFCNXM/miui_DITING_V14.0.11.0.TLFCNXM_ce82686a6f_13.0.zip"
    val z = RemoteZip(url)
    z.open()
    println("总大小: ${z.totalSize / 1048576} MB, 条目: ${z.listEntries().size}")
    val pe = z.listEntries().first { it.name == "payload.bin" }
    println("payload.bin: method=${pe.method} csize=${pe.csize}")

    val mf = PayloadReader.parse { off, len -> z.readEntryBytes(pe, off, len) }
    println("✓ 解析成功: 版本=${mf.version} 大端=${mf.bigEndianHeader} blockSize=${mf.blockSize} 分区数=${mf.partitions.size}")
    for (p in mf.partitions) println("   %-12s %10.1f MB  ops=%d".format(p.name, p.size / 1048576.0, p.ops.size))

    // 提取最小的 vbmeta 验证数据正确性 + SHA-256 校验（对应 App 内建校验功能）
    val v = mf.partitions.firstOrNull { it.name == "vbmeta" }
    if (v != null) {
        val out = File("miui_vbmeta.img")
        val sink = com.zcode.payloadextractor.core.OutputImage.of(out)
        Extractor.extractPartition(z, pe, mf, v, sink, { ph, c, t -> }) { }
        sink.close()
        val bytes = out.readBytes()
        val head = bytes.sliceArray(0 until 4).decodeToString()
        println("vbmeta 提取: ${bytes.size} 字节, magic=$head")
        check(head == "AVB0") { "vbmeta 魔数错误: $head" }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        check(v.imageHash != null) { "manifest 未提供哈希" }
        check(digest.contentEquals(v.imageHash)) {
            "SHA-256 不符: 计算=${digest.joinToString(""){"%02x".format(it)}.take(16)}... 期望=${v.imageHash!!.joinToString(""){"%02x".format(it)}.take(16)}..."
        }
        println("✓ 真实小米包 vbmeta 提取正确 (AVB0 + SHA-256 校验通过)")
        out.delete()
    }
}
