package com.zcode.payloadextractor.core

import java.net.HttpURLConnection
import java.net.URL

/**
 * OPPO / 一加 ColorOS 16 动态下载链接换址。
 *
 * 新版 OTA 链接形如:
 *   component-ota-cn.allawntech.com/downloadCheck?c=..&p=..&g=..&id=..&s=..
 * 接口返回 JSON，body 内含真实 zip 直链；动态链接约 10~30 分钟过期且绑定生成设备。
 */
object DynamicUrl {
    private val HOST = Regex("allawntech|allawnfs|allawnos|coloros", RegexOption.IGNORE_CASE)
    private val PATH = Regex("/(downloadCheck|download)\\?")
    private val ZIP_URL = Regex("https?://[^\"\\s]+?\\.zip(\\?[^\"\\s]*)?", RegexOption.IGNORE_CASE)

    fun isDynamic(url: String): Boolean = HOST.containsMatchIn(url) && PATH.containsMatchIn(url)

    /** 返回真实 zip 直链；无效时抛异常。 */
    fun resolve(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 20000
        conn.instanceFollowRedirects = false
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "okhttp/4.9.3")
            val code = conn.responseCode
            val loc = conn.getHeaderField("Location")
            if (code in 300..399 && !loc.isNullOrEmpty()) return loc
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            // 非 JSON（可能直接 302/反代到 zip 文件）：交给 ZIP 解析
            if (!body.trimStart().startsWith("{")) return url
            // 换出真实 zip 直链（JSON 形式如 {"errMsg":"0","responseCode":0,"body":{"url":"...zip"}}）
            val unescaped = body.replace("\\/", "/")
            val errOk1 = Regex("\"errMsg\"\\s*:\\s*\"0\"").containsMatchIn(unescaped)
            val errOk2 = Regex("\"responseCode\"\\s*:\\s*0").containsMatchIn(unescaped)
            val hit = if (errOk1 || errOk2) ZIP_URL.find(unescaped)?.value else null
            if (hit != null) return hit.trim('"')
            val m1 = Regex("\"errMsg\"\\s*:\\s*([^,}]+)").find(unescaped)
            val m2 = Regex("\"responseCode\"\\s*:\\s*([^,}]+)").find(unescaped)
            val report = m1?.groupValues?.get(1) ?: m2?.groupValues?.get(1) ?: "空响应"
            throw RuntimeException(
                "动态链接无效（服务端返回 $report）：ColorOS 新动态链接约 10~30 分钟过期且绑定生成设备，" +
                "请在手机上生成新链接后立刻粘贴解析。"
            )
        } finally {
            conn.disconnect()
        }
    }
}
