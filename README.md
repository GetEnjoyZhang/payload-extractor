# Payload 在线提取

输入 OTA 包 zip 的 URL，在线解析并按需提取分区镜像（boot.img 等），无需下载整包。原理同 payload dumper：HTTP Range 按需读取 + 本地解压重建，支持 SHA-256 / CRC32 自动校验。

[![APK 下载](https://img.shields.io/github/downloads/GetEnjoyZhang/payload-extractor/total?style=flat-square&label=APK%E4%B8%8B%E8%BD%BD)](https://github.com/GetEnjoyZhang/payload-extractor/releases/latest)
[![Stars](https://img.shields.io/github/stars/GetEnjoyZhang/payload-extractor?style=flat-square)](stargazers)
[![Release](https://img.shields.io/github/v/release/GetEnjoyZhang/payload-extractor?style=flat-square&label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC)](releases/latest)
[![License](https://img.shields.io/github/license/GetEnjoyZhang/payload-extractor?style=flat-square&label=%E8%AE%B8%E5%8F%AF%E8%AF%81)](LICENSE)

## 三端版本

| 版本 | 目录 | 说明 |
|---|---|---|
| 📱 安卓 App | [`app/`](app/) | 已签名 APK 在 [Releases](releases/latest)，Android 8.0+ 直接安装 |
| 🌐 网页版 | [`miniapp/web/`](miniapp/web/) | **GitHub Pages 在线使用**（见下），或本地 `node proxy.js` 起代理 |
| 💬 微信小程序 | [`miniapp/`](miniapp/) | 自用版：开发者工具导入，需开启「不校验合法域名」（平台限制，无法正式上架任意 URL 工具） |

## 网页版在线使用

1. 打开 <https://getenjoyzhang.github.io/payload-extractor/>
2. 大部分 OTA 服务器没有 CORS 头，需要一个小转发代理（页面页脚有说明）：
   - 本地：`node miniapp/web/tools/proxy.js 8787`，页面代理框填 `http://127.0.0.1:8787`
   - 长期：把 `miniapp/web/tools/cf-worker.js` 部署到 Cloudflare Workers（免费）
3. 粘贴 OTA 直链 → 解析 → 提取（Chrome/Edge 支持大文件流式保存）

核心逻辑三端共用（Kotlin / JS 双实现），测试覆盖：合成 payload、真实一加包 boot.img（CRC32）、真实小米包 vbmeta（XZ + SHA-256）。
