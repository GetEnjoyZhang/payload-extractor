// 极简 CORS 代理：只做 Range 转发，让网页版能抓取无 CORS 头的 OTA 服务器
// 用法: node proxy.js [端口] [允许的上游域名列表(逗号分隔,缺省不限制)]
// 安全提示: 公网部署时务必设置允许列表，防止被当作开放代理滥用
var http = require("http");
var https = require("https");

var port = parseInt(process.argv[2] || "8787");
var allowArg = process.argv[3];
var allowHosts = allowArg ? allowArg.split(",").map(function (s) { return s.trim(); }) : null;

function upstream(urlStr) {
  var u = new URL(urlStr);
  if (u.protocol !== "https:" && u.protocol !== "http:") throw new Error("bad protocol");
  if (allowHosts && allowHosts.indexOf(u.hostname) < 0) throw new Error("host not allowed: " + u.hostname);
  return u;
}

http.createServer(function (req, res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Headers", "*");
  res.setHeader("Access-Control-Expose-Headers", "Content-Range, Content-Length, Content-Type");
  if (req.method === "OPTIONS") { res.writeHead(204); res.end(); return; }
  var qs = new URL(req.url, "http://x").searchParams;
  var target = qs.get("u");
  if (!target) { res.writeHead(400); res.end("missing ?u=<encoded url>"); return; }
  var u;
  try { u = upstream(target); } catch (e) { res.writeHead(403); res.end(String(e.message)); return; }

  var headers = {};
  for (var k in req.headers) {
    var lk = k.toLowerCase();
    if (lk === "range") headers.Range = req.headers[k];
  }
  var mod = u.protocol === "https:" ? https : http;
  var up = mod.request(u, { headers: headers }, function (ur) {
    var out = {};
    ["content-range", "content-length", "content-type"].forEach(function (h) {
      if (ur.headers[h]) out[h] = ur.headers[h];
    });
    out["Access-Control-Allow-Origin"] = "*";
    res.writeHead(ur.statusCode, out);
    ur.pipe(res);
  });
  up.on("error", function (e) { res.writeHead(502); res.end("upstream error: " + e.message); });
  up.end();
}).listen(port, function () {
  console.log("Payload 代理已启动: http://127.0.0.1:" + port +
    (allowHosts ? "（仅允许: " + allowHosts.join(",") + "）" : "（未限制上游域名，公网部署请务必设置允许列表）"));
});
