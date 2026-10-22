// 各品牌常见 OTA 链接批量验证（只做解析级验证，不下载整包）
// 用法: node test/verify-ota.js
var fs = require("fs");
var { RemoteZip } = require("../utils/remotezip.js");
var payloadParser = require("../utils/payload.js");

var LINKS = [
  ["小米 MIUI14 recovery (aliyunSG)", "https://bkt-sgp-miui-ota-update-alisgp.oss-ap-southeast-1.aliyuncs.com/V14.0.11.0.TLFCNXM/miui_DITING_V14.0.11.0.TLFCNXM_ce82686a6f_13.0.zip"],
  ["小米 HyperOS3 recovery K50至尊 (bigota)", "https://bigota.d.miui.com/OS3.0.3.0.VLFCNXM/diting-ota_full-OS3.0.3.0.VLFCNXM-user-15.0-04006cfe4c.zip"],
  ["一加 ColorOS (gauss allawnfs)", "https://gauss-opexcostmanual-cn.allawnfs.com/remove-d3ceef81c8c68c8e3cc6f730973a7405/component-ota/24/02/19/3cd19846753d45e2a3e6ad534620f81b.zip"],
  ["OPPO ColorOS 动态链接(用户反馈,应已失效)", "https://component-ota-cn.allawntech.com/downloadCheck?c=8e3af519d9b021799ec4a35590a19ff7&p=a6c78fdd375d888b39ce89f94b9764359477f5b41b3554f282bb03bd9f7c5897efe9628ae42d3575b96dd3282179&d=e181cd82745f88956e8098ec0182623bca30a2a51b7b03ead6e04ce9ca7400d5b4e921c5b4276d&g=10eab6008d5642cf42abd2aa41f847cb&id=6a9a10dd92ce7c01836a5698&taste=1&supportDLTaste=0&mode=1&tr=manual&s=bc1b79eb7c886160b8dad34742bc6469"],
];

function findZipUrl(o) {
  if (typeof o === "string") return (/\.zip$/i.test(o) || /\.zip\?/.test(o)) ? o : null;
  if (o && typeof o === "object") { for (var k in o) { var r = findZipUrl(o[k]); if (r) return r; } }
  return null;
}

function fetchRange(url, start, endIncl) {
  return fetch(url, { headers: { Range: "bytes=" + start + "-" + endIncl } }).then(function (r) {
    if (r.status !== 206 && r.status !== 200) throw new Error("不支持 Range (HTTP " + r.status + ")");
    if (r.status === 200 && start > 0) throw new Error("忽略 Range (返回 200)");
    var cr = r.headers.get("content-range");
    var total = null;
    if (cr) { var m = /\/(\d+)\s*$/.exec(cr); if (m) total = parseInt(m[1]); }
    return r.arrayBuffer().then(function (ab) { return { buf: new Uint8Array(ab), total: total }; });
  });
}

async function resolveDyn(url) {
  if (!/allawntech|allawnfs|allawnos|coloros/i.test(url) || !/\/(downloadCheck|download)\?/.test(url)) return url;
  var t = await (await fetch(url)).text();
  try {
    var j = JSON.parse(t);
    if ((j.errMsg === "0" || j.responseCode === 0) && j.body) {
      var real = findZipUrl(j.body);
      if (real) return real;
    }
    throw new Error("动态链接无效(服务端返回 " + (j.errMsg != null ? j.errMsg : j.responseCode) + ") — 10~30 分钟过期且绑定设备");
  } catch (e) { if (e instanceof SyntaxError) return url; throw e; }
}

async function verify(name, rawUrl) {
  var out = { name: name, ok: false };
  try {
    var url = await resolveDyn(rawUrl);
    out.dynamic = url !== rawUrl;
    var z = new RemoteZip(url, fetchRange);
    await z.open();
    out.size = z.totalSize;
    out.entries = z.entries.length;
    var names = z.entries.map(function (e) { return e.name; });
    out.hasPayload = names.some(function (n) { return /payload\.bin$/.test(n); });
    if (!out.hasPayload) return out;
    var pe = z.entries.filter(function (e) { return /payload\.bin$/.test(e.name); })[0];
    var mf = await payloadParser.parse(function (off, len) { return z.readEntryBytes(pe, off, len); });
    out.ok = true;
    out.mfVersion = mf.manifestVersion != null ? mf.manifestVersion : "?";
    out.blockSize = mf.blockSize;
    out.partitions = mf.partitions.length;
    var typeCount = {};
    var total = 0;
    mf.partitions.forEach(function (p) {
      p.ops.forEach(function (o) { typeCount[o.type] = (typeCount[o.type] || 0) + 1; total++; });
    });
    out.opTypes = Object.keys(typeCount).map(function (k) { return k + ":" + typeCount[k]; }).join(" ").substring(0, 120);
    out.opsTotal = total;
    var hasZstd = mf.partitions.some(function (p) { return p.ops.some(function (o) { return o.type === 14; }); });
    out.zstd = hasZstd;
    var hasDelta = mf.partitions.some(function (p) { return p.ops.some(function (o) { return [2,3,4,5,9].indexOf(o.type) >= 0; }); });
    out.delta = hasDelta;
    out.firstN = names.slice(0, 6).join(", ");
  } catch (e) {
    out.error = e.message;
  }
  return out;
}

(async function main() {
  var results = [];
  for (var i = 0; i < LINKS.length; i++) {
    var r = await verify(LINKS[i][0], LINKS[i][1]);
    results.push(r);
    console.log("==== " + r.name);
    if (r.ok) {
      console.log("  zip: " + (r.size / 1048576).toFixed(1) + " MB, 条目 " + r.entries + ", payload.bin: 有");
      console.log("  manifest: block=" + r.blockSize + ", 分区 " + r.partitions + ", 操作 " + r.opsTotal + " 个");
      console.log("  op 类型: " + r.opTypes);
      console.log("  ZSTD(14): " + (r.zstd ? "有 ✓" : "无") + " | 增量: " + (r.delta ? "是" : "否"));
    } else {
      console.log("  ✗ " + (r.error || (r.hasPayload ? "" : "zip 内无 payload.bin")));
    }
  }
})();
