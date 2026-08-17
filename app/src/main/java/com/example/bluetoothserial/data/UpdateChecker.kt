package com.example.bluetoothserial.data

import android.content.Context
import android.net.ConnectivityManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
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
     * 默认更新源地址。
     * 当前默认 GitHub Releases API(便于开发调试,需要代理):
     *   https://api.github.com/repos/<user>/<repo>/releases/latest
     * 自动从 tag(v1.0.1)与 assets 中解析版本和 APK。
     *
     * 若最终用户在国内无代理,可在 App 内「更新地址」中改为:
     *   https://gitee.com/<用户名>/<仓库名>/raw/<分支>/version.json
     * (version.json 格式: {"versionName":"1.1.0","apkUrl":"https://.../app-debug.apk","notes":"更新说明"})
     * 无需重新编译。
     */
    const val DEFAULT_UPDATE_URL = "https://api.github.com/repos/zxggh/bleuart/releases/latest"

    /**
     * 检查更新。
     * @param context 用于读取系统代理(Android 系统代理设置)
     * @param updateUrl 更新源 URL(version.json 或 GitHub Releases API)
     * @param currentVersionName 当前版本号(如 1.0.0)
     * @param onResult (ok=是否成功获取到更新源信息, update=新版本信息,null 表示已是最新,
     *                 err=失败原因描述,便于排查)
     *                 在后台线程回调,调用方需自行切回主线程
     */
    fun check(context: Context, updateUrl: String, currentVersionName: String, onResult: (Boolean, AppUpdate?, String?) -> Unit) {
        val url = updateUrl.ifBlank { DEFAULT_UPDATE_URL }
        Thread({
            var ok = false
            var update: AppUpdate? = null
            var err: String? = null
            // 失败自动重试一次
            for (attempt in 0..1) {
                try {
                    val conn = openConnection(context, url)
                    conn.connectTimeout = 12000
                    conn.readTimeout = 12000
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("User-Agent", "ZxgBluetoothAssistant")
                    val code = conn.responseCode
                    if (code == 200) {
                        val body = conn.inputStream.bufferedReader().readText()
                        update = if (url.contains("api.github.com")) {
                            parseGitHubRelease(body, currentVersionName)
                        } else {
                            parseCustomVersionJson(body, currentVersionName)
                        }
                        ok = true
                    } else {
                        err = "HTTP $code"
                    }
                    conn.disconnect()
                    if (ok) break
                } catch (e: Exception) {
                    err = e.message ?: e.javaClass.simpleName
                }
                try { Thread.sleep(500) } catch (_: InterruptedException) {}
            }
            onResult(ok, update, err)
        }, "update-check").start()
    }

    /** 读取手机系统代理并建立连接(Android 系统代理设置默认不被 HttpURLConnection 使用) */
    private fun openConnection(context: Context, url: String): HttpURLConnection {
        val proxy = getSystemProxy(context)
        return if (proxy != null && proxy != Proxy.NO_PROXY) {
            URL(url).openConnection(proxy) as HttpURLConnection
        } else {
            URL(url).openConnection() as HttpURLConnection
        }
    }

    private fun getSystemProxy(context: Context): Proxy? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.defaultProxy
        } catch (_: Exception) {
            null
        }
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
