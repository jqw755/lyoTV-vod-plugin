package com.lyo.media3.player.player

import androidx.media3.common.PlaybackException

/**
 * 把 Media3 PlaybackException 映射到对外错误码契约
 * （docs/android-media3-nvue-player-refactor-plan.md §7.3）。
 *
 * 安全约束：用户可见消息中不得包含完整 URL、Cookie、Token、签名播放地址。
 */
object PlayerErrorMapper {

    /** 对外错误码常量 */
    const val CODE_INVALID_SOURCE = "INVALID_SOURCE"
    const val CODE_NETWORK_TIMEOUT = "NETWORK_TIMEOUT"
    const val CODE_HTTP_ERROR = "HTTP_ERROR"
    const val CODE_MANIFEST_ERROR = "MANIFEST_ERROR"
    const val CODE_DECODER_ERROR = "DECODER_ERROR"
    const val CODE_SOURCE_FAILED = "SOURCE_FAILED"
    const val CODE_UNKNOWN = "UNKNOWN"

    data class MappedError(
        val code: String,
        val message: String,
        val retryable: Boolean,
        val nativeCode: Int,
    )

    fun map(e: Throwable?): MappedError {
        if (e == null) return MappedError(CODE_UNKNOWN, "未知错误", false, 0)

        if (e !is PlaybackException) {
            return MappedError(CODE_UNKNOWN, sanitizeMessage(e.message ?: "未知错误"), true, 0)
        }

        val code = e.errorCode
        return when (code) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                MappedError(CODE_NETWORK_TIMEOUT, "网络连接超时", true, code)

            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
                MappedError(CODE_HTTP_ERROR, "服务器返回错误", true, code)

            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                MappedError(CODE_MANIFEST_ERROR, "播放列表解析失败", false, code)

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                MappedError(CODE_DECODER_ERROR, "硬件解码失败，当前视频编码不被支持", false, code)

            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
                MappedError(CODE_SOURCE_FAILED, "播放源连接失败", true, code)

            else -> MappedError(CODE_UNKNOWN, sanitizeMessage(e.message ?: "未知错误"), true, code)
        }
    }

    /** 移除消息中可能包含的 URL、Cookie、Authorization 头值，避免敏感信息泄露到用户可见日志 */
    private fun sanitizeMessage(msg: String): String {
        var s = msg
        s = Regex("https?://[^\\s,]+").replace(s, "<url>")
        s = Regex("(?i)(cookie|authorization|referer|token)\\s*[:=]\\s*[^\\s,]+").replace(s, "<redacted>")
        if (s.length > 80) s = s.substring(0, 80) + "..."
        return s
    }
}
