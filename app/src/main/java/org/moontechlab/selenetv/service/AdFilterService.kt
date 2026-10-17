package org.moontechlab.selenetv.service

import android.util.Log

/**
 * m3u8 去广告（对齐 LunaTV 网页端默认规则）。
 *
 * 一期：纯 Kotlin 内置关键字过滤，零网络、零新依赖。
 * 二期预留：拉取服务端 /api/ad-filter 自定义 JS（customCode/customVersion 字段已留好，
 * 执行引擎与版本缓存逻辑在接入 QuickJS 时补上）。
 */
object AdFilterService {
    private const val TAG = "AdFilter"

    /** 与 LunaTV 网页端 play/page.tsx 默认规则一致的广告关键字（大小写不敏感）。 */
    private val AD_KEYWORDS = listOf(
        "sponsor",
        "/ad/",
        "/ads/",
        "advert",
        "advertisement",
        "/adjump",
        "redtraffic"
    )

    /**
     * 当前播放源 key（点播为 "source+id"，直播为 LiveSource.key），对应自定义 JS 契约的 type 参数。
     * 由 PlayerFactory.create() 在建播放器时写入；一期暂未使用，二期自定义 JS 需要。
     */
    @Volatile
    var activeSourceType: String? = null

    /** 二期：服务端下发的自定义去广告代码（/api/ad-filter?full=true 的 code 字段）。 */
    @Volatile
    var customCode: String? = null

    /** 二期：自定义代码版本号（用于与服务端 version 比对、决定是否重拉）。 */
    @Volatile
    var customVersion: Int = 0

    fun isEnabled(): Boolean = AppDataService.getAdFilterEnabledSync()

    /**
     * 过滤入口：开关关闭时原样返回；否则优先自定义代码（未接入时跳过），降级到内置关键字规则。
     * 纯函数、线程安全（调用方是 ExoPlayer loader 线程）。
     */
    fun filter(type: String?, content: String): String {
        if (content.isEmpty()) return content
        val code = customCode
        if (!code.isNullOrBlank()) {
            // 二期：QuickJS 执行 filterAdsFromM3U8(type, content)，抛错则降级到关键字规则。
            Log.w(TAG, "自定义去广告代码尚未接入执行引擎，降级使用关键字规则")
        }
        return filterByKeywords(content)
    }

    /**
     * 网页端默认规则的逐行 port：
     * - 删掉所有含 #EXT-X-DISCONTINUITY 的行；
     * - 遇到 #EXTINF: 行时看下一行 URL 是否命中广告关键字，命中则两行一起丢。
     * master playlist 的 #EXT-X-STREAM-INF 行不含 "#EXTINF:" 子串，天然不受影响（与网页端行为一致）。
     */
    fun filterByKeywords(m3u8Content: String): String {
        if (m3u8Content.isEmpty()) return m3u8Content
        val lines = m3u8Content.split('\n')
        val out = ArrayList<String>(lines.size)
        var removed = 0
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.contains("#EXT-X-DISCONTINUITY")) {
                i++
                removed++
                continue
            }
            if (line.contains("#EXTINF:") && i + 1 < lines.size) {
                val nextLine = lines[i + 1]
                if (AD_KEYWORDS.any { keyword -> nextLine.contains(keyword, ignoreCase = true) }) {
                    i += 2
                    removed += 2
                    continue
                }
            }
            out.add(line)
            i++
        }
        if (removed > 0) Log.i(TAG, "关键字规则移除 $removed 行")
        return out.joinToString("\n")
    }
}
