// OTA 在线提取 · 网页版入口（复用 utils 核心，esbuild 打包为 IIFE）
import * as remotezip from "../utils/remotezip.js";
import * as payloadParser from "../utils/payload.js";
import * as ex from "../utils/extractor.js";
import { crc32 } from "../utils/xz.js";
import sha256 from "../vendor/sha256.js";

var $ = function (id) { return document.getElementById(id); };
var state = {
  zip: null, entries: [], mf: null, payloadEntry: null, url: "",
  mode: "none", query: "", imgOnly: false,
  busy: false, cancel: false, rows: [], states: {}, lastNotify: 0
};

var MAX_MEMORY_SINK = 512 * 1024 * 1024; // 超过则必须走系统保存对话框

// ---------- Range 客户端：直连失败自动走代理 ----------
function getProxy() { return (localStorage.getItem("proxy") || "").trim(); }

function fetchDirect(url, start, endIncl) {
  return fetch(url, { headers: { Range: "bytes=" + start + "-" + endIncl } }).then(function (r) {
    if (r.status !== 206 && r.status !== 200) throw new Error("HTTP " + r.status);
    var cr = r.headers.get("content-range");
    var total = null;
    if (cr) { var m = /\/(\d+)\s*$/.exec(cr); if (m) total = parseInt(m[1]); }
    return r.arrayBuffer().then(function (ab) { return { buf: new Uint8Array(ab), total: total }; });
  });
}

function fetchViaProxy(proxy, url, start, endIncl) {
  return fetch(proxy + "?u=" + encodeURIComponent(url), {
    headers: { Range: "bytes=" + start + "-" + endIncl }
  }).then(function (r) {
    if (r.status !== 206) throw new Error("代理返回 HTTP " + r.status);
    var cr = r.headers.get("content-range");
    var total = null;
    if (cr) { var m = /\/(\d+)\s*$/.exec(cr); if (m) total = parseInt(m[1]); }
    return r.arrayBuffer().then(function (ab) { return { buf: new Uint8Array(ab), total: total }; });
  });
}

function getRange(url, start, endIncl) {
  var proxy = getProxy();
  var p = proxy ? fetchViaProxy(proxy, url, start, endIncl) : fetchDirect(url, start, endIncl);
  return p.catch(function (e) {
    if (!proxy && (e instanceof TypeError || /Failed to fetch/.test(e.message || "")))
      throw new Error("直连被跨域(CORS)拦截且未配置代理。请展开页脚「代理设置」，启动或填入代理地址后重试。" +
        "（本地代理: node web/tools/proxy.js 8787）");
    throw e;
  });
}

// ---------- 输出目标 ----------
function MemorySink(size) {
  var parts = [], total = size;
  return {
    setLength: function (len) { total = len; },
    writeAt: function (pos, u8, off, len) {
      parts.push({ pos: pos, u8: u8, off: off, len: len });
    },
    close: function () { return Promise.resolve(); },
    hashAndSave: function (name, kind, expect) {
      parts.sort(function (a, b) { return a.pos - b.pos; });
      var blobs = [], cur = 0, crc = 0xffffffff >>> 0;
      var table = crcTable();
      var hasher = kind === "sha256" ? sha256.create() : null;
      function pushBlob(b) { blobs.push(b); }
      var chain = Promise.resolve();
      parts.forEach(function (pt) {
        var seg = pt.u8.subarray(pt.off, pt.off + pt.len);
        if (pt.pos > cur) { var gap = new Uint8Array(pt.pos - cur); pushBlob(gap); hashChunk(gap); cur = pt.pos; }
        pushBlob(seg); hashChunk(seg); cur = pt.pos + pt.len;
      });
      if (cur < total) { var tail = new Uint8Array(total - cur); pushBlob(tail); hashChunk(tail); }
      function hashChunk(u8) {
        if (kind === "crc32") { for (var i = 0; i < u8.length; i++) crc = table[(crc ^ u8[i]) & 0xff] ^ (crc >>> 8); crc = crc >>> 0; }
        else hasher.update(u8);
      }
      chain = chain.then(function () {
        var blob = new Blob(blobs);
        var ok;
        if (kind === "crc32") ok = (crc ^ 0xffffffff) >>> 0 === expect;
        else ok = hasher.hex() === toHex(expect);
        if (!ok) throw new Error("校验失败");
        var a = document.createElement("a");
        a.href = URL.createObjectURL(blob);
        a.download = name;
        document.body.appendChild(a);
        a.click();
        setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 5000);
      });
      return chain;
    }
  };
  function hashChunk(u8) {
    if (kind === "crc32") { for (var i = 0; i < u8.length; i++) crc = table[(crc ^ u8[i]) & 0xff] ^ (crc >>> 8); crc = crc >>> 0; }
    else hasher.update(u8);
  }
}

function FileSink(writable) {
  return {
    setLength: function () { },
    writeAt: function (pos, u8, off, len) {
      var data = (off === 0 && len === u8.length) ? u8 : u8.slice(off, off + len);
      return writable.write({ type: "write", position: pos, data: data });
    },
    close: function () { return writable.close(); },
    hashAndSave: function (name, kind, expect) {
      // 从已保存的文件读回校验
      var hashPromise = writable.getFile().then(function (file) {
        var reader = file.stream().getReader();
        var crc = 0xffffffff >>> 0, table = crcTable();
        var hasher = kind === "sha256" ? sha256.create() : null;
        function step() {
          return reader.read().then(function (r) {
            if (r.done) {
              if (kind === "crc32") return (crc ^ 0xffffffff) >>> 0 === expect;
              return hasher.hex() === toHex(expect);
            }
            if (kind === "crc32") { for (var i = 0; i < r.value.length; i++) crc = table[(crc ^ r.value[i]) & 0xff] ^ (crc >>> 8); crc = crc >>> 0; }
            else hasher.update(r.value);
            return step();
          });
        }
        return step();
      });
      return hashPromise.then(function (ok) {
        if (!ok) throw new Error("校验失败");
      });
    }
  };
}

function crcTable() {
  if (crcTable.t) return crcTable.t;
  var t = new Uint32Array(256), c, n, k;
  for (n = 0; n < 256; n++) { c = n; for (k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1); t[n] = c >>> 0; }
  crcTable.t = t;
  return t;
}
function toHex(bytes) {
  var s = "";
  for (var i = 0; i < bytes.length; i++) s += ("0" + bytes[i].toString(16)).slice(-2);
  return s;
}

// ---------- UI ----------
function mb(v) {
  if (v >= 1073741824) return (v / 1073741824).toFixed(2) + " GB";
  if (v >= 1048576) return (v / 1048576).toFixed(1) + " MB";
  if (v >= 1024) return (v / 1024).toFixed(1) + " KB";
  return v + " B";
}
function fmtRow(key, name, icon, iconClass, meta, size, st) {
  var r = { key: key, name: name, icon: icon, iconClass: iconClass, size: size, meta: meta, metaClass: "", pct: 0, active: false, btnText: "提取", btnDisabled: state.busy };
  if (st) {
    if (st.active) {
      r.active = true;
      var sp = st.speed > 0.05 ? " · " + st.speed.toFixed(1) + " MB/s" : "";
      r.meta = mb(st.cur) + " / " + mb(st.total) + sp + (st.msg ? " · " + st.msg : "");
      r.metaClass = "meta-accent";
      r.pct = st.pct >= 0 ? st.pct : 0;
      r.btnText = st.pct >= 0 ? st.pct + "%" : "…";
      r.btnDisabled = true;
    } else if (st.done) {
      r.meta = "✓ 已校验通过";
      r.metaClass = "meta-ok";
      r.btnText = "再次保存";
    } else if (st.failed) {
      r.meta = "✗ " + st.msg;
      r.metaClass = "meta-fail";
      r.btnText = "重试";
      r.btnDisabled = state.busy;
    }
  }
  return r;
}
function refreshRows() {
  var q = state.query.toLowerCase(), imgOnly = state.imgOnly, rows = [], i;
  if (state.mode === "entries") {
    for (i = 0; i < state.entries.length; i++) {
      var e = state.entries[i], n = e.name, base = n.split("/").pop(), icon, iconClass, extra = "";
      if (/payload\.bin$/.test(n)) { icon = "PAY"; iconClass = "icon-pay"; extra = " · payload 包，点击解析分区"; }
      else if (/\.img$/i.test(n)) { icon = "IMG"; iconClass = "icon-img"; }
      else if (/\.(dat\.br|patch\.dat|transfer\.list)$/i.test(n)) { icon = "DAT"; iconClass = "icon-dat"; extra = " · 动态分区"; }
      else { icon = "ZIP"; iconClass = "icon-dat"; }
      var dir = n.indexOf("/") >= 0 ? " · " + n.slice(0, n.lastIndexOf("/")) : "";
      var keep = /payload\.bin$/.test(n) ||
        ((q === "" || base.toLowerCase().indexOf(q) >= 0 || n.toLowerCase().indexOf(q) >= 0) && (!imgOnly || /\.img$/i.test(n)));
      if (keep) rows.push(fmtRow("entry:" + n, base, icon, iconClass, mb(e.usize) + dir + extra, e.usize, state.states["entry:" + n]));
    }
    rows.sort(function (a) { return a.key.indexOf("payload") === 0 ? -1 : 1; });
  } else if (state.mode === "partitions") {
    (state.mf ? state.mf.partitions : []).forEach(function (pt) {
      if (q !== "" && pt.name.toLowerCase().indexOf(q) < 0) return;
      rows.push(fmtRow("part:" + pt.name, pt.name, "IMG", "icon-img", mb(pt.size), pt.size, state.states["part:" + pt.name]));
    });
  }
  state.rows = rows;
  renderList();
}
function renderList() {
  var html = "";
  state.rows.forEach(function (r, i) {
    html += '<div class="item" data-i="' + i + '">' +
      '<div class="icon ' + r.iconClass + '">' + r.icon + "</div>" +
      '<div class="item-mid"><div class="item-name">' + esc(r.name) + "</div>" +
      '<div class="item-meta ' + r.metaClass + '">' + esc(r.meta) + "</div>" +
      (r.active ? '<div class="bar"><div style="width:' + r.pct + '%"></div></div>' : "") +
      "</div>" +
      '<button class="pill" data-i="' + i + '"' + (r.btnDisabled ? " disabled" : "") + ">" + esc(r.btnText) + "</button></div>";
  });
  if (!state.rows.length) html = '<div class="empty">无匹配项\n试试清除搜索或取消「仅 .img」</div>';
  $("list").innerHTML = html;
}
function esc(s) { return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;"); }
function status(s) { $("statusText").textContent = s; }
function throttleRefresh() {
  var now = Date.now();
  if (now - state.lastNotify > 250) { state.lastNotify = now; renderList(); }
}

function findEntry(name) { return state.entries.filter(function (e) { return e.name === name; })[0]; }
function findPart(name) { return state.mf.partitions.filter(function (p) { return p.name === name; })[0]; }

// ---------- 提取 ----------
function openSink(name, size, kind, expect) {
  // 优先系统保存对话框（Chrome/Edge，流式写盘适合大文件）；其余走内存
  var useFS = !!window.showSaveFilePicker && (size > MAX_MEMORY_SINK || window.__forcePick);
  if (useFS) {
    return window.showSaveFilePicker({ suggestedName: name }).then(function (handle) {
      return handle.createWritable().then(function (w) {
        var sink = FileSink(w);
        return {
          sink: sink,
          finalize: function () { return sink.close().then(function () { return sink.hashAndSave(name, kind, expect); }); },
          discard: function () { return w.abort().catch(function () { }); }
        };
      });
    }).catch(function (e) {
      if (e && e.name === "AbortError") throw new Error("__cancelled__");
      return memoryTarget(name, size, kind, expect);
    });
  }
  return Promise.resolve(memoryTarget(name, size, kind, expect));
}
function memoryTarget(name, size, kind, expect) {
  if (size > 1024 * 1024 * 1024) throw new Error("该文件超过 1GB，内存保存不可行，请使用 Chrome/Edge 的系统保存对话框");
  var sink = MemorySink(size);
  return {
    sink: sink,
    finalize: function () { return sink.close().then(function () { return sink.hashAndSave(name, kind, expect); }); },
    discard: function () { }
  };
}

function runItem(key) {
  var st = state.states[key] || (state.states[key] = { active: false, done: false, failed: false, msg: "", pct: 0, cur: 0, total: 0, speed: 0, lastTm: 0, lastCur: 0 });
  st.active = true; st.done = false; st.failed = false; st.pct = 0; st.cur = 0; st.total = 0; st.speed = 0; st.lastTm = 0; st.lastCur = 0; st.msg = "";
  refreshRows();

  var name = key.indexOf("entry:") === 0 ? key.slice(6).split("/").pop() : key.slice(5) + ".img";
  var kind = key.indexOf("part:") === 0 ? "sha256" : "crc32";
  var expect = key.indexOf("part:") === 0 ? (findPart(key.slice(5)) || {}).imageHash : (findEntry(key.slice(6)) || {}).crc;

  return Promise.resolve().then(function () {
    var size = key.indexOf("part:") === 0 ? findPart(key.slice(5)).size : findEntry(key.slice(6)).usize;
    return openSink(name, size, kind, expect);
  }).then(function (target) {
    var work = key.indexOf("entry:") === 0
      ? ex.extractEntry(state.zip, findEntry(key.slice(6)), target.sink, function (ph, c, t) { report(key, c, t); }, function () { return state.cancel; })
      : ex.extractPartition(state.zip, state.payloadEntry, state.mf, findPart(key.slice(5)), target.sink, function (ph, c, t) { report(key, c, t); }, function () { return state.cancel; });
    return work.then(function () {
      st.msg = "校验中";
      throttleRefresh();
      return target.finalize().then(function () {
        st.active = false; st.done = true; st.pct = 100; st.msg = "";
        refreshRows();
        return true;
      }, function (err) {
        target.discard();
        if (err && err.message === "校验失败" && attempt2(key)) return null; // 重试
        throw err;
      });
    }).catch(function (err) {
      if (err && err.message === "__cancelled__") {
        st.active = false; st.failed = true; st.msg = "已取消"; refreshRows();
        return null;
      }
      target.discard();
      st.active = false; st.failed = true; st.msg = err && err.message || "失败";
      refreshRows();
      return null;
    });
  });

  function attempt2(k) {
    if (st.retried) return false;
    st.retried = true;
    st.msg = "校验失败，重新提取中";
    refreshRows();
    var p2 = Promise.resolve().then(function () { return runItem(k); }).then(function () {
      st.retried = false;
      return true; // 已处理完成态
    });
    // 重试在后台完成，本次直接结束（不递归阻塞调用链）
    p2.catch(function () { });
    return true;
  }
}

function report(key, cur, total) {
  var st = state.states[key];
  if (!st) return;
  st.cur = cur; st.total = total;
  st.pct = total > 0 ? Math.min(100, Math.floor(cur * 100 / total)) : 0;
  var now = Date.now();
  if (st.lastTm && now - st.lastTm > 400) {
    var dt = (now - st.lastTm) / 1000;
    if (dt > 0) st.speed = (cur - st.lastCur) / dt / 1048576;
    st.lastCur = cur; st.lastTm = now;
  } else if (!st.lastTm) { st.lastCur = cur; st.lastTm = now; }
  throttleRefresh();
}

// ---------- 事件 ----------
function busyGuard() {
  if (state.busy) { alert("有任务进行中"); return true; }
  return false;
}

$("parseBtn").onclick = function () {
  if (busyGuard()) return;
  var url = $("url").value.trim();
  if (url.indexOf("http://") !== 0 && url.indexOf("https://") !== 0) { status("请输入有效的 http/https 链接"); return; }
  state.busy = true;
  $("parseBtn").disabled = true; $("parseBtn").textContent = "解析中…";
  status("正在解析远程 ZIP 中央目录…");
  var z = new remotezip.RemoteZip(url, getRange);
  z.open().then(function () {
    state.zip = z; state.entries = z.entries; state.url = url;
    $("infoCard").hidden = false; $("listHead").hidden = false; $("searchRow").hidden = false;
    $("infoSize").textContent = mb(z.totalSize);
    $("infoEntries").textContent = String(z.entries.length);
    $("infoName").textContent = url.split("?")[0].split("/").pop();
    $("infoDevice").textContent = "—";
    state.mode = "entries";
    refreshRows();
    status("共 " + z.entries.length + " 个条目");
  }).catch(function (e) {
    status("解析失败：" + e.message);
  }).then(function () {
    state.busy = false;
    $("parseBtn").disabled = false; $("parseBtn").textContent = "解析";
    // busy 释放后再自动解析 payload（否则被守卫拦掉）
    var hasPayload = state.entries.some(function (e) { return /payload\.bin$/.test(e.name); });
    if (hasPayload) parsePayload();
  });
};

function parsePayload() {
  if (state.busy) return;
  state.busy = true;
  status("正在解析 payload manifest…");
  var pe = state.entries.filter(function (e) { return /payload\.bin$/.test(e.name); })[0];
  payloadParser.parse(function (off, len) { return state.zip.readEntryBytes(pe, off, len); })
    .then(function (mf) {
      state.mf = mf; state.payloadEntry = pe;
      $("backBtn").hidden = false;
      state.mode = "partitions";
      refreshRows();
      status("manifest 解析成功：" + mf.partitions.length + " 个分区");
    })
    .catch(function (e) { status("payload 解析失败：" + e.message); })
    .then(function () { state.busy = false; });
}

$("backBtn").onclick = function () { $("backBtn").hidden = true; state.mode = "entries"; refreshRows(); };
$("search").oninput = function (e) { state.query = e.target.value; refreshRows(); };
$("imgOnly").onchange = function (e) { state.imgOnly = e.target.checked; refreshRows(); };

$("list").addEventListener("click", function (e) {
  var btn = e.target.closest(".pill");
  var item = e.target.closest(".item");
  if (!item) return;
  var row = state.rows[parseInt(item.dataset.i, 10)];
  if (!row) return;
  if (btn) {
    var st = state.states[row.key];
    if (st && st.done) { // 再次保存：重跑（浏览器下载无法重复触发旧 Blob）
      if (busyGuard()) return;
      runItem(row.key);
      return;
    }
  }
  if (busyGuard()) return;
  if (/payload\.bin$/.test(row.key)) { parsePayload(); return; }
  state.cancel = false;
  runItem(row.key).then(function (ok) {
    if (ok === true) status("已保存到浏览器下载目录");
  });
});

$("extractAll").onclick = function () {
  if (busyGuard()) return;
  var keys = [];
  if (state.mode === "partitions") state.mf.partitions.forEach(function (p) { keys.push("part:" + p.name); });
  else state.entries.forEach(function (e) { if (/\.img$/i.test(e.name)) keys.push("entry:" + e.name); });
  if (!keys.length) { alert("没有可提取的项"); return; }
  if (!confirm("将按顺序提取 " + keys.length + " 项，浏览器逐个弹出下载。继续？")) return;
  state.busy = true; state.cancel = false;
  $("parseBtn").disabled = true;
  var i = 0, ok = 0, fail = 0;
  function next() {
    if (i >= keys.length || state.cancel) {
      state.busy = false;
      $("parseBtn").disabled = false;
      status("完成：成功 " + ok + " 项" + (fail ? "，失败 " + fail + " 项" : ""));
      return;
    }
    runItem(keys[i++]).then(function (r) { if (r === true) ok++; else fail++; next(); });
  }
  next();
};

$("proxyUrl").value = getProxy();
$("proxyUrl").oninput = function (e) { localStorage.setItem("proxy", e.target.value.trim()); };

// 保存位置策略提示
if (!window.showSaveFilePicker) status("提示：当前浏览器不支持流式保存，超过 1GB 的镜像无法下载，建议 Chrome/Edge");
