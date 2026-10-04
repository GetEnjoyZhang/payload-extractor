// 小程序版核心逻辑 Node 测试
// T1 合成payload(新schema) | T2 deflate条目+CRC | T3 增量包拒绝 | T4 真实一加包 | T5 真实小米包(REPLACE_XZ+SHA-256)
// 运行: node test/core.test.js [real]
var fs = require("fs");
var path = require("path");
var { RemoteZip } = require("../utils/remotezip.js");
var payloadParser = require("../utils/payload.js");
var ex = require("../utils/extractor.js");
var { crc32 } = require("../utils/xz.js");
var sha256 = require("../vendor/sha256.js");
var { buildSyntheticPayload, zipBytes } = require("./builder.js");

var ONEPLUS = "https://gauss-opexcostmanual-cn.allawnfs.com/remove-d3ceef81c8c68c8e3cc6f730973a7405/component-ota/24/02/19/3cd19846753d45e2a3e6ad534620f81b.zip";
var MIUI = "https://bkt-sgp-miui-ota-update-alisgp.oss-ap-southeast-1.aliyuncs.com/V14.0.11.0.TLFCNXM/miui_DITING_V14.0.11.0.TLFCNXM_ce82686a6f_13.0.zip";

function fetchRange(url, start, endIncl) {
  return fetch(url, { headers: { Range: "bytes=" + start + "-" + endIncl } }).then(function (r) {
    if (r.status !== 206) throw new Error("不支持 Range (HTTP " + r.status + ")");
    var cr = r.headers.get("content-range");
    var total = null;
    if (cr) { var m = /\/(\d+)\s*$/.exec(cr); if (m) total = parseInt(m[1]); }
    return r.arrayBuffer().then(function (ab) { return { buf: new Uint8Array(ab), total: total }; });
  });
}

function fileSink(f) {
  var fd = fs.openSync(f, "w");
  return {
    setLength: function (len) { fs.ftruncateSync(fd, 0); if (len > 0) fs.writeSync(fd, Buffer.alloc(1), 0, 1, len - 1); },
    writeAt: function (pos, u8, off, len) { fs.writeSync(fd, u8, off, len, pos); },
    close: function () { fs.closeSync(fd); }
  };
}

function p(s) { console.log(s); }

function serve(buf, run) {
  return new Promise(function (resolve) {
    var http = require("http");
    var srv = http.createServer(function (req, res) {
      var m = /^bytes=(\d+)-(\d*)/.exec(req.headers.range || "");
      if (m) {
        var s = parseInt(m[1]), e = m[2] ? Math.min(parseInt(m[2]), buf.length - 1) : buf.length - 1;
        res.writeHead(206, { "Content-Range": "bytes " + s + "-" + e + "/" + buf.length });
        res.end(buf.subarray(s, e + 1));
      } else { res.writeHead(200, { "Content-Length": buf.length }); res.end(buf); }
    });
    srv.listen(0, "127.0.0.1", function () {
      run("http://127.0.0.1:" + srv.address().port + "/ota.zip").then(function (r) {
        srv.close(function () { resolve(r); });
      }, function (err) {
        srv.close(function () { resolve(Promise.reject(err)); });
      });
    });
  });
}

function main() {
  var includeReal = process.argv[2] === "real";
  var chain = Promise.resolve();

  // T1: 合成 payload（新版 schema: block_size=3 / partitions=13 / dst=6, 大端头部）
  chain = chain.then(function () { return serve(
    zipBytes([{ name: "payload.bin", data: buildSyntheticPayload() }], false),
    function (url) {
      p("[T1] 合成 payload.bin (stored, 大端头部+新字段编号)");
      var z = new RemoteZip(url, fetchRange);
      return z.open().then(function () {
        var e = z.entries.find(function (x) { return x.name === "payload.bin"; });
        return payloadParser.parse(function (off, len) { return z.readEntryBytes(e, off, len); }).then(function (mf) {
          var boot = mf.partitions[0];
          if (boot.name !== "boot" || boot.size !== 40960) throw new Error("分区解析异常: " + boot.name + "/" + boot.size);
          var out = path.join(__dirname, "t1_boot.img");
          var sink = fileSink(out);
          return ex.extractPartition(z, e, mf, boot, sink).then(function () {
            sink.close();
            var d = fs.readFileSync(out);
            if (d.length !== 40960) throw new Error("大小不符");
            for (var i = 0; i < 4 * 4096; i++) if (d[i] !== 0x41) throw new Error("块0-3 不是 A");
            for (i = 6 * 4096; i < 8 * 4096; i++) if (d[i] !== 0) throw new Error("块6-7 不是零");
            for (i = 8 * 4096; i < 10 * 4096; i++) if (d[i] !== 0x43) throw new Error("块8-9 不是 C");
            p("  ✓ 合成分区重建正确 (40960 字节, ZERO+raw, 新 schema)");
            fs.unlinkSync(out);
          }, function (err) { sink.close(); throw err; });
        });
      });
    }); });

  // T2: deflate 条目 + CRC
  chain = chain.then(function () { return serve(
    zipBytes([{ name: "boot.img", data: Buffer.from(require("crypto").randomBytes(4 * 1024 * 1024)) }], true),
    function (url) {
      p("[T2] 普通条目提取 (deflate) + CRC32 校验");
      var z = new RemoteZip(url, fetchRange);
      return z.open().then(function () {
        var e = z.entries.find(function (x) { return x.name === "boot.img"; });
        var out = path.join(__dirname, "t2_boot.img");
        var sink = fileSink(out);
        return ex.extractEntry(z, e, sink).then(function () {
          sink.close();
          var d = fs.readFileSync(out);
          if (d.length !== e.usize || crc32(d) !== e.crc) throw new Error("内容或 CRC 不符");
          p("  ✓ 条目解压正确 (4MB 随机数据, CRC32 匹配)");
          fs.unlinkSync(out);
        }, function (err) { sink.close(); throw err; });
      });
    }); });

  // T3: 增量包拒绝
  chain = chain.then(function () { return serve(
    zipBytes([{ name: "payload.bin", data: buildSyntheticPayload(true) }], false),
    function (url) {
      p("[T3] 增量包 (PUFFDIFF) 应明确拒绝");
      var z = new RemoteZip(url, fetchRange);
      return z.open().then(function () {
        var e = z.entries.find(function (x) { return x.name === "payload.bin"; });
        return payloadParser.parse(function (off, len) { return z.readEntryBytes(e, off, len); }).then(function (mf) {
          var out = path.join(__dirname, "t3.img");
          var sink = fileSink(out);
          return Promise.resolve().then(function () {
            return ex.extractPartition(z, e, mf, mf.partitions[0], sink);
          }).then(function () {
            throw new Error("应当拒绝");
          }, function (err) {
            sink.close();
            if (!/增量包/.test(err.message)) throw err;
            p("  ✓ 正确拒绝: " + err.message);
          });
        });
      });
    }); });

  if (includeReal) {
    // T4: 真实一加包
    chain = chain.then(function () {
      p("[T4] 真实一加包 boot.img (deflate 条目)");
      var z = new RemoteZip(ONEPLUS, fetchRange);
      return z.open().then(function () {
        p("  条目数: " + z.entries.length + ", 总大小: " + (z.totalSize / 1048576).toFixed(0) + " MB");
        var e = z.entries.find(function (x) { return x.name === "boot.img"; });
        var out = path.join(__dirname, "t4_boot.img");
        var sink = fileSink(out);
        return ex.extractEntry(z, e, sink).then(function () {
          sink.close();
          var d = fs.readFileSync(out);
          if (d.subarray(0, 8).toString() !== "ANDROID!" || d.length !== 100663296) throw new Error("boot.img 校验失败");
          if (crc32(d) !== e.crc) throw new Error("CRC32 不符");
          p("  ✓ boot.img 提取正确 (100663296 字节, ANDROID!, CRC32 匹配)");
          fs.unlinkSync(out);
        }, function (err) { sink.close(); throw err; });
      });
    });

    // T5: 真实小米包
    chain = chain.then(function () {
      p("[T5] 真实小米包 vbmeta (大端头部+新schema+REPLACE_XZ+SHA-256)");
      var z = new RemoteZip(MIUI, fetchRange);
      return z.open().then(function () {
        var e = z.entries.find(function (x) { return x.name === "payload.bin"; });
        return payloadParser.parse(function (off, len) { return z.readEntryBytes(e, off, len); }).then(function (mf) {
          p("  分区数: " + mf.partitions.length);
          var v = mf.partitions.find(function (x) { return x.name === "vbmeta"; });
          var out = path.join(__dirname, "t5_vbmeta.img");
          var sink = fileSink(out);
          return ex.extractPartition(z, e, mf, v, sink).then(function () {
            sink.close();
            var d = fs.readFileSync(out);
            if (d.subarray(0, 4).toString() !== "AVB0") throw new Error("vbmeta 魔数错误");
            var digest = Buffer.from(sha256.create().update(d).hex(), "hex");
            if (!digest.equals(Buffer.from(v.imageHash))) throw new Error("SHA-256 与 manifest 不符");
            p("  ✓ vbmeta 提取正确 (AVB0 + SHA-256 与 manifest 一致)");
            fs.unlinkSync(out);
          }, function (err) { sink.close(); throw err; });
        });
      });
    });
  }

  chain.then(function () { p("全部测试通过 ✅"); })
    .catch(function (err) {
      p("❌ 测试失败: " + (err && err.message || err));
      console.error(err);
      process.exit(1);
    });
}

main();
