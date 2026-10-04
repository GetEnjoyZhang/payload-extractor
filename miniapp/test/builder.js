// 测试辅助: 合成 payload + zip 构建器
var zlib = require("zlib");
var { crc32 } = require("../utils/xz.js");

function buildSyntheticPayload(withPuffdiff) {
  function varint(v) { v = BigInt(v); var o = []; while (true) { if ((v & ~0x7fn) === 0n) { o.push(Number(v)); break; } o.push(Number((v & 0x7fn) | 0x80n)); v >>= 7n; } return Uint8Array.from(o); }
  function fVar(n, v) { return concat([Uint8Array.from(varint((n << 3) | 0)), Uint8Array.from(varint(v))]); }
  function fBytes(n, b) { return concat([Uint8Array.from(varint((n << 3) | 2)), Uint8Array.from(varint(b.length)), b]); }
  function fStr(n, s) { return fBytes(n, Buffer.from(s)); }
  function ext(s, n) { return concat([fVar(1, s), fVar(2, n)]); }
  function concat(arrs) { var len = arrs.reduce(function (s, a) { return s + a.length; }, 0); var o = new Uint8Array(len), p = 0; arrs.forEach(function (a) { o.set(a, p); p += a.length; }); return o; }

  var aData = Buffer.alloc(4 * 4096, 0x41);
  var cData = Buffer.alloc(2 * 4096, 0x43);
  var off = 0;
  function opRaw(type, dst, data) {
    var parts = [fVar(1, type)];
    if (data) { parts.push(fVar(2, off)); parts.push(fVar(3, data.length)); off += data.length; }
    parts.push(fBytes(6, ext(dst[0], dst[1]))); // 新 schema: dst extents = 字段 6
    return concat(parts);
  }
  var op1 = opRaw(0, [0, 4], aData);
  var op3 = concat([fVar(1, 6), fBytes(6, ext(6, 2))]); // ZERO
  var op2 = opRaw(0, [8, 2], cData);
  var opPuff = concat([fVar(1, 9), fBytes(6, ext(10, 2))]); // PUFFDIFF(仅用于拒绝测试)
  var partition = concat([fStr(1, "boot"), fBytes(8, op1), fBytes(8, op3), fBytes(8, op2)]);
  if (withPuffdiff) partition = concat([partition, fBytes(8, opPuff)]);
  // 新 schema: block_size = 字段 3, partitions = 字段 13
  var manifest = concat([fVar(3, 4096), fBytes(13, partition)]);
  var head = Buffer.from("CrAU");
  var ver = Buffer.alloc(8); ver.writeUInt32BE(0, 0); ver.writeUInt32BE(2, 4); // 大端版本 2
  var ml = Buffer.alloc(8); ml.writeUInt32BE(0, 0); ml.writeUInt32BE(manifest.length, 4);
  var ms = Buffer.alloc(4); ms.writeUInt32BE(0, 0);
  return Buffer.concat([head, ver, ml, ms, manifest, aData, cData]);
}

function zipBytes(entries, deflate) {
  var parts = [], central = [], offset = 0;
  entries.forEach(function (e) {
    var data = Buffer.from(e.data);
    var method = 0, payload = data;
    if (deflate) { method = 8; payload = zlib.deflateRawSync(data); }
    var lfh = Buffer.alloc(30);
    lfh.writeUInt32LE(0x04034b50, 0); lfh.writeUInt16LE(method, 8);
    lfh.writeUInt32LE(crc32(data), 14);
    lfh.writeUInt32LE(payload.length, 18); lfh.writeUInt32LE(data.length, 22);
    lfh.writeUInt16LE(e.name.length, 26);
    central.push({ name: e.name, method: method, csize: payload.length, usize: data.length, lho: offset, crc: crc32(data) });
    parts.push(lfh, Buffer.from(e.name), payload);
    offset += 30 + e.name.length + payload.length;
  });
  var cdStart = offset;
  central.forEach(function (c) {
    var cd = Buffer.alloc(46);
    cd.writeUInt32LE(0x02014b50, 0); cd.writeUInt16LE(c.method, 10);
    cd.writeUInt32LE(c.crc, 16); cd.writeUInt32LE(c.csize, 20); cd.writeUInt32LE(c.usize, 24);
    cd.writeUInt16LE(c.name.length, 28);
    cd.writeUInt32LE(c.lho, 42);
    parts.push(cd, Buffer.from(c.name));
    offset += 46 + c.name.length;
  });
  var eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0); eocd.writeUInt16LE(central.length, 10);
  eocd.writeUInt32LE(offset - cdStart, 12); eocd.writeUInt32LE(cdStart, 16);
  parts.push(eocd);
  return Buffer.concat(parts);
}

module.exports = { buildSyntheticPayload: buildSyntheticPayload, zipBytes: zipBytes };
