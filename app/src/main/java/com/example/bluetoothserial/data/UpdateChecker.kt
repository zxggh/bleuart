package com.example.bluetoothserial.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 检查到的新版本信息 */
data class AppUpdate(
    val versionName: String,
    val apkUrl: String,
    val notes: String
)

/** 更新检查器(后台线程,不阻塞 UI) */
object UpdateChecker {

    /**
     * 更新源地址。
     * 1) 默认使用 GitHub Releases API:在仓库 Releases 发布新版本并上传 APK 即可,
     *    版本号用 tag 如 v1.0.1,无需维护版本文件;
     * 2) 也可改为自建服务器 / Gitee 等平台的 version.json 地址,格式:
     *    {"versionName":"1.1.0","apkUrl":"https://.../app-debug.apk","notes":"更新说明"}
     */
    const val UPDATE_URL = "https://api.github.com/repos/zxggh/bleuart/releases/latest"

    /**
     * 检查更新。
     * @param currentVersionName 当前版本号(如 1.0.0)
     * @param onResult (ok=是否成功获取到更新源信息, update=新版本信息,null 表示已是最新)
     *                 在后台线程回调,调用方需自行切回主线程
     */
    fun check(currentVersionName: String, onResult: (Boolean, AppUpdate?) -> Unit) {
        Thread({
            var ok = false
            var update: AppUpdate? = null
            try {
                val conn = URL(UPDATE_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "ZxgBluetoothAssistant")
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    update = if (UPDATE_URL.contains("api.github.com")) {
                        parseGitHubRelease(body, currentVersionName)
                    } else {
                        parseCustomVersionJson(body, currentVersionName)
                    }
                    ok = true
                }
                conn.disconnect()
            } catch (_: Exception) {
                ok = false
            }
            onResult(ok, update)
        }, "update-check").start()
    }

    /** 解析 GitHub Releases API 响应 */
    private fun parseGitHubRelease(json: String, current: String): AppUpdate? {
        return try {
            val obj = JSONObject(json)
            val tag = obj.optString("tag_name", "").removePrefix("v")
            val notes = obj.optString("body", "").trim()
            val assets = obj.optJSONArray("assets") ?: JSONArray()
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name", "").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url")
                    break
                }
            }
            if (tag.isEmpty() || apkUrl.isNullOrEmpty()) return null
            if (compareVersions(tag, current) <= 0) null
            else AppUpdate(tag, apkUrl, notes)
        } catch (_: Exception) {
            null
        }
    }

    /** 解析自定义 version.json */
    private fun parseCustomVersionJson(json: String, current: String): AppUpdate? {
        return try {
            val obj = JSONObject(json)
            val name = obj.optString("versionName", "")
            val apkUrl = obj.optString("apkUrl", "")
            if (name.isEmpty() || apkUrl.isEmpty()) return null
            if (compareVersions(name, current) <= 0) null
            else AppUpdate(name, apkUrl, obj.optString("notes", ""))
        } catch (_: Exception) {
            null
        }
    }

    /** 语义化版本比较:a > b 返回正数,相等返回 0 */
    fun compareVersions(a: String, b: String): Int {
        fun parts(s: String): List<Int> =
            s.trim().removePrefix("v").split('.', '-', '_', ' ')
                .filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
