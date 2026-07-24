package com.lyo.media3.player.datasource

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource

/**
 * 自定义 HTTP DataSource.Factory：把 headers 透传到所有 HTTP 请求，
 * 覆盖 HLS 主播放列表、子播放列表、TS/fMP4 分片、AES Key 与 HTTP 重定向后的请求
 * （docs/android-media3-nvue-player-refactor-plan.md §9.2）。
 *
 * 实现说明：
 * - DefaultHttpDataSource 在每次 open() 时对底层 HttpURLConnection 调用 setRequestProperty，
 *   包括 30x 重定向后产生的新连接。setDefaultRequestProperties 把指定头应用到每个请求。
 * - setAllowCrossProtocolRedirects(true) 允许 http<->https 切换，避免被某些 CDN 拒绝。
 *
 * 因 ExoPlayer 在 Builder 阶段固定了 DataSource.Factory，replaceSource 后需要替换 headers，
 * 用 [MutableHeaderDataSourceFactory] 持有可变 headers 引用，运行期整体替换。
 */
object HeaderDataSourceFactory {

    /** 创建一次性 [DataSource.Factory]：headers 固定，适合不需要换源的场景 */
    fun create(headers: Map<String, String>): DataSource.Factory {
        return DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .apply {
                if (headers.isNotEmpty()) setDefaultRequestProperties(HashMap(headers))
            }
    }
}

/**
 * 可变 headers 的 DataSource.Factory：
 * ExoPlayer 实例固定后，replaceSource 时调用 [setHeaders] 整体替换 headers，
 * 旧源的头不会残留到新源（见 §9.2）。
 *
 * 线程安全：[headers] 与内部 [httpFactory] 用 volatile + 同步保护，
 * 因为 ExoPlayer 的加载线程会并发调用 createDataSource()。
 */
class MutableHeaderDataSourceFactory : DataSource.Factory {

    @Volatile
    private var httpFactory: DefaultHttpDataSource.Factory = buildFactory(emptyMap())

    private fun buildFactory(headers: Map<String, String>): DefaultHttpDataSource.Factory {
        return DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .apply {
                if (headers.isNotEmpty()) setDefaultRequestProperties(HashMap(headers))
            }
    }

    /** 替换 headers；下一次 createDataSource() 起生效（已在加载中的 DataSource 不受影响） */
    fun setHeaders(headers: Map<String, String>) {
        httpFactory = buildFactory(headers)
    }

    override fun createDataSource(): DataSource {
        return httpFactory.createDataSource()
    }
}
