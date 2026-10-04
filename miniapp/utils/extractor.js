// 分区/条目提取器 —— 移植自安卓版 Kotlin 实现
// 支持 REPLACE(0) / REPLACE_BZ(1) / REPLACE_XZ(8) / REPLACE_BROTLI(11) / ZERO(6) / DISCARD(7)
// sink: { setLength(len), writeAt(pos, u8), close() } 由调用方提供（小程序 fs / Node fs 适配）

var pako = require("../vendor/pako.js");
var bzip2 = require("../vendor/bzip2.js");
var brotliDecompress = require("../vendor/brotli/entry.js");
var lzma2mod = require("../vendor/lzma2.js");
var LZMA2 = lzma2mod.LZMA || lzma2mod;
var xz = require("./xz.js");

var SOURCE_OPS = { 2: 1, 3: 1, 4: 1, 5: 1, 9: 1 }; // MOVE BSDIFF SOURCE_COPY SOURCE_BSDIFF PUFFDIFF
var ZERO_OPS = { 6: 1, 7: 1 };                     // ZERO DISCARD
var MAX_CHUNK = 8 * 1024 * 1024; // JS 内存友好，8MB 一批
var MAX_WINDOW = 8 * 1024 * 1024;

function unsupportedType(t) { return new Error("不支持的操作类型 " + t); }

function decompressOp(type, raw) {
  if (type === 0) return raw;
  if (type === 1) {
    var bits = bzip2.array(raw);
    return bzip2.simple(bits);
  }
  if (type === 8) return xz.decodeXZ(raw);
  if (type === 11) return brotliDecompress(raw);
  throw unsupportedType(type);
}

function applyOp(a, buf, bufStart, sink, blockSize) {
  if (ZERO_OPS[a.op.type]) return; // 输出初始即全零
  var off = a.start - bufStart;
  var raw = (off === 0 && a.len === buf.length) ? buf : buf.subarray(off, off + a.len);
  var content = decompressOp(a.op.type, raw);
  var expected = 0, i;
  for (i = 0; i < a.op.dst.length; i++) expected += a.op.dst[i][1] * blockSize;
  if (content.length !== expected)
    throw new Error("解压后长度不符: 期望 " + expected + " 实际 " + content.length + " (type=" + a.op.type + ")");
  var wpos = 0;
  for (i = 0; i < a.op.dst.length; i++) {
    var ext = a.op.dst[i];
    var bytes = ext[1] * blockSize;
    sink.writeAt(ext[0] * blockSize, content, wpos, bytes);
    wpos += bytes;
  }
}

/** 提取 payload.bin 中的分区（仅支持 stored 随机访问的 payload.bin）。 */
function extractPartition(zip, entry, manifest, part, sink, onProgress, checkCancel) {
  var SOURCE = SOURCE_OPS, i, j;
  for (i = 0; i < part.ops.length; i++)
    if (SOURCE[part.ops[i].type])
      throw new Error("分区 " + part.name + " 含差量操作(MOVE/BSDIFF/PUFFDIFF等)，这是增量包，需要配合底包才能重建");
  if (part.size <= 0) throw new Error("分区 " + part.name + " 大小为 0");
  if (entry.method !== 0) throw new Error("该包的 payload.bin 为压缩存储，小程序暂不支持，请使用 App 版");

  onProgress = onProgress || function () {};
  checkCancel = checkCancel || function () {};

  return zip.dataOffset(entry).then(function (dOff) {
    var base = manifest.dataStart;
    var dataOps = [];
    for (i = 0; i < part.ops.length; i++) {
      var op = part.ops[i];
      if (op.dataLength > 0 && !ZERO_OPS[op.type])
        dataOps.push({ op: op, start: base + op.dataOffset, len: op.dataLength });
    }
    dataOps.sort(function (a, b) { return a.start - b.start; });
    var total = 0;
    for (i = 0; i < dataOps.length; i++) total += dataOps[i].len;

    sink.setLength(part.size);
    var done = 0, idx = 0;

    function nextChunk() {
      checkCancel();
      if (idx >= dataOps.length) return Promise.resolve();
      var start = dataOps[idx].start;
      var end = dataOps[idx].start + dataOps[idx].len;
      j = idx + 1;
      while (j < dataOps.length && dataOps[j].start + dataOps[j].len - start <= MAX_CHUNK) {
        end = dataOps[j].start + dataOps[j].len;
        j++;
      }
      return zip.readRange(dOff + start, dOff + end - 1).then(function (buf) {
        for (var k = idx; k < j; k++) {
          checkCancel();
          applyOp(dataOps[k], buf, start, sink, manifest.blockSize);
          done += dataOps[k].len;
        }
        onProgress("下载+写入", done, total);
        idx = j;
        return nextChunk();
      });
    }
    return nextChunk();
  });
}

/** 提取 zip 里的普通条目（自动处理 deflate）。 */
function extractEntry(zip, e, sink, onProgress, checkCancel) {
  onProgress = onProgress || function () {};
  checkCancel = checkCancel || function () {};
  onProgress("下载", 0, e.csize);
  return zip.dataOffset(e).then(function (dOff) {
    if (e.method === 0) {
      var pos = 0;
      function step() {
        checkCancel();
        if (pos >= e.usize) return Promise.resolve();
        var end = Math.min(dOff + pos + MAX_CHUNK, dOff + e.csize) - 1;
        return zip.readRange(dOff + pos, end).then(function (buf) {
          sink.writeAt(pos, buf, 0, buf.length);
          pos += buf.length;
          onProgress("下载", pos, e.usize);
          return step();
        });
      }
      return step();
    }
    // deflate: 一次取全部压缩数据再解压（boot.img 级别 22MB 可接受）
    return zip.readRange(dOff, dOff + e.csize - 1).then(function (raw) {
      checkCancel();
      onProgress("解压", e.csize, e.csize);
      var out = pako.inflateRaw(raw);
      if (out.length !== e.usize)
        throw new Error("解压后大小不符: 期望 " + e.usize + " 实际 " + out.length);
      sink.setLength(e.usize);
      sink.writeAt(0, out, 0, out.length);
    });
  });
}

module.exports = { extractPartition: extractPartition, extractEntry: extractEntry };
