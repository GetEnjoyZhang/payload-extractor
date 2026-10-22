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

## 更新日志

- **v1.4.0（2026-10-17）**：修复「不支持的操作类型 14」——新版 HyperOS 2 OTA（红米 K50 至尊/PD2528 等）使用 ZSTD 压缩的 payload 数据块，安卓 App 现已支持在线提取（内置 zstd-jni 四 ABI 原生库）；新增右上角「设置」→「关于本项目」（含项目地址跳转）
- **网页版同步修复（2026-10-22）**：网页版/小程序核心集成 [fzstd](https://www.npmjs.com/package/fzstd)（纯 JS ZSTD 解压器，8KB），同样支持 ZSTD 数据块；合成 T6 用例覆盖，Pages 线上已更新（硬刷新 Ctrl+F5 生效）
- **v1.3.1**：Android App（详见 [`app/`](app/)）：远程 zip + payload.bin 按需 Range 提取，液态玻璃 UI，小米大端头部 / 新版 manifest schema 兼容，搜索、行内进度、全部提取、任务取消
- **网页版 / 小程序**：与 App 核心逻辑一致，纯 JS 解压（pako / xz.js / bzip2 / brotli），仅支持 payload.bin 为 stored 存储的包

## 安卓 App 使用

- 安装 [Releases](releases/latest) 中的 `PayloadExtractor.apk`（Android 8.0+）
- 粘贴 OTA zip 直链 URL → 解析 → 搜索镜像 → 行内「提取」或「全部提取」
- 结果保存在应用目录 `Android/data/com.zcode.payloadextractor/files/extracted/` 或公共 Download，可随时分享
- 对增量包（含 MOVE / BSDIFF / SOURCE_COPY / SOURCE_BSDIFF / PUFFDIFF 等操作）明确报错：仅凭 OTA 包无法脱离底包重建

## 网页版 / 小程序使用限制

- 微信小程序因域名白名单限制**只能自用**（详见 [`miniapp/README.md`](miniapp/README.md)）
- 网页版纯 JS 解压速度有限，大分区（system 等）耗时明显，建议只提取 boot / vbmeta / dtbo 等小镜像
