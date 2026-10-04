// 远程 ZIP 在线解析（HTTP Range 版）——移植自安卓版 Kotlin 实现
// 依赖注入 getRange(url, start, endIncl) -> Promise<{buf: Uint8Array, total: number|null}>

var SIG_EOCD = 0x06054b50, SIG_CDH = 0x02014b50, SIG_LFH = 0x04034b50;
var SIG_Z64LOC = 0x07064b50, SIG_Z64EOCD = 0x06064b50;

function sigAt(b, p, sig) {
  if (p + 4 > b.length) return false;
  return b[p] === (sig & 0xff) && b[p + 1] === ((sig >>> 8) & 0xff) &&
    b[p + 2] === ((sig >>> 16) & 0xff) && b[p + 3] === ((sig >>> 24) & 0xff);
}

function utf8Decode(b) {
  var out = "", i = 0;
  while (i < b.length) {
    var c = b[i];
    if (c < 0x80) { out += String.fromCharCode(c); i++; }
    else if (c < 0xe0) { out += String.fromCharCode(((c & 0x1f) << 6) | (b[i + 1] & 0x3f)); i += 2; }
    else if (c < 0xf0) {
      out += String.fromCharCode(((c & 0x0f) << 12) | ((b[i + 1] & 0x3f) << 6) | (b[i + 2] & 0x3f));
      i += 3;
    } else {
      var cp = ((c & 0x07) << 18) | ((b[i + 1] & 0x3f) << 12) | ((b[i + 2] & 0x3f) << 6) | (b[i + 3] & 0x3f);
      cp -= 0x10000;
      out += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff));
      i += 4;
    }
  }
  return out;
}

function RemoteZip(url, getRange) {
  this.url = url;
  this.getRange = getRange; // (url, start, endIncl) -> {buf, total}
  this.totalSize = -1;
  this.entries = [];
}

/** 读取 [start, endIncl]。 */
RemoteZip.prototype.readRange = function (start, endIncl) {
  var self = this;
  return self.getRange(self.url, start, endIncl).then(function (r) {
    if (r.total != null && self.totalSize < 0) self.totalSize = r.total;
    return r.buf;
  });
};

/** 解析中央目录（支持 ZIP64）。 */
RemoteZip.prototype.open = function () {
  var self = this;
  return self.readRange(0, 0).then(function () {
    if (self.totalSize < 64) throw new Error("文件太小，不是有效的 zip");
    var tailLen = Math.min(70000, self.totalSize);
    return self.readRange(self.totalSize - tailLen, self.totalSize - 1).then(function (tail) {
      var eocd = -1, i = tail.length - 22;
      while (i >= 0) { if (sigAt(tail, i, SIG_EOCD)) { eocd = i; break; } i--; }
      if (eocd < 0) throw new Error("未找到 ZIP 目录(EOCD)，可能不是 zip 文件");
      var dv = new DataView(tail.buffer, tail.byteOffset + eocd + 4, 18);
      dv.getUint16(0, true); dv.getUint16(2, true); dv.getUint16(4, true);
      var cdEntries = dv.getUint16(6, true);
      var cdSize = dv.getUint32(8, true) >>> 0;
      var cdOff = dv.getUint32(12, true) >>> 0;
      if (cdOff === 0xffffffff || cdSize === 0xffffffff || cdEntries === 0xffff) {
        var loc = -1, j = tail.length - 20;
        while (j >= 0) { if (sigAt(tail, j, SIG_Z64LOC)) { loc = j; break; } j--; }
        if (loc < 0) throw new Error("ZIP64 定位器未找到");
        var dv2 = new DataView(tail.buffer, tail.byteOffset + loc + 8, 8);
        var z64Off = Number(dv2.getBigUint64(0, true));
        return self.readRange(z64Off, z64Off + 55).then(function (z) {
          if (!sigAt(z, 0, SIG_Z64EOCD)) throw new Error("ZIP64 EOCD 校验失败");
          var dv3 = new DataView(z.buffer, z.byteOffset + 40, 16);
          cdSize = Number(dv3.getBigUint64(0, true));
          cdOff = Number(dv3.getBigUint64(8, true));
          return self._readCD(cdOff, cdSize);
        });
      }
      return self._readCD(cdOff, cdSize);
    });
  });
};

RemoteZip.prototype._readCD = function (cdOff, cdSize) {
  var self = this;
  return self.readRange(cdOff, cdOff + cdSize - 1).then(function (cd) {
    var p = 0;
    while (p + 46 <= cd.length && sigAt(cd, p, SIG_CDH)) {
      var dv = new DataView(cd.buffer, cd.byteOffset + p + 4, 42);
      dv.getUint16(0, true); dv.getUint16(2, true); dv.getUint16(4, true); // 版本/标志
      var method = dv.getUint16(6, true);
      dv.getUint16(8, true); dv.getUint16(10, true); // 时间/日期
      var crc = dv.getUint32(12, true) >>> 0;
      var csize = dv.getUint32(16, true) >>> 0;
      var usize = dv.getUint32(20, true) >>> 0;
      var nlen = dv.getUint16(24, true), elen = dv.getUint16(26, true), clen = dv.getUint16(28, true);
      dv.getUint16(30, true); dv.getUint16(32, true); dv.getUint32(34, true); // 盘号/属性
      var lho = dv.getUint32(38, true) >>> 0;
      var name = utf8Decode(cd.subarray(p + 46, p + 46 + nlen));
      var extra = cd.subarray(p + 46 + nlen, p + 46 + nlen + elen);
      // ZIP64 扩展
      if (usize === 0xffffffff || csize === 0xffffffff || lho === 0xffffffff) {
        var q = 0;
        while (q + 4 <= extra.length) {
          var id = extra[q] | (extra[q + 1] << 8);
          var sz = extra[q + 2] | (extra[q + 3] << 8);
          if (id === 1) {
            var r = q + 4;
            var rd64 = function () { var v = new DataView(extra.buffer, extra.byteOffset + r, 8).getBigUint64(0, true); r += 8; return Number(v); };
            if (usize === 0xffffffff && r + 8 <= q + 4 + sz) usize = rd64();
            if (csize === 0xffffffff && r + 8 <= q + 4 + sz) csize = rd64();
            if (lho === 0xffffffff && r + 8 <= q + 4 + sz) lho = rd64();
            break;
          }
          q += 4 + sz;
        }
      }
      self.entries.push({ name: name, method: method, csize: csize, usize: usize, lho: lho, crc: crc });
      p += 46 + nlen + elen + clen;
    }
    if (!self.entries.length) throw new Error("ZIP 目录中没有条目");
    return self.entries;
  });
};

/** 条目数据区在 zip 中的绝对偏移。 */
RemoteZip.prototype.dataOffset = function (e) {
  var self = this;
  return self.readRange(e.lho, e.lho + 29).then(function (lh) {
    if (!sigAt(lh, 0, SIG_LFH)) throw new Error("本地文件头校验失败: " + e.name);
    var nlen = lh[26] | (lh[27] << 8);
    var elen = lh[28] | (lh[29] << 8);
    return e.lho + 30 + nlen + elen;
  });
};

/** 读取条目内部 [off, off+len) 解压后的数据（仅支持 stored 随机访问）。 */
RemoteZip.prototype.readEntryBytes = function (e, off, len) {
  var self = this;
  return self.dataOffset(e).then(function (dOff) {
    if (e.method !== 0) throw new Error("该条目为压缩存储，无法随机读取: " + e.name);
    return self.readRange(dOff + off, dOff + off + len - 1);
  });
};

module.exports = { RemoteZip: RemoteZip, utf8Decode: utf8Decode };
