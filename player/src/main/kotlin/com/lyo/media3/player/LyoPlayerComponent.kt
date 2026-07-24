package com.lyo.media3.player

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.lyo.media3.player.control.GestureController
import com.lyo.media3.player.control.LiveControllerView
import com.lyo.media3.player.control.VodControllerView
import com.lyo.media3.player.player.PlayerConfig
import com.lyo.media3.player.player.PlayerErrorMapper
import com.lyo.media3.player.player.PlayerManager
import com.lyo.media3.player.player.LyoPlayerView
import io.dcloud.feature.uniapp.UniSDKInstance
import io.dcloud.feature.uniapp.annotation.UniJSMethod
import io.dcloud.feature.uniapp.ui.action.AbsComponentData
import io.dcloud.feature.uniapp.ui.component.AbsVContainer
import io.dcloud.feature.uniapp.ui.component.UniComponent
import io.dcloud.feature.uniapp.ui.component.UniComponentProp

/**
 * Lyo-Media3Player nvue 原生组件入口。
 *
 * 在 nvue 页面中以 `<lyo-player :src="..." :headers="..." mode="vod" />` 形式使用，
 * 对外契约见 docs/android-media3-nvue-player-refactor-plan.md §7。
 *
 * 设计要点：
 * - 一个组件实例对应一个 [PlayerManager] -> 一个 ExoPlayer 实例（§9.1）。
 * - replaceSource() 复用组件实例，不依赖 nvue key 强制重建（§7.2 / §9.3）。
 * - 时间统一毫秒；headers 在 replaceSource 后整体替换，不残留旧源的头（§9.2）。
 * - 全屏切换复用同一 player 实例；返回键优先退出全屏（§11）。
 * - 事件统一通过 fireEvent(name, {detail: {...}})，详见 §7.3。
 *
 * 业务边界：
 * - 组件只负责播放传入 URL/Header；不允许把 Fongmi 解析或站源业务耦合进来（§4）。
 * - 频道分组/选集/线路/收藏/历史等业务由 nvue 页面负责。
 */
class LyoPlayerComponent(
    instance: UniSDKInstance,
    parent: AbsVContainer<*>,
    basicComponentData: AbsComponentData<*>,
) : UniComponent<LyoPlayerView>(instance, parent, basicComponentData) {

    private lateinit var hostView: LyoPlayerView
    private lateinit var manager: PlayerManager

    /** 点播 / 直播控制层 */
    private var vodController: VodControllerView? = null
    private var liveController: LiveControllerView? = null

    /** 手势：双击快退 / 快进、长按临时倍速 */
    private var gesture: GestureController? = null

    /** 当前 mode（决定控制层渲染） */
    private var currentMode: PlayerConfig.Mode = PlayerConfig.Mode.VOD

    /** 当前是否全屏 */
    private var isFullscreen = false

    /** 用户上一次显式设置的 speed（用于长按临时倍速后恢复） */
    private var lastUserSpeed: Float = 1f

    /**
     * 累积的属性值。
     * nvue 按属性声明顺序依次调用各 @UniComponentProp setter；若每个 setter 用 buildConfig()
     * 仅设置自己那一个字段、其余取默认值（空串 / 空 map），后到的 setter 会把先到的 src 覆盖成空值，
     * 导致 manager.applyConfig() 拿到空 src 去 prepare 空地址 -> 黑屏无流量（§9.2 回归）。
     * 这里累积全部 prop，每次 setter 更新自己的字段后用 [applyProps] 重建完整 config。
     */
    private var propSrc: String = ""
    private var propHeaders: Map<String, String> = emptyMap()
    private var propTitle: String = ""
    private var propPoster: String = ""
    private var propAutoplay: Boolean = false
    private var propMuted: Boolean = false
    private var propStartPositionMs: Long = 0L

    // ===== 宿主 View 初始化 =====

    override fun initComponentHostView(context: Context): LyoPlayerView {
        hostView = LyoPlayerView(context).also { view ->
            view.applyEmbedSize()
        }

        manager = PlayerManager(
            context = context,
            onErrorListener = ::onError,
            onStateListener = ::onStateChanged,
            onProgressListener = ::onProgress,
            onFirstFrameListener = ::onFirstFrame,
        )

        // 默认 VOD 控制层（mode 属性变更后切换）
        ensureController(PlayerConfig.Mode.VOD)

        // 手势
        gesture = GestureController(
            view = hostView,
            onLongPressSpeedStart = { speed ->
                // 长按临时倍速：保存当前 speed，应用临时 speed
                lastUserSpeed = manager.getState()["speed"] as? Float ?: 1f
                manager.setSpeed(speed)
                fireLongPressSpeed(speed, true)
            },
            onLongPressSpeedEnd = {
                manager.setSpeed(lastUserSpeed)
                fireLongPressSpeed(lastUserSpeed, false)
            },
            onDoubleTapTogglePlay = {
                val isPlaying = manager.getState()["isPlaying"] == true
                if (isPlaying) manager.pause() else manager.play()
            },
            onSingleTap = {
                // 单击：切换控制层显隐
                vodController?.toggleVisible() ?: liveController?.toggleVisible()
            },
        )

        return hostView
    }

    // ===== 属性（@UniComponentProp，§7.1） =====

    @UniComponentProp(name = "mode")
    fun setModeProp(mode: String) {
        val m = when (mode.lowercase()) {
            "live" -> PlayerConfig.Mode.LIVE
            else -> PlayerConfig.Mode.VOD
        }
        if (m != currentMode) {
            currentMode = m
            ensureController(m)
        }
    }

    @UniComponentProp(name = "src")
    fun setSrcProp(src: String) {
        propSrc = src
        applyProps()
    }

    @UniComponentProp(name = "headers")
    fun setHeadersProp(headers: JSONObject) {
        propHeaders = parseHeaders(headers)
        applyProps()
    }

    @UniComponentProp(name = "title")
    fun setTitleProp(title: String) {
        propTitle = title
        vodController?.setTitle(title)
        liveController?.setTitle(title)
        applyProps()
    }

    @UniComponentProp(name = "poster")
    fun setPosterProp(poster: String) {
        propPoster = poster
        applyProps()
    }

    @UniComponentProp(name = "autoplay")
    fun setAutoplayProp(autoplay: Boolean) {
        propAutoplay = autoplay
        applyProps()
    }

    @UniComponentProp(name = "muted")
    fun setMutedProp(muted: Boolean) {
        propMuted = muted
        manager.setMuted(muted)
        vodController?.setMuted(muted)
        liveController?.setMuted(muted)
        applyProps()
    }

    @UniComponentProp(name = "startPosition")
    fun setStartPositionProp(positionMs: Long) {
        propStartPositionMs = positionMs
        applyProps()
    }

    @UniComponentProp(name = "speed")
    fun setSpeedProp(speed: Float) {
        lastUserSpeed = speed
        manager.setSpeed(speed)
        vodController?.setSpeed(speed)
    }

    // ===== 方法（@UniJSMethod，§7.2） =====

    @UniJSMethod(uiThread = true)
    fun play() = manager.play()

    @UniJSMethod(uiThread = true)
    fun pause() = manager.pause()

    @UniJSMethod(uiThread = true)
    fun stop() = manager.stop()

    @UniJSMethod(uiThread = true)
    fun seekTo(options: JSONObject) {
        // 兼容直接传 number 与 {positionMs: n}
        val positionMs = options.getByPath("positionMs") as? Long
            ?: options.getLong("positionMs")
            ?: 0L
        manager.seekTo(positionMs)
    }

    @UniJSMethod(uiThread = true)
    fun setSpeed(options: JSONObject) {
        val speed = options.getFloat("speed") ?: 1f
        lastUserSpeed = speed
        manager.setSpeed(speed)
        vodController?.setSpeed(speed)
    }

    @UniJSMethod(uiThread = true)
    fun setMuted(options: JSONObject) {
        val muted = options.getBooleanValue("muted")
        manager.setMuted(muted)
        vodController?.setMuted(muted)
        liveController?.setMuted(muted)
    }

    /**
     * 换源（§7.2 / §9.3）：
     * - 复用组件实例，不依赖 nvue key 强制重建。
     * - src / headers / position / autoplay 整体替换，旧 headers 不残留。
     * - 直播换线路传 position: 0。
     */
    @UniJSMethod(uiThread = true)
    fun replaceSource(options: JSONObject) {
        val src = options.getString("src") ?: ""
        val headers = parseHeaders(options.getJSONObject("headers"))
        val position = options.getLong("position") ?: 0L
        val autoplay = if (options.containsKey("autoplay")) options.getBooleanValue("autoplay") else true

        // 同步累积 prop，避免后续某个 @UniComponentProp setter 再用旧值把 src 覆盖回去
        propSrc = src
        propHeaders = headers
        options.getString("title")?.let { propTitle = it }
        options.getString("poster")?.let { propPoster = it }
        propAutoplay = autoplay
        if (options.containsKey("muted")) propMuted = options.getBooleanValue("muted")
        propStartPositionMs = position

        val config = PlayerConfig(
            mode = currentMode,
            src = src,
            headers = headers,
            title = propTitle,
            poster = propPoster,
            autoplay = autoplay,
            muted = propMuted,
            startPositionMs = position,
            speed = lastUserSpeed,
        )
        manager.applyConfig(config)
    }

    @UniJSMethod(uiThread = true)
    fun retry() = manager.retry()

    @UniJSMethod(uiThread = true)
    fun goLiveEdge() = manager.goLiveEdge()

    @UniJSMethod(uiThread = true)
    fun requestFullscreen() {
        // 通过事件通知 nvue 业务层进入全屏状态：业务层控制下方内容隐藏、横屏请求
        // 实际把 PlayerView 复用 attach 到全屏容器的工作由业务层配合组件完成（§11）
        isFullscreen = true
        vodController?.setFullscreen(true)
        liveController?.setFullscreen(true)
        fireEvent("fullscreenchange", mapOf("detail" to mapOf("isFullscreen" to true)))
    }

    @UniJSMethod(uiThread = true)
    fun exitFullscreen() {
        isFullscreen = false
        vodController?.setFullscreen(false)
        liveController?.setFullscreen(false)
        fireEvent("fullscreenchange", mapOf("detail" to mapOf("isFullscreen" to false)))
    }

    @UniJSMethod(uiThread = true)
    fun getState(jsCallback: io.dcloud.feature.uniapp.bridge.UniJSCallback) {
        val state = manager.getState()
        val result = JSONObject()
        state.forEach { (k, v) -> result[k] = v }
        jsCallback.invoke(result)
    }

    /** 释放：必须幂等，多次调用不崩溃（§7.2） */
    @UniJSMethod(uiThread = true)
    fun release() {
        manager.release()
    }

    // ===== 生命周期回调 =====

    override fun onActivityResume() {
        super.onActivityResume()
        manager.play()
    }

    override fun onActivityPause() {
        super.onActivityPause()
        // 页面不可见时由页面明确调用暂停，不允许无控制后台播放（§9.1）
        manager.pause()
    }

    override fun onActivityDestroy() {
        super.onActivityDestroy()
        manager.release()
    }

    // ===== 内部：配置构造 / 控制层管理 / 事件转发 =====

    /** 基于当前 mode 与传入字段构造一份完整 PlayerConfig；其他字段保留默认（不残留旧值） */
    private fun buildConfig(
        src: String = "",
        headers: Map<String, String> = emptyMap(),
        title: String = "",
        poster: String = "",
        autoplay: Boolean = false,
        muted: Boolean = false,
        startPositionMs: Long = 0L,
        speed: Float = 1f,
    ): PlayerConfig {
        return PlayerConfig(
            mode = currentMode,
            src = src,
            headers = headers,
            title = title,
            poster = poster,
            autoplay = autoplay,
            muted = muted,
            startPositionMs = startPositionMs,
            speed = speed,
        )
    }

    /** 把当前最新属性合并到 manager：实际由调用方提供完整 src+headers 后才生效 */
    private fun applyConfig(config: PlayerConfig) {
        manager.applyConfig(config)
    }

    /**
     * 用累积的全部 prop 重建一份完整 PlayerConfig 并下发。
     * 仅当 src 非空时才真正下发，避免初始化阶段（src 尚未到达）用空地址 prepare 导致黑屏。
     */
    private fun applyProps() {
        if (propSrc.isEmpty()) return
        val config = PlayerConfig(
            mode = currentMode,
            src = propSrc,
            headers = propHeaders,
            title = propTitle,
            poster = propPoster,
            autoplay = propAutoplay,
            muted = propMuted,
            startPositionMs = propStartPositionMs,
            speed = lastUserSpeed,
        )
        manager.applyConfig(config)
    }

    /** 切换控制层（仅一种生效），全屏切换前后都使用同一个 playerView */
    private fun ensureController(mode: PlayerConfig.Mode) {
        vodController?.removeFromParent()
        liveController?.removeFromParent()

        when (mode) {
            PlayerConfig.Mode.VOD -> {
                if (vodController == null) {
                    vodController = VodControllerView(
                        context = getContext(),
                        hostView = hostView,
                        onPlayToggle = { manager.play() },
                        onPauseToggle = { manager.pause() },
                        onSeekTo = { ms -> manager.seekTo(ms) },
                        onSpeedChange = { s ->
                            lastUserSpeed = s
                            manager.setSpeed(s)
                        },
                        onPrevEpisode = { fireEvent("prevEpisode", mapOf("detail" to emptyMap<String, Any>())) },
                        onNextEpisode = { fireEvent("nextEpisode", mapOf("detail" to emptyMap<String, Any>())) },
                        onMuteToggle = { m ->
                            manager.setMuted(m)
                            fireMuteChange(m)
                        },
                        onFullscreenToggle = { fs ->
                            if (fs) requestFullscreen() else exitFullscreen()
                        },
                    )
                }
                vodController?.attachTo(hostView)
            }
            PlayerConfig.Mode.LIVE -> {
                if (liveController == null) {
                    liveController = LiveControllerView(
                        context = getContext(),
                        hostView = hostView,
                        onPlayToggle = { manager.play() },
                        onPauseToggle = { manager.pause() },
                        onMuteToggle = { m ->
                            manager.setMuted(m)
                            fireMuteChange(m)
                        },
                        onFullscreenToggle = { fs ->
                            if (fs) requestFullscreen() else exitFullscreen()
                        },
                        onGoLiveEdge = { manager.goLiveEdge() },
                    )
                }
                liveController?.attachTo(hostView)
            }
        }
    }

    private fun onError(err: PlayerErrorMapper.MappedError) {
        val detail = mapOf(
            "code" to err.code,
            "message" to err.message,
            "retryable" to err.retryable,
            "nativeCode" to err.nativeCode,
        )
        fireEvent("error", mapOf("detail" to detail))
    }

    private fun onStateChanged(state: PlayerManager.State) {
        when (state) {
            PlayerManager.State.IDLE -> Unit
            PlayerManager.State.READY -> fireEvent("ready", mapOf("detail" to emptyMap<String, Any>()))
            PlayerManager.State.PLAYING -> fireEvent("play", mapOf("detail" to mapOf("isPlaying" to true)))
            PlayerManager.State.PAUSED -> fireEvent("pause", mapOf("detail" to mapOf("isPlaying" to false)))
            PlayerManager.State.BUFFERING -> fireEvent("buffering", mapOf("detail" to mapOf("isBuffering" to true)))
            // READY 之外的"缓冲结束"信号：从 BUFFERING 进入 PLAYING/PAUSED 时补发 buffered
            PlayerManager.State.ENDED -> fireEvent("ended", mapOf("detail" to emptyMap<String, Any>()))
        }
        // 缓冲结束（进入 PLAYING 或 PAUSED）补发 buffered 事件（§7.3）
        if (state == PlayerManager.State.PLAYING || state == PlayerManager.State.PAUSED) {
            fireEvent("buffered", mapOf("detail" to mapOf("isBuffering" to false)))
        }
    }

    private fun onProgress(positionMs: Long, durationMs: Long, bufferedMs: Long) {
        val detail = mapOf(
            "position" to positionMs,
            "duration" to durationMs,
            "bufferedPosition" to bufferedMs,
            "isPlaying" to (manager.getState()["isPlaying"] == true),
            "isLive" to (manager.getState()["isLive"] == true),
        )
        fireEvent("progress", mapOf("detail" to detail))
    }

    private fun onFirstFrame() {
        fireEvent("firstframe", mapOf("detail" to emptyMap<String, Any>()))
    }

    private fun fireLongPressSpeed(speed: Float, active: Boolean) {
        val detail = mapOf(
            "speed" to speed,
            "active" to active,
        )
        fireEvent("longpressspeed", mapOf("detail" to detail))
    }

    /** 用户在播放器原生控制层点击静音按钮时，回传新的静音状态给 JS */
    private fun fireMuteChange(muted: Boolean) {
        fireEvent("mutechange", mapOf("detail" to mapOf("muted" to muted)))
    }

    // ===== 工具：headers 解析 =====

    /** 把 fastjson JSONObject 解析成 Map<String, String>；空对象返回空 Map */
    private fun parseHeaders(headers: JSONObject?): Map<String, String> {
        if (headers == null || headers.isEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (key in headers.keys) {
            val v = headers[key] ?: continue
            map[key] = v.toString()
        }
        return map
    }

    /** View 移除自己（避免控制层之间叠加） */
    private fun View.removeFromParent() {
        (parent as? ViewGroup)?.removeView(this)
    }

    /** fastjson 路径取值（支持 a.b.c） */
    private fun Any.getByPath(path: String): Any? {
        if (path.isEmpty()) return this
        if (this !is JSONObject) return null
        val parts = path.split(".")
        var cur: Any? = this
        for (p in parts) {
            cur = (cur as? JSONObject)?.get(p)
            if (cur == null) return null
        }
        return cur
    }
}
