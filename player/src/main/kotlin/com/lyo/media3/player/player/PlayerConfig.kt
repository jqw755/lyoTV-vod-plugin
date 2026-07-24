package com.lyo.media3.player.player

/**
 * Lyo-Media3Player 播放配置。
 *
 * 一个组件实例对应一份配置；replaceSource() 后整体替换为新实例，
 * 不残留旧 src / headers（见 docs/android-media3-nvue-player-refactor-plan.md §7.1、§9.2）。
 *
 * 所有时间统一为毫秒，与对外契约一致。
 */
data class PlayerConfig(
    /** 播放模式：VOD 或 LIVE */
    val mode: Mode = Mode.VOD,
    /** 当前播放地址 */
    val src: String = "",
    /** 请求头：UA / Referer / Cookie 等，按当前播放源为粒度；replaceSource 后整体替换 */
    val headers: Map<String, String> = emptyMap(),
    /** 标题（用于全屏顶部） */
    val title: String = "",
    /** 点播封面 URL */
    val poster: String = "",
    /** 是否自动播放 */
    val autoplay: Boolean = false,
    /** 是否静音 */
    val muted: Boolean = false,
    /** 点播起播位置（毫秒） */
    val startPositionMs: Long = 0L,
    /** 点播速度 0.5~3 */
    val speed: Float = 1f,
) {
    enum class Mode { VOD, LIVE }

    companion object {
        /** 直播同地址自动重试次数上限（达到此值后发出 SOURCE_FAILED，见 §9.4） */
        const val LIVE_MAX_RETRY = 2

        /** 直播卡顿超时（毫秒）：超过此时间内仍处于 buffering，认定为卡顿，触发业务换源（见 §9.5） */
        const val LIVE_STALL_TIMEOUT_MS = 15_000L

        /** 直播定期检查卡顿的轮询间隔（毫秒） */
        const val LIVE_STALL_CHECK_INTERVAL_MS = 1_000L

        /** 进度上报节流间隔（毫秒） */
        const val PROGRESS_REPORT_INTERVAL_MS = 500L
    }
}
