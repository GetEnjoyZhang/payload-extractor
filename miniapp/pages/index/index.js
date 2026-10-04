var { RemoteZip } = require("../../utils/remotezip.js");
var payloadParser = require("../../utils/payload.js");
var ex = require("../../utils/extractor.js");
var { crc32 } = require("../../utils/xz.js");
var sha256 = require("../../vendor/sha256.js");

// wx.request 版 Range 客户端（与 Node 测试同一接口）
function wxRange(url, start, endIncl) {
  return new Promise(function (resolve, reject) {
    wx.request({
      url: url,
      header: { Range: "bytes=" + start + "-" + endIncl },
      responseType: "arraybuffer",
      success: function (res) {
        if (res.statusCode !== 206) {
          reject(new Error("该服务器不支持断点续传 (HTTP " + res.statusCode + ")，无法在线提取"));
          return;
        }
        var cr = null;
        for (var k in res.header) if (k.toLowerCase() === "content-range") cr = res.header[k];
        var total = null;
        if (cr) { var m = /\/(\d+)\s*$/.exec(cr); if (m) total = parseInt(m[1]); }
        resolve({ buf: new Uint8Array(res.data), total: total });
      },
      fail: function (e) { reject(new Error(e.errMsg || "网络请求失败")); }
    });
  });
}

function mb(v) {
  if (v >= 1073741824) return (v / 1073741824).toFixed(2) + " GB";
  if (v >= 1048576) return (v / 1048576).toFixed(1) + " MB";
  if (v >= 1024) return (v / 1024).toFixed(1) + " KB";
  return v + " B";
}

// 小程序文件 sink（可定位写入）
function fsSink(path) {
  var fsm = wx.getFileSystemManager();
  var fd = fsm.openSync(path, "w");
  return {
    setLength: function (len) {
      try { fsm.truncateSync(path, len); } catch (e) { /* 尽力扩展 */ }
    },
    writeAt: function (pos, u8, off, len) {
      fsm.writeSync(fd, u8.buffer.slice(u8.byteOffset + off, u8.byteOffset + off + len), pos);
    },
    close: function () { fsm.closeSync(fd); }
  };
}

Page({
  data: {
    url: "",
    busy: false,
    parseText: "解析",
    infoShow: false,
    infoSize: "", infoEntries: "", infoDevice: "", infoName: "",
    mode: "none", // none | entries | partitions
    list: [],
    query: "",
    imgOnly: false,
    statusText: "提取的文件保存在小程序沙箱，完成后可分享到聊天导出",
    lastNotify: 0
  },

  // 内存态（不进 data）
  zip: null, entries: [], mf: null, payloadEntry: null,
  states: {}, rows: [], cancelFlag: false,

  onLoad: function () {
    try {
      var t = wx.getClipboardData ? null : null;
    } catch (e) { }
  },

  onUrl: function (e) { this.setData({ url: e.detail.value }); },

  fmtRow: function (key, name, icon, iconClass, meta, size, st) {
    var r = {
      key: key, name: name, icon: icon, iconClass: iconClass, sizeBytes: size,
      meta: meta, metaClass: "", pct: 0, active: false, btnText: "提取", btnDisabled: !!this.busy
    };
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
        r.btnText = "分享";
      } else if (st.failed) {
        r.meta = "✗ " + st.msg;
        r.metaClass = "meta-fail";
        r.btnText = "重试";
        r.btnDisabled = !!this.busy;
      }
    }
    return r;
  },

  refreshRows: function () {
    var q = (this.data.query || "").toLowerCase();
    var imgOnly = this.data.imgOnly;
    var rows = [];
    var i;
    if (this.data.mode === "entries") {
      for (i = 0; i < this.entries.length; i++) {
        var e = this.entries[i];
        var n = e.name, base = n.split("/").pop(), icon, iconClass, extra = "";
        if (/payload\.bin$/.test(n)) { icon = "PAY"; iconClass = "icon-pay"; extra = " · payload 包，点击解析分区"; }
        else if (/\.img$/i.test(n)) { icon = "IMG"; iconClass = "icon-img"; }
        else if (/\.(dat\.br|patch\.dat|transfer\.list)$/i.test(n)) { icon = "DAT"; iconClass = "icon-dat"; extra = " · 动态分区"; }
        else { icon = "ZIP"; iconClass = "icon-dat"; }
        var dir = n.indexOf("/") >= 0 ? " · " + n.slice(0, n.lastIndexOf("/")) : "";
        var keep = /payload\.bin$/.test(n) ||
          ((q === "" || base.toLowerCase().indexOf(q) >= 0 || n.toLowerCase().indexOf(q) >= 0) &&
            (!imgOnly || /\.img$/i.test(n)));
        if (keep) rows.push(this.fmtRow("entry:" + n, base, icon, iconClass, mb(e.usize) + dir + extra, e.usize, this.states["entry:" + n]));
      }
      rows.sort(function (a, b) { return (a.key.indexOf("payload") === 0 ? -1 : 1); });
    } else if (this.data.mode === "partitions") {
      var parts = this.mf ? this.mf.partitions : [];
      for (i = 0; i < parts.length; i++) {
        var pt = parts[i];
        if (q !== "" && pt.name.toLowerCase().indexOf(q) < 0) continue;
        rows.push(this.fmtRow("part:" + pt.name, pt.name, "IMG", "icon-img", mb(pt.size), pt.size, this.states["part:" + pt.name]));
      }
    }
    this.rows = rows;
    this.setData({ list: rows });
  },

  onSearch: function (e) { this.setData({ query: e.detail.value }); this.refreshRows(); },
  toggleImgOnly: function (e) { this.setData({ imgOnly: e.detail.value }); this.refreshRows(); },
  goBack: function () { this.setData({ mode: "entries" }); this.refreshRows(); },

  startParse: function () {
    if (this.data.busy) return;
    var url = (this.data.url || "").trim();
    if (url.indexOf("http://") !== 0 && url.indexOf("https://") !== 0) {
      this.setData({ statusText: "请输入有效的 http/https 链接" });
      return;
    }
    var self = this;
    self.busy = true;
    self.setData({ busy: true, parseText: "解析中…", statusText: "正在解析远程 ZIP 中央目录…" });
    var z = new RemoteZip(url, wxRange);
    z.open().then(function () {
      self.zip = z; self.entries = z.entries; self.url = url;
      var device = "";
      var meta = null;
      for (var i = 0; i < self.entries.length; i++)
        if (self.entries[i].name === "META-INF/com/android/metadata") meta = self.entries[i];
      var loadMeta = meta && meta.usize <= 65536
        ? z.readEntryBytes(meta, 0, meta.usize).then(function (b) {
            var txt = decodeURIComponent(escape(String.fromCharCode.apply(null, b)));
            var m = /pre-device=([^\r\n]+)/.exec(txt);
            if (m) device = m[1];
          }).catch(function () {})
        : Promise.resolve();
      return loadMeta.then(function () {
        self.setData({
          infoShow: true,
          infoSize: mb(z.totalSize),
          infoEntries: String(self.entries.length),
          infoDevice: device || "—",
          infoName: url.split("?")[0].split("/").pop(),
          mode: "entries",
          statusText: "共 " + self.entries.length + " 个条目"
        });
        self.refreshRows();
      });
    }).catch(function (e) {
      self.setData({ statusText: "解析失败：" + e.message });
    }).then(function () {
      self.busy = false;
      self.setData({ busy: false, parseText: "解析" });
      // busy 释放后再自动解析 payload（否则被守卫拦掉）
      var hasPayload = false;
      for (var j = 0; j < self.entries.length; j++)
        if (/payload\.bin$/.test(self.entries[j].name)) hasPayload = true;
      if (hasPayload) self.parsePayload();
    });
  },

  parsePayload: function () {
    var self = this;
    if (self.busy) return;
    self.busy = true;
    self.setData({ statusText: "正在解析 payload manifest…" });
    var pe = null;
    for (var i = 0; i < self.entries.length; i++)
      if (/payload\.bin$/.test(self.entries[i].name)) pe = self.entries[i];
    payloadParser.parse(function (off, len) { return self.zip.readEntryBytes(pe, off, len); })
      .then(function (mf) {
        self.mf = mf; self.payloadEntry = pe;
        self.setData({ mode: "partitions", statusText: "manifest 解析成功：" + mf.partitions.length + " 个分区" });
        self.refreshRows();
      })
      .catch(function (e) { self.setData({ statusText: "payload 解析失败：" + e.message }); })
      .then(function () { self.busy = false; });
  },

  onItem: function (e) {
    var key = e.currentTarget.dataset.key;
    if (busyGuard(this)) return;
    if (/payload\.bin$/.test(key)) { this.parsePayload(); return; }
    this.launch(key);
  },
  onItemBtn: function (e) {
    var key = e.currentTarget.dataset.key;
    var st = this.states[key];
    if (st && st.done) { this.shareFile(key); return; }
    this.onItem({ currentTarget: { dataset: { key: key } } });
  },

  report: function (key, cur, total) {
    var st = this.states[key];
    if (!st) return;
    st.cur = cur; st.total = total;
    st.pct = total > 0 ? Math.min(100, Math.floor(cur * 100 / total)) : 0;
    var now = Date.now();
    if (st.lastTm && now - st.lastTm > 400) {
      var dt = (now - st.lastTm) / 1000;
      if (dt > 0) st.speed = (cur - st.lastCur) / dt / 1048576;
      st.lastCur = cur; st.lastTm = now;
    } else if (!st.lastTm) { st.lastCur = cur; st.lastTm = now; }
    if (now - this.data.lastNotify > 300) {
      this.data.lastNotify = now;
      this.refreshRows();
    }
  },

  outPath: function (name) {
    return wx.env.USER_DATA_PATH + "/" + name;
  },

  runItem: function (key) {
    var self = this;
    var st = self.states[key] || (self.states[key] = { active: false, done: false, failed: false, msg: "", pct: 0, cur: 0, total: 0, speed: 0, lastTm: 0, lastCur: 0 });
    st.active = true; st.done = false; st.failed = false; st.pct = 0; st.cur = 0; st.total = 0;
    st.speed = 0; st.lastTm = 0; st.lastCur = 0; st.msg = "";
    self.refreshRows();
    var name = key.indexOf("entry:") === 0 ? key.slice(6).split("/").pop() : key.slice(5) + ".img";
    var path = self.outPath(name);
    var fsm = wx.getFileSystemManager();

    function verify() {
      // SHA-256（payload 分区）或 CRC32（zip 条目）
      return Promise.resolve().then(function () {
        var stat = fsm.statSync(path);
        var size = stat.size();
        if (key.indexOf("part:") === 0) {
          var pt = null;
          for (var i = 0; i < self.mf.partitions.length; i++)
            if (self.mf.partitions[i].name === key.slice(5)) pt = self.mf.partitions[i];
          if (!pt || !pt.imageHash) return true;
          var hasher = sha256.create();
          var pos = 0, CHUNK = 1024 * 1024;
          function step() {
            if (pos >= size) return hasher.hex() === hex(pt.imageHash);
            var n = Math.min(CHUNK, size - pos);
            var ab = fsm.readFileSync(path, undefined, pos, n);
            hasher.update(ab);
            pos += n;
            return step();
          }
          return step();
        } else {
          var e = null;
          for (var j = 0; j < self.entries.length; j++)
            if (self.entries[j].name === key.slice(6)) e = self.entries[j];
          if (!e || e.usize === 0) return true;
          var crc = 0xffffffff >>> 0;
          var pos2 = 0;
          function step2() {
            if (pos2 >= size) return ((crc ^ 0xffffffff) >>> 0) === e.crc;
            var n = Math.min(1024 * 1024, size - pos2);
            var u8 = new Uint8Array(fsm.readFileSync(path, undefined, pos2, n));
            var table = getCRCTable();
            for (var k = 0; k < u8.length; k++) crc = table[(crc ^ u8[k]) & 0xff] ^ (crc >>> 8);
            crc = crc >>> 0;
            pos2 += n;
            return step2();
          }
          return step2();
        }
      }).catch(function () { return null; });
    }

    function attempt(attemptNo) {
      if (attemptNo === 2) { st.msg = "校验失败，重新提取中"; self.refreshRows(); }
      var sink = fsSink(path);
      var work;
      if (key.indexOf("entry:") === 0) {
        var e = null;
        for (var i = 0; i < self.entries.length; i++)
          if (self.entries[i].name === key.slice(6)) e = self.entries[i];
        work = ex.extractEntry(self.zip, e, sink, function (ph, c, t) { self.report(key, c, t); },
          function () { return self.cancelFlag; });
      } else {
        var pt = null;
        for (var j = 0; j < self.mf.partitions.length; j++)
          if (self.mf.partitions[j].name === key.slice(5)) pt = self.mf.partitions[j];
        work = ex.extractPartition(self.zip, self.payloadEntry, self.mf, pt, sink,
          function (ph, c, t) { self.report(key, c, t); },
          function () { return self.cancelFlag; });
      }
      return work.then(function () {
        sink.close();
        st.msg = "校验中";
        self.refreshRows();
        return verify().then(function (ok) {
          if (ok !== false) {
            st.active = false; st.done = true; st.pct = 100; st.msg = "";
            self.refreshRows();
            return { path: path, name: name };
          }
          // 校验失败：删除重试
          try { fsm.unlinkSync(path); } catch (e2) { }
          if (attemptNo >= 2) {
            st.active = false; st.failed = true; st.msg = "校验失败（已自动重试）";
            self.refreshRows();
            return null;
          }
          return attempt(attemptNo + 1);
        });
      }).catch(function (err) {
        try { sink.close(); } catch (e3) { }
        try { fsm.unlinkSync(path); } catch (e4) { }
        st.active = false;
        st.failed = true;
        st.msg = err && err.message || "失败";
        self.refreshRows();
        return null;
      });
    }
    return attempt(1);
  },

  launch: function (key) {
    var self = this;
    if (self.busy) { wx.showToast({ title: "有任务进行中", icon: "none" }); return; }
    self.busy = true;
    self.cancelFlag = false;
    self.setData({ busy: true });
    self.runItem(key).then(function (res) {
      self.busy = false;
      self.setData({ busy: false });
      if (res) {
        self.doneRes = res;
        self.setData({ statusText: "已保存：" + res.name + "（点行内「分享」导出）" });
        wx.showModal({
          title: "提取完成",
          content: res.name + " 已校验通过，是否分享到聊天导出？",
          confirmText: "分享",
          cancelText: "关闭",
          success: function (r) { if (r.confirm) self.shareFile(key); }
        });
      }
    });
  },

  shareFile: function (key) {
    var name = key.indexOf("entry:") === 0 ? key.slice(6).split("/").pop() : key.slice(5) + ".img";
    wx.shareFileMessage({
      filePath: this.outPath(name),
      fileName: name,
      success: function () { },
      fail: function (e) {
        if (e && e.errMsg && e.errMsg.indexOf("cancel") < 0)
          wx.showToast({ title: "分享失败：" + e.errMsg, icon: "none" });
      }
    });
  },

  extractAll: function () {
    var self = this;
    if (self.busy) return;
    var keys = [];
    if (self.data.mode === "partitions") {
      self.mf.partitions.forEach(function (p) { keys.push("part:" + p.name); });
    } else {
      self.entries.forEach(function (e) { if (/\.img$/i.test(e.name)) keys.push("entry:" + e.name); });
    }
    if (!keys.length) { wx.showToast({ title: "没有可提取的项", icon: "none" }); return; }
    wx.showModal({
      title: "全部提取",
      content: "将按顺序提取 " + keys.length + " 项，小程序解压较慢，建议只提取需要的分区。继续？",
      success: function (r) {
        if (!r.confirm) return;
        self.busy = true;
        self.cancelFlag = false;
        self.setData({ busy: true });
        var i = 0, ok = 0, fail = 0;
        function next() {
          if (i >= keys.length || self.cancelFlag) {
            self.busy = false;
            self.setData({ busy: false });
            wx.showToast({ title: "完成：成功 " + ok + " 项" + (fail ? "，失败 " + fail + " 项" : ""), icon: "none" });
            return;
          }
          self.runItem(keys[i++]).then(function (res) { if (res) ok++; else fail++; next(); });
        }
        next();
      }
    });
  }
});

function busyGuard(page) {
  if (page.busy) { wx.showToast({ title: "有任务进行中", icon: "none" }); return true; }
  return false;
}

var _crcTable = null;
function getCRCTable() {
  if (_crcTable) return _crcTable;
  _crcTable = new Uint32Array(256);
  for (var n = 0; n < 256; n++) {
    var c = n;
    for (var k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    _crcTable[n] = c >>> 0;
  }
  return _crcTable;
}

function hex(bytes) {
  var s = "";
  for (var i = 0; i < bytes.length; i++) s += ("0" + bytes[i].toString(16)).slice(-2);
  return s;
}
