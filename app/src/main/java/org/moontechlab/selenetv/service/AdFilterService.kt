package org.moontechlab.selenetv.service

import android.util.Log

/**
 * m3u8 去广告（对齐 LunaTV 网页端默认规则，并扩展了广告块指纹规则）。
 *
 * 一期：纯 Kotlin 内置规则，零网络、零新依赖。共两层：
 * 1. 广告块指纹：删掉被 #EXT-X-DISCONTINUITY 对夹住、且分段时长呈循环小数
 *    （如 5.566667、2.933333）的广告块。某类广告编码器按 30fps 整帧数输出时长，
 *    写成秒就是这种循环小数，与正片分段的规整时长（4.000000、3.880000）显著不同。
 *    这是指纹式启发规则——换一家广告编码器就可能失效，届时看日志再调。
 * 2. 关键字规则：网页端 play/page.tsx 默认规则的逐行 port。
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
     * 过滤入口：开关关闭时原样返回；否则先做广告块指纹过滤，再走关键字规则
     * （自定义代码未接入时跳过，降级到内置规则）。
     * 纯函数、线程安全（调用方是 ExoPlayer loader 线程）。
     */
    fun filter(type: String?, content: String): String {
        if (content.isEmpty()) return content
        val code = customCode
        if (!code.isNullOrBlank()) {
            // 二期：QuickJS 执行 filterAdsFromM3U8(type, content)，抛错则降级到内置规则。
            Log.w(TAG, "自定义去广告代码尚未接入执行引擎，降级使用内置规则")
        }
        var lines = content.split('\n')
        var removedBlocks = 0
        var removedKeywords = 0
        removeAdBlocks(lines).let {
            lines = it.first
            removedBlocks = it.second
        }
        filterByKeywords(lines).let {
            lines = it.first
            removedKeywords = it.second
        }
        val removed = removedBlocks + removedKeywords
        if (removed > 0) Log.i(TAG, "去广告共移除 $removed 行（广告块 $removedBlocks，关键字 $removedKeywords）")
        return lines.joinToString("\n")
    }

    /**
     * 广告块指纹过滤：找出被 #EXT-X-DISCONTINUITY 对夹住的分段块，
     * 若块内出现循环小数时长（如 5.566667、2.933333），整块（含两头标记）丢掉。
     * 返回（过滤后行列表，移除行数）。
     */
    private fun removeAdBlocks(lines: List<String>): Pair<List<String>, Int> {
        val discIdx = lines.indices.filter { lines[it].contains("#EXT-X-DISCONTINUITY") }
        if (discIdx.isEmpty()) return lines to 0
        // 块边界：marker[i]..marker[i+1]，最后一段用末尾做哨兵；块含开头标记、不含结尾标记
        val bounds = discIdx + listOf(lines.size)
        val drop = BooleanArray(lines.size)
        var removed = 0
        for (b in discIdx.indices) {
            val start = discIdx[b]
            val end = bounds[b + 1]
            var suspicious = false
            for (i in start + 1 until end) {
                val line = lines[i]
                if (line.startsWith("#EXTINF:") && isSuspiciousDuration(line)) {
                    suspicious = true
                    break
                }
            }
            if (suspicious) {
                for (i in start until end) {
                    if (!drop[i]) {
                        drop[i] = true
                        removed++
                    }
                }
            }
        }
        if (removed == 0) return lines to 0
        return lines.filterIndexed { i, _ -> !drop[i] } to removed
    }

    /** 循环小数时长 ≈ 30fps 整帧数换算来的秒数，是某类广告编码器的指纹（如 5.566667、2.933333）。 */
    private fun isSuspiciousDuration(extinfLine: String): Boolean =
        extinfLine.contains("33333") || extinfLine.contains("66667")

    /**
     * 网页端默认规则的逐行 port：
     * - 删掉所有含 #EXT-X-DISCONTINUITY 的行；
     * - 遇到 #EXTINF: 行时看下一行 URL 是否命中广告关键字，命中则两行一起丢。
     * master playlist 的 #EXT-X-STREAM-INF 行不含 "#EXTINF:" 子串，天然不受影响（与网页端行为一致）。
     * 返回（过滤后行列表，移除行数）。
     */
    private fun filterByKeywords(lines: List<String>): Pair<List<String>, Int> {
        if (lines.isEmpty()) return lines to 0
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
        return out to removed
    }
}
