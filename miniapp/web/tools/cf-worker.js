// Cloudflare Worker 版 CORS 代理（免费额度 10 万请求/天，无需备案）
// 部署: dash.cloudflare.com → Workers → 新建 → 粘贴本文件 → 部署
// 公网部署请务必配置 ALLOW_HOSTS，防止被当作开放代理滥用
const ALLOW_HOSTS = null; // 例如 ["bkt-sgp-miui-ota-update-alisgp.oss-ap-southeast-1.aliyuncs.com", "gauss-opexcostmanual-cn.allawnfs.com"]

export default {
  async fetch(request) {
    if (request.method === "OPTIONS") {
      return new Response(null, {
        headers: {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Headers": "*",
          "Access-Control-Allow-Methods": "GET,OPTIONS",
          "Access-Control-Expose-Headers": "Content-Range, Content-Length, Content-Type"
        }
      });
    }
    const u = new URL(request.url);
    const target = u.searchParams.get("u");
    if (!target) return text("missing ?u=<encoded url>", 400);
    let up;
    try { up = new URL(target); } catch (e) { return text("bad url", 400); }
    if (!/^https?:$/.test(up.protocol)) return text("bad protocol", 400);
    if (ALLOW_HOSTS && !ALLOW_HOSTS.includes(up.hostname)) return text("host not allowed", 403);

    const headers = {};
    const range = request.headers.get("range");
    if (range) headers.Range = range;

    const resp = await fetch(target, { headers: headers });
    const out = new Headers();
    ["content-range", "content-length", "content-type"].forEach(function (h) {
      const v = resp.headers.get(h);
      if (v) out.set(h, v);
    });
    out.set("Access-Control-Allow-Origin", "*");
    out.set("Access-Control-Expose-Headers", "Content-Range, Content-Length, Content-Type");
    return new Response(resp.body, { status: resp.status, headers: out });
  }
};

function text(s, code) {
  return new Response(s, { status: code, headers: { "Access-Control-Allow-Origin": "*", "Content-Type": "text/plain" } });
}
