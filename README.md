# Payload 在线提取 (PayloadExtractor)

[![APK 下载](https://img.shields.io/github/downloads/GetEnjoyZhang/payload-extractor/total?style=flat-square&label=APK%E4%B8%8B%E8%BD%BD)](https://github.com/GetEnjoyZhang/payload-extractor/releases/latest)
[![Stars](https://img.shields.io/github/stars/GetEnjoyZhang/payload-extractor?style=flat-square)](https://github.com/GetEnjoyZhang/payload-extractor/stargazers)
[![Release](https://img.shields.io/github/v/release/GetEnjoyZhang/payload-extractor?style=flat-square&label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC)](https://github.com/GetEnjoyZhang/payload-extractor/releases/latest)
[![Fork](https://img.shields.io/github/forks/GetEnjoyZhang/payload-extractor?style=flat-square)](https://github.com/GetEnjoyZhang/payload-extractor/network/members)
[![License](https://img.shields.io/github/license/GetEnjoyZhang/payload-extractor?style=flat-square&label=%E8%AE%B8%E5%8F%AF%E8%AF%81)](LICENSE)

一个安卓小工具：**输入 OTA 包 zip 的 URL，在线解析并按需提取分区镜像（如 boot.img），无需下载整个几个 GB 的包。**

原理同 payload dumper / "payload dumper online"：

1. 用 HTTP **Range 请求**只下载 zip 文件尾部，解析 EOCD / ZIP64 中央目录，列出所有条目
2. 点击普通条目（如 `boot.img`、`firmware-update/vbmeta.img`）→ 只下载该条目的压缩数据，本地解压
3. 点击 `payload.bin`（A/B OTA）→ 只下载 payload 头部 + manifest（protobuf），列出全部分区 →
   选择分区后，只下载该分区用到的数据块（REPLACE / REPLACE_BZ / REPLACE_XZ / REPLACE_BROTLI / ZERO / DISCARD），
   本地重建出原始分区镜像

对增量包（含 MOVE / BSDIFF / SOURCE_COPY / SOURCE_BSDIFF / PUFFDIFF 操作）会明确报错，
因为仅凭 OTA 包无法脱离底包重建。

## 功能特性（v1.2）

- 🧊 **液态玻璃 UI**：渐变底 + 半透明玻璃卡片 + 高光描边，浅色 / 深色模式自适应
- 🔍 **搜索镜像**：列表实时过滤，可勾选「仅 .img」快速筛选镜像文件
- 📊 **行内进度**：每个条目/分区独立进度条 + 实时速度（MB/s），完成即可分享
- 📦 **OTA 信息卡**：自动解析 `META-INF/com/android/metadata` 显示文件大小、设备、文件名
- 🗂 **payload 分区列表**：点击 payload.bin 即切换到分区视图，可一键「全部提取」
- ✋ **任务取消**：任意时刻可取消当前下载
- 📱 **厂商兼容**（v1.2）：自动识别小米等厂商的 payload 变体
  - payload 头部**大端字节序**自动探测（标准 AOSP 为小端）
  - **新版 manifest 字段编号**（block_size=3、partitions=13、dst extents=6）
  - 流式解压写入，任意大小分区（>2GB 的 super 子分区）不再占用大量内存

## 安装使用

- 安装 `PayloadExtractor.apk`（Android 8.0 / API 26 及以上）
- 粘贴 OTA zip 直链 URL（若剪贴板里是 .zip 链接会自动填入），点「解析」
- 搜索框过滤镜像 → 点行内「提取」或「全部提取」
- 结果保存在应用目录 `Android/data/com.zcode.payloadextractor/files/extracted/`，
  完成后可通过弹窗或行内「分享」按钮发送到任意位置

## 工程结构

```
app/src/main/java/com/zcode/payloadextractor/
  core/RemoteZip.kt      远程 ZIP：Range 探测、EOCD/ZIP64 中央目录解析、按需读取/流式解压
  core/PayloadReader.kt  payload.bin：头部 + 极简 protobuf 解析 manifest（分区/操作/extent）
  core/Extractor.kt      条目提取 + 分区重建（随机访问与流式两条路径），支持取消
  MainActivity.kt        界面（无 androidx 依赖，纯 framework 控件 + 液态玻璃皮肤）
  ShareProvider.kt       content:// 分享提取结果
app/src/test/kotlin/test/DesktopTest.kt  桌面 JVM 测试（合成 payload + 本地 Range 服务器 + 真实 URL）
```

依赖（均已 dex 进 APK）：
- org.apache.commons:commons-compress:1.21（bzip2）
- org.tukaani:xz:1.9（xz）
- org.brotli:dec:0.1.2（brotli）
- kotlin-stdlib 2.0.20

## 构建（无需 Gradle）

本项目用 JDK + Kotlin embeddable compiler + Android build-tools 直接命令行构建：

```bash
# 1. Kotlin -> JVM class
java -cp <kotlin-compiler-embeddable.jar + stdlib + trove4j + coroutines + annotations> \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib \
  -cp <kotlin-stdlib.jar>;<android.jar>;libs/commons-compress.jar;libs/xz.jar;libs/brotli-dec.jar \
  -d out_android <全部 .kt 文件>

# 2. jar + d8 转 dex
jar cf classes.jar -C out_android .
java -cp <build-tools>/lib/d8.jar com.android.tools.r8.D8 --release --min-api 26 \
  --lib <android.jar> --output dexout classes.jar <4 个依赖 jar>

# 3. aapt2 打资源 + 清单
aapt2 compile --dir app/src/main/res -o res.zip
aapt2 link -o base.apk -I <android.jar> --manifest app/src/main/AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 34 res.zip

# 4. dex 塞进 apk（stored）+ zipalign + apksigner 签名
python -c "zipfile 追加 classes.dex (ZIP_STORED)"
zipalign -f 4 base.apk aligned.apk
keytool -genkeypair -keystore ks.jks -alias app ...
java -jar <build-tools>/lib/apksigner.jar sign --ks ks.jks --out PayloadExtractor.apk aligned.apk
```

## 测试

`DesktopTest.kt` 可在桌面 JVM 直接运行（无需安卓设备）：

- T1 payload.bin(stored) 随机访问提取 —— 重建内容逐块校验
- T2 payload.bin(deflate) 流式提取 —— 重建内容逐块校验
- T3 增量包（PUFFDIFF）正确拒绝
- T4 普通条目(deflate) 96MB 提取
- T5 传入真实 OTA URL：解析目录 + 提取 boot.img 校验 `ANDROID!` 魔数与大小
- MiuiDiag：真实小米 V14 包（大端头部 + 新版 manifest schema）解析 34 个分区并提取 vbmeta 校验 `AVB0`

```bash
java -cp out_core;kotlin-stdlib;libs/* test.DesktopTestKt <OTA zip URL>
```
