package org.moontechlab.selenetv.service

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 包在真实 DataSource 外层的去广告代理。
 *
 * 仅对 m3u8 playlist（URI 以 .m3u8 结尾，或 Content-Type 含 mpegurl）做文本层过滤；
 * ts 分片、mp4、DASH 等一律原样透传。
 * 同时覆盖 master playlist 与 media playlist（与网页端 hls-loader 拦截 manifest/level 两级语义一致）。
 *
 * 注意：open() 跑在 ExoPlayer loader 线程，过滤函数必须线程安全（AdFilterService 是纯函数）。
 */
@OptIn(UnstableApi::class)
class AdFilteringDataSource(
    private val delegate: DataSource,
) : DataSource {

    companion object {
        private const val TAG = "AdFilter"
        /** playlist 文本体积上限（正常 m3u8 只有几 KB）；超过则视为病态，直接透传。 */
        private const val MAX_PLAYLIST_BYTES = 4 * 1024 * 1024
    }

    private var filteredStream: ByteArrayInputStream? = null
    private var currentDataSpec: DataSpec? = null

    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        currentDataSpec = dataSpec
        val length = delegate.open(dataSpec)
        filteredStream = null

        if (!AdFilterService.isEnabled()) return length
        if (!isPlaylist(dataSpec)) return length
        if (length != C.LENGTH_UNSET && length > MAX_PLAYLIST_BYTES) return length

        val raw = readFully(delegate)
        val text = raw.toString(Charsets.UTF_8)
        val filtered = AdFilterService.filter(AdFilterService.activeSourceType, text)
        val bytes = filtered.toByteArray(Charsets.UTF_8)
        filteredStream = ByteArrayInputStream(bytes)
        if (bytes.size != raw.size) {
            Log.d(TAG, "playlist 已过滤: ${dataSpec.uri} (${raw.size} -> ${bytes.size} bytes)")
        }
        return bytes.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val stream = filteredStream
        return if (stream != null) {
            val n = stream.read(buffer, offset, length)
            if (n == -1) C.RESULT_END_OF_INPUT else n
        } else {
            delegate.read(buffer, offset, length)
        }
    }

    override fun getUri(): Uri? = currentDataSpec?.uri ?: delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    override fun close() {
        filteredStream?.close()
        filteredStream = null
        currentDataSpec = null
        delegate.close()
    }

    private fun isPlaylist(dataSpec: DataSpec): Boolean {
        if (dataSpec.uri.toString().substringBefore('?').endsWith(".m3u8", ignoreCase = true)) return true
        // Content-Type 头名大小写不统一（HTTP/2 下常为小写），忽略大小写匹配
        val contentType = delegate.responseHeaders.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.firstOrNull().orEmpty()
        return contentType.contains("mpegurl", ignoreCase = true)
    }

    @Throws(IOException::class)
    private fun readFully(source: DataSource): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = source.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            total += n
            if (total > MAX_PLAYLIST_BYTES) throw IOException("playlist 超过 $MAX_PLAYLIST_BYTES 字节上限")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
