package org.moontechlab.selenetv.service

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自定义去广告 JS 拉取器（对齐 LunaTV 网页端逻辑）。
 *
 * - 请求带上登录 cookie：服务端 proxy 中间件的公开白名单不含 /api/ad-filter，
 *   无 cookie 会 401（route 注释虽写"公开"，实际以中间件为准）。
 * - GET /api/ad-filter → {version}：version 为 0 表示服务端未配置，清空本地缓存。
 * - 版本号变化时 GET /api/ad-filter?full=true → {code, version}，落盘缓存。
 * - 版本号最多每 30 分钟检查一次；任何失败都静默降级（沿用内置规则）。
 *
 * 调用方是 ExoPlayer loader 线程，可阻塞做网络。
 */
object AdFilterFetcher {
    private const val TAG = "AdFilter"
    private const val CHECK_INTERVAL_MS = 30 * 60 * 1000L
    private const val TIMEOUT_MS = 10_000

    @Volatile
    private var lastCheckMs = 0L

    /** 确保本地自定义代码为最新；失败时不抛错。 */
    fun ensureLatest() {
        if (SystemClock.elapsedRealtime() - lastCheckMs < CHECK_INTERVAL_MS
            && AdFilterService.hasCustomCode()
        ) return
        lastCheckMs = SystemClock.elapsedRealtime()
        try {
            val base = AppDataService.getServerUrlSync()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
                ?: return
            val version = getJson("$base/api/ad-filter").optInt("version", 0)
            if (version == 0) {
                AdFilterService.clearCustomCode()
                return
            }
            if (version != AdFilterService.customVersion || !AdFilterService.hasCustomCode()) {
                val full = getJson("$base/api/ad-filter?full=true")
                val code = full.optString("code", "")
                val newVersion = full.optInt("version", version)
                if (code.isNotBlank()) {
                    AdFilterService.setCustomCode(code, newVersion)
                    Log.i(TAG, "自定义去广告代码已更新到版本 $newVersion")
                } else {
                    AdFilterService.clearCustomCode()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "拉取自定义去广告规则失败，沿用内置规则: ${e.message}")
        }
    }

    private fun getJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json")
            // 注意：服务端 proxy 中间件的公开白名单不含 /api/ad-filter，
            // 无 cookie 会 401；App 已登录，直接带上 cookie（与 ApiService 一致）。
            AppDataService.getCookiesSync()?.takeIf { it.isNotBlank() }?.let {
                conn.setRequestProperty("Cookie", it)
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }
}
