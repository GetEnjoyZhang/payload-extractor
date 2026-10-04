// XZ 容器解析（调用纯 JS LZMA2 解码器）
// XZ = 流头(12B) + [块头 + LZMA2 数据 + 校验]* + 索引 + 流尾
var lzma2mod = require("../vendor/lzma2.js");
var LZMA2 = lzma2mod.LZMA || lzma2mod;

function crc32Table() {
  var t = new Uint32Array(256), c, n, k;
  for (n = 0; n < 256; n++) {
    c = n;
    for (k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    t[n] = c >>> 0;
  }
  return t;
}
var T = crc32Table();
function crc32(buf, start, end) {
  var c = 0xFFFFFFFF;
  for (var i = start || 0; i < (end == null ? buf.length : end); i++)
    c = T[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xFFFFFFFF) >>> 0;
}

function readVarint(b, p) {
  var v = 0, s = 0, r;
  while (true) {
    if (p >= b.length) throw new Error("xz varint 越界");
    r = b[p]; v += (r & 0x7f) * Math.pow(2, s); p++;
    if (!(r & 0x80)) return { v: v, p: p };
    s += 7;
  }
}

/** 扫描 LZMA2 流的实际长度（到终止块为止）。 */
function lzma2ScanSize(b) {
  var p = 0;
  while (p < b.length) {
    var c = b[p];
    if (c === 0x00) return p + 1; // 终止块
    if ((c & 0x80) === 0) {
      // 未压缩块: 控制字节 + 2B 大小 + 数据 + 2B 校验
      var size = (((c & 0x1f) << 8) | b[p + 1]) + 1;
      p += 3 + size + 2;
    } else {
      // LZMA 块: 5B 头 + 压缩数据
      var packed = ((b[p + 3] << 8) | b[p + 4]) + 1;
      p += 5 + packed;
    }
  }
  throw new Error("LZMA2 流缺少终止符");
}

/** 解码 XZ 数据（u8）为 Uint8Array。支持多块；仅支持 LZMA2 过滤器。 */
function decodeXZ(data) {
  if (!(data.length >= 12 && data[0] === 0xfd && data[1] === 0x37 && data[2] === 0x7a &&
    data[3] === 0x58 && data[4] === 0x5a && data[5] === 0x00))
    throw new Error("XZ 魔数错误");
  var flags = data[7]; // 检验类型低 2 位
  var checkSize = [0, 4, 4, 4, 8, 8, 8, 16, 32, 32, 32, 64][flags] || 0;
  var p = 12, out = [];
  while (true) {
    if (p >= data.length) throw new Error("XZ 数据提前结束");
    if (data[p] === 0x00) break; // 索引指示器，块结束
    // ---- 块头 ----
    var hs = (data[p] + 1) * 4; // 规范: 实际大小 = (值+1)*4，含尾部 CRC32
    var hFlags = data[p + 1];
    var nFilters = (hFlags & 3) + 1;
    var hasCSize = !!(hFlags & 0x40), hasUSize = !!(hFlags & 0x80);
    var q = p + 2, cSize = -1, uSize = -1, fi;
    if (hasCSize) { var r1 = readVarint(data, q); cSize = r1.v; q = r1.p; }
    if (hasUSize) { var r2 = readVarint(data, q); uSize = r2.v; q = r2.p; }
    var lzma2Start = -1, propsPos = -1;
    for (fi = 0; fi < nFilters; fi++) {
      var id = readVarint(data, q); q = id.p;
      var psz = readVarint(data, q); q = psz.p;
      if (id.v === 0x21) { lzma2Start = p + hs; propsPos = q; } // LZMA2 数据在整块头（含 CRC32）之后
      q += psz.v;
    }
    if (lzma2Start < 0) throw new Error("XZ 块使用了非 LZMA2 过滤器，不支持");
    // ---- 解码 LZMA2 ----
    // 该 LZMA2 库要求自定义容器: [1字节字典属性][8字节长度(0xFF=未知)][LZMA2 流]
    var lzma2Data = hasCSize
      ? data.subarray(lzma2Start, lzma2Start + cSize)
      : data.subarray(lzma2Start);
    var input = new Uint8Array(9 + lzma2Data.length);
    input[0] = data[propsPos];
    for (var k = 1; k <= 8; k++) input[k] = 0xff;
    input.set(lzma2Data, 9);
    var part = LZMA2.lzma2_decompress(input);
    if (part) out.push(part);
    // ---- 块填充（对齐到块起点的 4 倍数） + 校验字段 ----
    var dataEnd = lzma2Start + lzma2ScanSize(lzma2Data); // LZMA2 流实际消费的长度
    var after = dataEnd + ((4 - ((dataEnd - p) % 4)) % 4);
    p = after + checkSize;
  }
  if (!out.length) throw new Error("XZ 中没有数据块");
  var total = 0, i;
  for (i = 0; i < out.length; i++) total += out[i].length;
  var res = new Uint8Array(total);
  var off = 0;
  for (i = 0; i < out.length; i++) { res.set(out[i], off); off += out[i].length; }
  return res;
}

module.exports = { decodeXZ: decodeXZ, crc32: crc32 };
