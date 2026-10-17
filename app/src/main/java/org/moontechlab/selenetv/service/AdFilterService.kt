package org.moontechlab.selenetv.service

import android.util.Log
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.QuickJSContext
import org.json.JSONObject

/**
 * m3u8 去广告（对齐 LunaTV 网页端）。
 *
 * 优先级：自定义 JS（服务端 /api/ad-filter 下发，QuickJS 执行）
 *      → 内置规则（广告块指纹 + 关键字）→ 原样返回。
 *
 * 自定义 JS 契约与网页端一致：代码定义 filterAdsFromM3U8(type, m3u8Content)，
 * type 为当前播放源 key。执行抛错/返回空时降级到内置规则。
 *
 * 内置规则（零网络、零新依赖）：
 * 1. 广告块指纹：删掉被 #EXT-X-DISCONTINUITY 对夹住、且分段时长呈循环小数
 *    （如 5.566667、2.933333）的广告块。某类广告编码器按 30fps 整帧数输出时长，
 *    写成秒就是这种循环小数，与正片分段的规整时长（4.000000、3.880000）显著不同。
 *    这是指纹式启发规则——换一家广告编码器就可能失效，届时看日志再调。
 * 2. 关键字规则：网页端 play/page.tsx 默认规则的逐行 port。
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
     * 由 PlayerFactory.create() 在建播放器时写入。
     */
    @Volatile
    var activeSourceType: String? = null

    /** 自定义去广告 JS（服务端下发，落盘缓存；空=未配置）。 */
    @Volatile
    var customCode: String? = null
        private set

    /** 自定义代码版本号（用于与服务端 version 比对、决定是否重拉）。 */
    @Volatile
    var customVersion: Int = 0
        private set

    @Volatile
    private var prefsLoaded = false

    @Volatile
    private var quickJsReady: Boolean? = null

    fun isEnabled(): Boolean = AppDataService.getAdFilterEnabledSync()

    fun hasCustomCode(): Boolean {
        loadPrefsOnce()
        return !customCode.isNullOrBlank()
    }

    fun setCustomCode(code: String, version: Int) {
        customCode = code
        customVersion = version
        prefsLoaded = true
        AppDataService.setAdFilterCodeSync(code)
        AppDataService.setAdFilterVersionSync(version)
    }

    fun clearCustomCode() {
        customCode = null
        customVersion = 0
        prefsLoaded = true
        AppDataService.setAdFilterCodeSync("")
        AppDataService.setAdFilterVersionSync(0)
    }

    private fun loadPrefsOnce() {
        if (prefsLoaded) return
        synchronized(this) {
            if (prefsLoaded) return
            customCode = AppDataService.getAdFilterCodeSync().takeIf { it.isNotBlank() }
            customVersion = AppDataService.getAdFilterVersionSync()
            prefsLoaded = true
        }
    }

    /**
     * 过滤入口：开关关闭时原样返回；否则先同步自定义代码（节流），
     * 有自定义代码则优先用 QuickJS 执行，失败/无代码时降级到内置规则。
     * 调用方是 ExoPlayer loader 线程，可阻塞。
     */
    fun filter(type: String?, content: String): String {
        if (content.isEmpty()) return content
        loadPrefsOnce()
        AdFilterFetcher.ensureLatest()
        val code = customCode
        if (!code.isNullOrBlank()) {
            val customResult = runCustomJs(type, code, content)
            if (!customResult.isNullOrEmpty()) {
                Log.i(TAG, "自定义去广告生效（版本 $customVersion）")
                return customResult
            }
            Log.w(TAG, "自定义去广告执行失败，降级使用内置规则")
        }
        return filterBuiltin(content)
    }

    /**
     * 用 QuickJS 执行自定义代码（与网页端 new Function 契约一致）。
     * 每次调用新建 context、用完即销毁，全程在调用线程内，无线程亲和问题。
     * 失败返回 null，调用方降级。
     */
    private fun runCustomJs(type: String?, code: String, content: String): String? {
        if (quickJsReady == false) return null
        var ctx: QuickJSContext? = null
        return try {
            if (quickJsReady == null) {
                quickJsReady = try {
                    QuickJSLoader.init()
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "QuickJS 初始化失败: ${e.message}")
                    false
                }
            }
            if (quickJsReady != true) return null
            ctx = QuickJSContext.create()
            // 对齐网页端：先剥掉 TS 类型注解，再调用约定的函数名
            val js = stripTsAnnotations(code) +
                "\nfilterAdsFromM3U8(${JSONObject.quote(type ?: "")}, ${JSONObject.quote(content)});"
            val result = ctx.evaluate(js)?.toString()
            result?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "自定义去广告 JS 执行失败: ${e.message}")
            null
        } finally {
            try {
                ctx?.destroy()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 剥掉 TS 类型注解（与网页端 play/page.tsx 的三条 replace 等价），转成纯 JS。
     * 注意 Kotlin 替换串里的 $ 必须转义。
     */
    private fun stripTsAnnotations(code: String): String {
        var s = code
        s = Regex("""(\w+)\s*:\s*(string|number|boolean|any|void|never|unknown|object)\s*([,)])""")
            .replace(s, "\$1\$3")
        s = Regex("""\)\s*:\s*(string|number|boolean|any|void|never|unknown|object)\s*\{""")
            .replace(s, ") {")
        s = Regex("""(const|let|var)\s+(\w+)\s*:\s*(string|number|boolean|any|void|never|unknown|object)\s*=""")
            .replace(s, "\$1 \$2 =")
        return s
    }

    /** 内置规则：广告块指纹 → 关键字。纯函数。 */
    private fun filterBuiltin(content: String): String {
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
        if (removed > 0) Log.i(TAG, "内置规则移除 $removed 行（广告块 $removedBlocks，关键字 $removedKeywords）")
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
