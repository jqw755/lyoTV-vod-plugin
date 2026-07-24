package com.lyo.media3.player.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.lyo.media3.player.datasource.MutableHeaderDataSourceFactory
import com.lyo.media3.player.network.NetworkMonitor

/**
 * ExoPlayer 实例持有与生命周期管理。
 *
 * 设计要点（见 docs/android-media3-nvue-player-refactor-plan.md §9）：
 *
 * 1. 一个组件实例对应一个 ExoPlayer 实例。横竖屏 / 全屏切换不重建 player，
 *    只切换 [androidx.media3.ui.PlayerView] 的 attach 关系。
 * 2. 页面销毁时必须调用 [release]，释放 player、网络监听和定时任务。
 * 3. replaceSource 复用同一 player 实例：先 stop 再 setMediaItem + prepare，
 *    同时整体替换 headers（不残留旧源的头），不依赖 nvue key 强制重建。
 * 4. 直播同地址有限次自动重试：达到上限后通过 [onErrorListener] 通知 SOURCE_FAILED，
 *    由业务层换线。
 * 5. 直播卡顿监控：在 buffering 状态下定时检查，超过阈值触发业务换源。
 * 6. 网络恢复后重试：通过 [NetworkMonitor] 监听可用性，重新 prepare。
 * 7. 缓冲参数使用 Media3 默认 LoadControl；只在真实样本证明不满足要求时调整。
 *
 * 该类在主线程调用，事件回调通过 [onErrorListener] / [onStateListener] / [onProgressListener] 传回组件。
 */
class PlayerManager(
    private val context: Context,
    private val onErrorListener: (PlayerErrorMapper.MappedError) -> Unit,
    private val onStateListener: (State) -> Unit,
    private val onProgressListener: (Long, Long, Long) -> Unit,
    private val onFirstFrameListener: () -> Unit,
) {

    /** 播放状态枚举，对应对外契约（§7.3） */
    enum class State {
        IDLE, READY, PLAYING, PAUSED, BUFFERING, ENDED
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 可变 headers 引用，replaceSource 时整体替换 */
    private val headerFactory = MutableHeaderDataSourceFactory()

    /** 当前配置（含 src / headers / mode / position 等），replaceSource 时整体替换 */
    @Volatile
    private var current: PlayerConfig = PlayerConfig()

    /** ExoPlayer 实例；懒初始化，避免组件构造阶段触发 native 资源分配 */
    private var player: ExoPlayer? = null

    /** 直播同地址自动重试计数器 */
    private var liveRetryCount = 0

    /** 当前 URL（用于判断同地址重试） */
    private var liveCurrentUrl = ""

    /** 直播卡顿检查定时器 */
    private var stallCheckRunnable: Runnable? = null

    /** 直播进入 buffering 的时间戳（用于卡顿超时判定） */
    private var liveBufferingSinceMs = 0L

    /** 进度上报定时器 */
    private var progressRunnable: Runnable? = null

    /** 首帧是否已上报 */
    private var firstFrameSent = false

    /** 网络监听 */
    private var networkMonitor: NetworkMonitor? = null

    /** 是否已 release（幂等保护） */
    private var released = false

    /** 当前播放位置（毫秒），用于网络恢复后恢复播放 */
    private var lastKnownPositionMs: Long = 0L

    // ===== 生命周期 =====

    /** 确保 ExoPlayer 已创建；幂等 */
    @Synchronized
    fun ensurePlayer() {
        if (released) return
        if (player != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val loadControl: LoadControl = DefaultLoadControl.Builder()
            // 默认 LoadControl（§9.6）；不臆造"最佳参数"
            .build()

        val mediaSourceFactory: MediaSource.Factory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(headerFactory)

        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(attrs, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.addListener(playerListener)
        player = p

        // 网络监听：网络恢复后重新 prepare
        if (networkMonitor == null) {
            networkMonitor = NetworkMonitor(context, ::onNetworkAvailable)
        }
        networkMonitor?.start()
        startProgressReport()
    }

    /** 应用一份新配置（首次或 replaceSource），不重建 player 实例 */
    @Synchronized
    fun applyConfig(config: PlayerConfig) {
        if (released) return
        ensurePlayer()
        val p = player ?: return

        // 同地址直播重试计数：换 URL 重置
        if (config.mode == PlayerConfig.Mode.LIVE) {
            if (config.src != liveCurrentUrl) {
                liveCurrentUrl = config.src
                liveRetryCount = 0
            }
        } else {
            liveCurrentUrl = ""
            liveRetryCount = 0
        }

        // 整体替换 headers（§9.2：不残留旧源的头）
        headerFactory.setHeaders(config.headers)

        current = config

        val mediaItem = MediaItem.Builder()
            .setUri(config.src)
            .apply {
                if (config.poster.isNotEmpty()) {
                    setMediaMetadata(
                        MediaMetadata.Builder()
                            .setArtworkUri(android.net.Uri.parse(config.poster))
                            .setTitle(config.title)
                            .build()
                    )
                } else if (config.title.isNotEmpty()) {
                    setMediaMetadata(MediaMetadata.Builder().setTitle(config.title).build())
                }
            }
            .build()

        p.setMediaItem(mediaItem, config.startPositionMs.coerceAtLeast(0))

        // 速度（点播）
        if (config.mode == PlayerConfig.Mode.VOD) {
            p.setPlaybackSpeed(config.speed.coerceIn(0.5f, 3f))
        }

        // 静音
        p.volume = if (config.muted) 0f else 1f

        // 起播位置：setMediaItem 已传入 startPosition；prepare 后由播放器跳转
        firstFrameSent = false
        p.prepare()

        if (config.autoplay) {
            p.play()
        } else {
            p.pause()
        }
    }

    // ===== 对外方法（@UniJSMethod 直接转调这里） =====

    fun play() {
        if (released) return
        ensurePlayer()
        player?.play()
    }

    fun pause() {
        if (released) return
        player?.pause()
    }

    fun stop() {
        if (released) return
        player?.stop()
    }

    fun seekTo(positionMs: Long) {
        if (released) return
        val p = player ?: return
        val target = positionMs.coerceAtLeast(0L)
        // 位置超过新源时长时由播放器限制到有效范围（§9.3 第 6 点）
        val duration = p.duration
        val safe = if (duration > 0 && target > duration - 100L) (duration - 100L).coerceAtLeast(0L) else target
        p.seekTo(safe)
    }

    fun setSpeed(speed: Float) {
        if (released) return
        player?.setPlaybackSpeed(speed.coerceIn(0.5f, 3f))
    }

    fun setMuted(muted: Boolean) {
        if (released) return
        player?.volume = if (muted) 0f else 1f
    }

    fun retry() {
        if (released) return
        val p = player ?: return
        // Media3 Player 无 retry() 方法：prepare() 会重新加载当前 MediaItem，等效重试
        p.prepare()
        if (current.autoplay) p.play()
    }

    /** 直播：跳到直播边缘（seek 到 max，触发 Media3 live edge） */
    fun goLiveEdge() {
        if (released) return
        val p = player ?: return
        if (p.isCurrentMediaItemLive) {
            p.seekToDefaultPosition(/* mediaItemIndex = */ 0)
        } else {
            val duration = p.duration
            if (duration > 0) p.seekTo(duration) else p.seekToDefaultPosition()
        }
    }

    /** 获取当前状态快照（用于 getState()） */
    fun getState(): Map<String, Any?> {
        val p = player
        return mapOf(
            "isPlaying" to (p?.isPlaying == true),
            "isLive" to (p?.isCurrentMediaItemLive == true),
            "position" to (p?.currentPosition ?: 0L),
            "duration" to (p?.duration ?: 0L),
            "bufferedPosition" to (p?.bufferedPosition ?: 0L),
            "playbackState" to (p?.playbackState ?: Player.STATE_IDLE),
            "mode" to current.mode.name,
        )
    }

    /** 释放：必须幂等，多次调用不崩溃（§7.2） */
    @Synchronized
    fun release() {
        if (released) return
        released = true
        stopStallCheck()
        stopProgressReport()
        try {
            networkMonitor?.stop()
            networkMonitor = null
        } catch (_: Throwable) {}
        try {
            player?.removeListener(playerListener)
            player?.release()
        } catch (_: Throwable) {}
        player = null
    }

    // ===== 内部事件转发 =====

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val p = player ?: return
            when (playbackState) {
                Player.STATE_IDLE -> onStateListener(State.IDLE)
                Player.STATE_BUFFERING -> {
                    onStateListener(State.BUFFERING)
                    if (current.mode == PlayerConfig.Mode.LIVE) {
                        liveBufferingSinceMs = System.currentTimeMillis()
                        startStallCheck()
                    }
                }
                Player.STATE_READY -> {
                    onStateListener(if (p.isPlaying) State.PLAYING else State.PAUSED)
                    if (current.mode == PlayerConfig.Mode.LIVE) {
                        liveBufferingSinceMs = 0L
                        stopStallCheck()
                    }
                }
                Player.STATE_ENDED -> {
                    if (current.mode == PlayerConfig.Mode.LIVE) {
                        stopStallCheck()
                    }
                    onStateListener(State.ENDED)
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val p = player ?: return
            if (p.playbackState == Player.STATE_READY) {
                onStateListener(if (isPlaying) State.PLAYING else State.PAUSED)
            }
        }

        override fun onPlayerErrorChanged(error: PlaybackException?) {
            if (error == null) return
            val mapped = PlayerErrorMapper.map(error)
            // 直播同地址重试：达到上限才通知业务层换源（§9.4）
            if (current.mode == PlayerConfig.Mode.LIVE &&
                mapped.retryable &&
                liveRetryCount < PlayerConfig.LIVE_MAX_RETRY
            ) {
                liveRetryCount++
                // 重试前延迟 800ms，给网络一个缓冲窗口
                mainHandler.postDelayed({
                    if (!released) {
                        retry()
                    }
                }, 800L)
                return
            }
            onErrorListener(mapped)
        }

        override fun onRenderedFirstFrame() {
            if (firstFrameSent) return
            firstFrameSent = true
            onFirstFrameListener()
        }
    }

    // ===== 进度上报 =====

    private fun startProgressReport() {
        if (progressRunnable != null) return
        val r = object : Runnable {
            override fun run() {
                if (released) return
                val p = player ?: return
                val position = p.currentPosition.coerceAtLeast(0L)
                val duration = p.duration.coerceAtLeast(0L)
                val buffered = p.bufferedPosition.coerceAtLeast(0L)
                lastKnownPositionMs = position
                onProgressListener(position, duration, buffered)
                mainHandler.postDelayed(this, PlayerConfig.PROGRESS_REPORT_INTERVAL_MS)
            }
        }
        progressRunnable = r
        mainHandler.postDelayed(r, PlayerConfig.PROGRESS_REPORT_INTERVAL_MS)
    }

    private fun stopProgressReport() {
        val r = progressRunnable ?: return
        mainHandler.removeCallbacks(r)
        progressRunnable = null
    }

    // ===== 直播卡顿监控（§9.5） =====

    private fun startStallCheck() {
        if (current.mode != PlayerConfig.Mode.LIVE) return
        stopStallCheck()
        val r = object : Runnable {
            override fun run() {
                if (released) return
                val p = player ?: return
                if (p.playbackState != Player.STATE_BUFFERING) {
                    liveBufferingSinceMs = 0L
                    return
                }
                val since = liveBufferingSinceMs
                if (since > 0 && System.currentTimeMillis() - since >= PlayerConfig.LIVE_STALL_TIMEOUT_MS) {
                    // 卡顿超时 -> 业务层换源
                    onErrorListener(
                        PlayerErrorMapper.MappedError(
                            code = PlayerErrorMapper.CODE_SOURCE_FAILED,
                            message = "直播卡顿，建议切换线路",
                            retryable = false,
                            nativeCode = 0,
                        )
                    )
                    liveBufferingSinceMs = 0L
                    return
                }
                mainHandler.postDelayed(this, PlayerConfig.LIVE_STALL_CHECK_INTERVAL_MS)
            }
        }
        stallCheckRunnable = r
        mainHandler.postDelayed(r, PlayerConfig.LIVE_STALL_CHECK_INTERVAL_MS)
    }

    private fun stopStallCheck() {
        val r = stallCheckRunnable ?: return
        mainHandler.removeCallbacks(r)
        stallCheckRunnable = null
        liveBufferingSinceMs = 0L
    }

    // ===== 网络恢复回调 =====

    private fun onNetworkAvailable() {
        if (released) return
        val p = player ?: return
        // 网络恢复后重新 prepare（§ 直播：网络恢复后重新播放）
        mainHandler.post {
            if (released) return@post
            try {
                if (current.mode == PlayerConfig.Mode.LIVE) {
                    // 直播：重新 prepare 当前 mediaItem
                    p.prepare()
                    if (current.autoplay) p.play()
                } else if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
                    p.prepare()
                    if (current.autoplay) p.play()
                } else if (lastKnownPositionMs > 0) {
                    p.seekTo(lastKnownPositionMs)
                    if (current.autoplay) p.play()
                }
            } catch (_: Throwable) {}
        }
    }
}
