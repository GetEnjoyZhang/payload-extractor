// payload.bin (ChromeOS update payload v2) 在线解析 —— 移植自安卓版 Kotlin 实现
// 兼容: 大端头部（小米）/ 新版 manifest 字段编号（block_size=3, partitions=13, dst extents=6）
var utf8Decode = require("./remotezip.js").utf8Decode;

var MAGIC0 = 0x43, MAGIC1 = 0x72, MAGIC2 = 0x41, MAGIC3 = 0x55; // CrAU

function readVarint(b, p) {
  var shift = 0, result = 0, r;
  while (true) {
    if (p >= b.length) throw new Error("varint 越界");
    r = b[p];
    result += (r & 0x7f) * Math.pow(2, shift);
    p++;
    if (!(r & 0x80)) return { v: result, p: p };
    shift += 7;
    if (shift > 63) throw new Error("varint 过长");
  }
}

/** 通用 protobuf 字段遍历: 返回 [{num, wire, long(仅varint), bytes(仅wire2)}] */
function fields(buf) {
  var out = [], p = 0;
  while (p < buf.length) {
    var tag = readVarint(buf, p);
    var num = Math.floor(tag.v / 8), wire = tag.v % 8;
    p = tag.p;
    if (wire === 0) {
      var v = readVarint(buf, p); p = v.p;
      out.push({ num: num, wire: 0, long: v.v });
    } else if (wire === 2) {
      var l = readVarint(buf, p); p = l.p;
      out.push({ num: num, wire: 2, bytes: buf.subarray(p, p + l.v) });
      p += l.v;
    } else if (wire === 1) { p += 8; }
    else if (wire === 5) { p += 4; }
    else throw new Error("protobuf wire type " + wire + " 不支持");
  }
  return out;
}

function parseOp(buf) {
  var op = { type: 0, dataOffset: 0, dataLength: 0, dst: [] };
  var fl = fields(buf);
  for (var i = 0; i < fl.length; i++) {
    var f = fl[i];
    if (f.num === 1) op.type = f.long;
    else if (f.num === 2) op.dataOffset = f.long;
    else if (f.num === 3) op.dataLength = f.long;
    else if (f.num === 5 || f.num === 6) op.dst.push(parseExtent(f.bytes)); // 旧5/新6
  }
  return op;
}

function parseExtent(buf) {
  var s = 0, n = 0, fl = fields(buf), i;
  for (i = 0; i < fl.length; i++) {
    if (fl[i].num === 1) s = fl[i].long;
    else if (fl[i].num === 2) n = fl[i].long;
  }
  return [s, n];
}

function parsePartition(buf, blockSize) {
  var p = { name: "", ops: [], size: 0, imageHash: null };
  var imgSize = 0, fl = fields(buf), i, j;
  for (i = 0; i < fl.length; i++) {
    var f = fl[i];
    if (f.num === 1) p.name = utf8Decode(f.bytes);
    else if (f.num === 7) { // ImageInfo: 新分区大小 + SHA-256
      var g = fields(f.bytes);
      for (j = 0; j < g.length; j++) {
        if (g[j].num === 1) imgSize = g[j].long;
        else if (g[j].num === 2) p.imageHash = g[j].bytes;
      }
    } else if (f.num === 8) p.ops.push(parseOp(f.bytes));
  }
  if (p.name) {
    var maxEnd = 0;
    for (i = 0; i < p.ops.length; i++)
      for (j = 0; j < p.ops[i].dst.length; j++) {
        var e = p.ops[i].dst[j];
        maxEnd = Math.max(maxEnd, (e[0] + e[1]) * blockSize);
      }
    p.size = imgSize > maxEnd ? imgSize : maxEnd;
    return p;
  }
  return null;
}

/**
 * 解析 payload.bin。
 * fetchBytes(offset, length) -> Promise<Uint8Array>：在条目内按偏移取数据。
 */
function parse(fetchBytes) {
  return fetchBytes(0, 24).then(function (h) {
    if (h.length < 24 || h[0] !== MAGIC0 || h[1] !== MAGIC1 || h[2] !== MAGIC2 || h[3] !== MAGIC3)
      throw new Error("payload 魔数错误（不是 CrAU）");
    var dv = new DataView(h.buffer, h.byteOffset);
    var version = dv.getUint32(4, false) * 4294967296 + dv.getUint32(8, false); // 大端 u64
    var manifestLen = dv.getUint32(12, false) * 4294967296 + dv.getUint32(16, false);
    var metaSigLen = dv.getUint32(20, false);
    if (version < 1 || version > 2) { // 尝试小端（标准 AOSP）
      version = dv.getUint32(4, true) + dv.getUint32(8, true) * 4294967296;
      manifestLen = dv.getUint32(12, true) + dv.getUint32(16, true) * 4294967296;
      metaSigLen = dv.getUint32(20, true);
    }
    if (version < 1 || version > 2) throw new Error("不支持的 payload 版本 " + version);
    if (manifestLen <= 0 || manifestLen > 64 * 1024 * 1024) throw new Error("manifest 大小异常: " + manifestLen);

    return fetchBytes(24, manifestLen).then(function (m) {
      var blockSize = 4096, bsOld = -1, bsNew = -1;
      var partitions = [], fl = fields(m), i;
      for (i = 0; i < fl.length; i++) {
        var f = fl[i];
        if (f.num === 2 && f.wire === 0 && f.long > 0) bsOld = f.long;
        else if (f.num === 3 && f.wire === 0 && f.long > 0) bsNew = f.long;
        else if ((f.num === 6 || f.num === 13) && f.wire === 2) {
          var p = parsePartition(f.bytes, 4096);
          if (p) partitions.push(p);
        }
      }
      blockSize = bsOld >= 512 ? bsOld : (bsNew >= 512 ? bsNew : 4096);
      // 用真实 blockSize 重算分区大小
      for (i = 0; i < partitions.length; i++) {
        var pp = partitions[i], maxEnd = 0, j, k;
        for (j = 0; j < pp.ops.length; j++)
          for (k = 0; k < pp.ops[j].dst.length; k++) {
            var e = pp.ops[j].dst[k];
            maxEnd = Math.max(maxEnd, (e[0] + e[1]) * blockSize);
          }
        pp.size = pp.size > maxEnd ? pp.size : maxEnd;
      }
      if (!partitions.length) throw new Error("manifest 中没有分区");
      return {
        version: version, bigEndian: version !== 0 && manifestLen > 0 && bsNew > 0,
        blockSize: blockSize, partitions: partitions,
        dataStart: 24 + manifestLen + (version === 1 ? 0 : metaSigLen)
      };
    });
  });
}

module.exports = { parse: parse, fields: fields };
