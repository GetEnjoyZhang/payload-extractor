# MiniPayload —— 微信小程序版 + 网页版 · OTA 在线提取

「Payload 在线提取」的微信小程序 + 网页双版本：粘贴 OTA 包 zip 的 URL，在线解析并按需提取分区镜像（boot.img 等），无需下载整包。核心逻辑共用（`utils/`）。

## ⚠️ 小程序平台限制（使用前必读）

微信对**正式发布**的小程序强制校验请求域名白名单，而本工具需要访问任意用户提供的 OTA 链接。
因此本小程序**只能自用**：

| 使用方式 | 是否可用 | 说明 |
|---|---|---|
| 开发者工具预览 | ✅ | 详情 → 本地设置 → 勾选「不校验合法域名…」 |
| 手机体验版/开发版 | ✅ | 需打开「调试」模式（右上角胶囊 → 开发调试） |
| 正式发布上线 | ❌ | 任意域名无法进白名单（微信平台硬性限制） |

## 使用

1. 微信开发者工具导入本目录（测试号 appid `touristappid` 即可）
2. 勾选「不校验合法域名」，编译
3. 粘贴 OTA zip 直链 → 解析 → 自动列出分区 → 搜索/提取
4. 提取完成后 SHA-256（payload 分区）/ CRC32（zip 条目）自动校验，
   通过后可「分享」到微信聊天导出（文件存于小程序沙箱）

## 与安卓版的差异

- 核心逻辑用纯 JS 重写（`utils/`），并在 Node 中用真实 URL 测试（`test/core.test.js`，T1–T5 全部通过）
- 解压器全部为纯 JS（`vendor/`）：pako（deflate）、xz.js（自写 XZ 容器解析 + SortaCore/lzma2-js）、bzip2、devongovett/brotli.js、js-sha256
- 仅支持 `payload.bin` 为 **stored 存储** 的包（常见 OTA 均如此）；压缩存储的 payload.bin 提示改用 App 版
- 纯 JS 解压速度有限，大分区（system 等）耗时明显，建议只提取 boot/vbmeta/dtbo 等小镜像
- 文件保存在小程序沙箱（`wx.env.USER_DATA_PATH`），通过「分享到聊天」导出；无法直接写入手机 Download

## 构建

无需编译：微信开发者工具导入即可运行。`test/core.test.js` 可在 Node 18+ 里验证核心逻辑：

```bash
node test/core.test.js        # 合成包测试
node test/core.test.js real   # 加上真实 OTA URL 测试（一加 + 小米）
```

## 🌐 网页版（web/）

浏览器直接用，无域名白名单限制，**推荐公开分享时使用**：

```bash
npm install                          # 安装 esbuild
npm run build                        # 打包 web/dist/bundle.js
npm run proxy                        # 启动本地 CORS 代理 (127.0.0.1:8787)
# 浏览器打开 web/index.html（或本地静态服务），页脚填入代理地址
```

- 大部分 OTA 服务器没有 CORS 头，直连会被浏览器拦截 → 需要转发代理
  - 本地：`node web/tools/proxy.js 8787`（可传第二个参数限制上游域名）
  - 长期使用：把 `web/tools/cf-worker.js` 部署到 Cloudflare Workers（免费），**公网部署务必配置 ALLOW_HOSTS 白名单**防滥用
- 大文件（>512MB）在 Chrome/Edge 中会弹出系统「另存为」并流式写盘；其他浏览器走内存下载（上限约 1GB）
- 提取完成自动校验（SHA-256/CRC32），失败自动重提一次，校验通过才触发浏览器下载

### 部署到 GitHub Pages

把 `web/index.html`、`web/style.css`、`web/dist/bundle.js` 三个文件推到仓库（如 `docs/` 目录）并开启 Pages 即可；代理仍需按上节运行（本机或 Cloudflare）。

