package com.lyo.media3.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
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

    private companion object {
        const val TAG = "LyoPlayerComponent"
    }

    private lateinit var hostView: LyoPlayerView
    private lateinit var manager: PlayerManager

    /** 点播 / 直播控制层 */
    private var vodController: VodControllerView? = null
    private var liveController: LiveControllerView? = null

    /** 手势：双击快退 / 快进、长按临时倍速 */
    private var gesture: GestureController? = null
    private var gestureSeekBasePositionMs: Long? = null

    /** 当前 mode（决定控制层渲染） */
    private var currentMode: PlayerConfig.Mode = PlayerConfig.Mode.VOD

    /** 当前是否全屏 */
    private var isFullscreen = false
    private var isControlsLocked = false
    private var fullscreenRotatedToPortrait = false
    private var previousOrientation: Int? = null
    private var previousSystemUiVisibility: Int? = null
    private var hadFullscreenWindowFlag = false
    private var embeddedParent: ViewGroup? = null
    private var embeddedIndex = -1
    private var embeddedLayoutParams: ViewGroup.LayoutParams? = null
    private var fullscreenContainer: FrameLayout? = null
    private var fullscreenBackCallback: OnBackInvokedCallback? = null

    private var audioManager: AudioManager? = null
    private var lastSystemVolume = 0
    private var volumeObserver: ContentObserver? = null

    /** 用户上一次显式设置的 speed（用于长按临时倍速后恢复） */
    private var lastUserSpeed: Float = 1f
    private var longPressSpeed: Float = 2f
    /** 页面/用户暂停后禁止生命周期或重复属性下发擅自恢复播放。 */
    private var playbackResumeBlocked = false

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
    private var propsApplyScheduled = false
    private var hasAppliedSource = false

    // ===== 宿主 View 初始化 =====

    override fun initComponentHostView(context: Context): LyoPlayerView {
        // Host View 的 LayoutParams 必须由 nvue 实际父容器创建和管理。
        // 这里若预先塞入 FrameLayout.LayoutParams，UniApp 父容器测量时会发生
        // LayoutParams 类型转换崩溃。
        hostView = LyoPlayerView(context)

        manager = PlayerManager(
            context = context,
            onErrorListener = ::onError,
            onStateListener = ::onStateChanged,
            onProgressListener = ::onProgress,
            onFirstFrameListener = ::onFirstFrame,
        )
        registerSystemVolumeObserver(context)
        // 必须显式连接 ExoPlayer 与视频输出面；此前二者从未 attach，因而始终黑屏。
        hostView.attachPlayer(manager.getPlayer())

        // Keep playback initialization independent from optional controller UI.
        hostView.post { initControllerAndGestureSafely() }

        return hostView
    }

    private fun initControllerAndGestureSafely() {
        try {
            ensureController(currentMode)
        } catch (t: Throwable) {
            Log.e(TAG, "controller init failed; playback core remains available", t)
        }
        try {
            gesture = GestureController(
                view = hostView,
                onLongPressSpeedStart = { speed ->
                    if (currentMode == PlayerConfig.Mode.VOD) {
                        lastUserSpeed = manager.getState()["speed"] as? Float ?: 1f
                        manager.setSpeed(speed)
                        hostView.showLongPressSpeedHint(speed)
                        fireLongPressSpeed(speed, true)
                    }
                },
                onLongPressSpeedEnd = {
                    if (currentMode == PlayerConfig.Mode.VOD) {
                        manager.setSpeed(lastUserSpeed)
                        hostView.hideLongPressSpeedHint()
                        fireLongPressSpeed(lastUserSpeed, false)
                    }
                },
                getLongPressSpeed = { longPressSpeed },
                onDoubleTapTogglePlay = {
                    val isPlaying = manager.getState()["isPlaying"] == true
                    if (isPlaying) pauseByUser() else playByUser()
                },
                onSingleTap = {
                    when (currentMode) {
                        PlayerConfig.Mode.VOD -> vodController?.toggleVisible()
                        PlayerConfig.Mode.LIVE -> liveController?.toggleVisible()
                    }
                },
                canHorizontalSeek = {
                    !isControlsLocked &&
                    currentMode == PlayerConfig.Mode.VOD &&
                        ((manager.getState()["duration"] as? Long) ?: 0L) > 0L
                },
                canVerticalAdjust = { !isControlsLocked },
                onHorizontalSeekPreview = { deltaMs ->
                    val state = manager.getState()
                    val durationMs = (state["duration"] as? Long) ?: 0L
                    if (durationMs > 0L) {
                        val basePositionMs = gestureSeekBasePositionMs
                            ?: ((state["position"] as? Long) ?: 0L).also {
                                gestureSeekBasePositionMs = it
                            }
                        val targetMs = gestureSeekTarget(basePositionMs, deltaMs, durationMs)
                        hostView.showSeekHint(deltaMs, targetMs)
                    }
                },
                onHorizontalSeekEnd = { deltaMs ->
                    val state = manager.getState()
                    val durationMs = (state["duration"] as? Long) ?: 0L
                    val basePositionMs = gestureSeekBasePositionMs
                        ?: ((state["position"] as? Long) ?: 0L)
                    if (durationMs > 0L) {
                        val targetMs = gestureSeekTarget(basePositionMs, deltaMs, durationMs)
                        seekVodTo(targetMs)
                        // 不等待下一个 500ms 定时上报，确保松手后立即返回也能保存目标进度。
                        val bufferedMs = (state["bufferedPosition"] as? Long) ?: targetMs
                        onProgress(targetMs, durationMs, bufferedMs)
                    }
                    gestureSeekBasePositionMs = null
                    hostView.hideSeekHint()
                },
                onHorizontalSeekCancel = {
                    gestureSeekBasePositionMs = null
                    hostView.hideSeekHint()
                },
                onVolumeAdjusted = { percent ->
                    val muted = percent <= 0
                    if (propMuted != muted) {
                        propMuted = muted
                        manager.setMuted(muted)
                        vodController?.setMuted(muted)
                        liveController?.setMuted(muted)
                        fireMuteChange(muted)
                    }
                },
            )
        } catch (t: Throwable) {
            Log.e(TAG, "gesture init failed; playback core remains available", t)
        }
    }

    // ===== 属性（@UniComponentProp，§7.1） =====

    private fun gestureSeekTarget(baseMs: Long, deltaMs: Long, durationMs: Long): Long {
        val maxPositionMs = (durationMs - 100L).coerceAtLeast(0L)
        return (baseMs + deltaMs).coerceIn(0L, maxPositionMs)
    }

    private fun seekVodTo(positionMs: Long) {
        showVodLoading(currentMode == PlayerConfig.Mode.VOD)
        manager.seekTo(positionMs)
    }

    private fun showVodLoading(show: Boolean) {
        hostView.showBuffering(show)
        vodController?.showLoading(show)
    }

    @UniComponentProp(name = "mode")
    fun setModeProp(mode: String) {
        val m = when (mode.lowercase()) {
            "live" -> PlayerConfig.Mode.LIVE
            else -> PlayerConfig.Mode.VOD
        }
        if (m != currentMode) {
            currentMode = m
            try {
                ensureController(m)
            } catch (t: Throwable) {
                Log.e(TAG, "controller switch failed: $m", t)
            }
            if (hasAppliedSource) scheduleApplyProps()
        }
    }

    @UniComponentProp(name = "src")
    fun setSrcProp(src: String) {
        val changed = propSrc != src
        propSrc = src
        if (!changed) return
        hostView.showShutter()
        if (src.isEmpty()) {
            hasAppliedSource = false
            manager.stop()
        } else {
            scheduleApplyProps()
        }
    }

    @UniComponentProp(name = "headers")
    fun setHeadersProp(headers: JSONObject) {
        val parsed = parseHeaders(headers)
        if (parsed != propHeaders) {
            propHeaders = parsed
            if (propSrc.isNotEmpty()) scheduleApplyProps()
        }
    }

    @UniComponentProp(name = "title")
    fun setTitleProp(title: String) {
        propTitle = title
        vodController?.setTitle(title)
        liveController?.setTitle(title)
    }

    @UniComponentProp(name = "poster")
    fun setPosterProp(poster: String) {
        propPoster = poster
    }

    @UniComponentProp(name = "autoplay")
    fun setAutoplayProp(autoplay: Boolean) {
        propAutoplay = autoplay
        if (hasAppliedSource) {
            if (autoplay && !playbackResumeBlocked) manager.play() else manager.pause()
        }
    }

    @UniComponentProp(name = "muted")
    fun setMutedProp(muted: Boolean) {
        propMuted = muted
        manager.setMuted(muted)
        vodController?.setMuted(muted)
        liveController?.setMuted(muted)
    }

    @UniComponentProp(name = "startPosition")
    fun setStartPositionProp(positionMs: Long) {
        propStartPositionMs = positionMs
        if (hasAppliedSource && positionMs > 0L) seekVodTo(positionMs)
    }

    @UniComponentProp(name = "speed")
    fun setSpeedProp(speed: Float) {
        lastUserSpeed = speed
        manager.setSpeed(speed)
        vodController?.setSpeed(speed)
    }

    @UniComponentProp(name = "longPressSpeed")
    fun setLongPressSpeedProp(speed: Float) {
        longPressSpeed = speed.coerceIn(1f, 4f)
    }

    // ===== 方法（@UniJSMethod，§7.2） =====

    @UniJSMethod(uiThread = true)
    fun play() = playByUser()

    @UniJSMethod(uiThread = true)
    fun pause() = pauseByUser()

    @UniJSMethod(uiThread = true)
    fun stop() {
        playbackResumeBlocked = true
        manager.stop()
    }

    @UniJSMethod(uiThread = true)
    fun seekTo(options: JSONObject) {
        // 兼容直接传 number 与 {positionMs: n}
        val positionMs = options.getByPath("positionMs") as? Long
            ?: options.getLong("positionMs")
            ?: 0L
        seekVodTo(positionMs)
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
        if (src.isBlank()) {
            onError(PlayerErrorMapper.map(IllegalArgumentException("empty media source")))
            return
        }
        val headers = parseHeaders(options.getJSONObject("headers"))
        val position = options.getLong("position") ?: 0L
        val autoplay = if (options.containsKey("autoplay")) options.getBooleanValue("autoplay") else true

        // 同步累积 prop，避免后续某个 @UniComponentProp setter 再用旧值把 src 覆盖回去
        propSrc = src
        propHeaders = headers
        options.getString("title")?.let { propTitle = it }
        options.getString("poster")?.let { propPoster = it }
        propAutoplay = autoplay
        playbackResumeBlocked = !autoplay
        if (options.containsKey("muted")) propMuted = options.getBooleanValue("muted")
        propStartPositionMs = position

        hostView.showShutter()
        vodController?.resetProgress()
        liveController?.showError(null)
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
        vodController?.setPlaying(autoplay)
        liveController?.setPlaying(autoplay)
        showVodLoading(currentMode == PlayerConfig.Mode.VOD && autoplay)
        liveController?.showLoading(autoplay)
        hasAppliedSource = applyConfig(config)
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
        // 先通知 nvue 隐藏状态栏占位和下方内容，再在下一轮 UI 消息中旋转原生窗口。
        // 若先旋转，横屏第一帧仍会带着竖屏页面顶部的黑色占位条。
        fireEvent("fullscreenchange", mapOf("isFullscreen" to true))
        hostView.post {
            if (isFullscreen) enterActivityFullscreen()
        }
    }

    @UniJSMethod(uiThread = true)
    fun exitFullscreen() {
        isControlsLocked = false
        fullscreenRotatedToPortrait = false
        isFullscreen = false
        exitActivityFullscreen()
        vodController?.setFullscreen(false)
        liveController?.setFullscreen(false)
        fireEvent("fullscreenchange", mapOf("isFullscreen" to false))
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
        hostView.keepScreenOn = false
        exitActivityFullscreen()
        unregisterSystemVolumeObserver()
        hostView.detachPlayer(manager.getPlayer())
        manager.release()
    }

    private fun playByUser() {
        playbackResumeBlocked = false
        manager.play()
    }

    private fun pauseByUser() {
        playbackResumeBlocked = true
        manager.pause()
    }

    // ===== 生命周期回调 =====

    override fun onActivityResume() {
        super.onActivityResume()
        // 首次进入的自动播放由 autoplay 属性负责。Activity 恢复不能替用户播放；
        // 无论是用户手动暂停还是 onHide 导致的暂停，都只能由用户主动恢复。
    }

    override fun onActivityPause() {
        super.onActivityPause()
        // 页面不可见时由页面明确调用暂停，不允许无控制后台播放（§9.1）
        pauseByUser()
    }

    override fun onActivityDestroy() {
        super.onActivityDestroy()
        hostView.keepScreenOn = false
        exitActivityFullscreen()
        unregisterSystemVolumeObserver()
        hostView.detachPlayer(manager.getPlayer())
        manager.release()
    }

    private fun registerSystemVolumeObserver(context: Context) {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = manager
        lastSystemVolume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val changed = current != lastSystemVolume
                lastSystemVolume = current
                if (changed && current > 0 && propMuted) {
                    propMuted = false
                    this@LyoPlayerComponent.manager.setMuted(false)
                    vodController?.setMuted(false)
                    liveController?.setMuted(false)
                    fireMuteChange(false)
                }
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            volumeObserver!!,
        )
    }

    private fun unregisterSystemVolumeObserver() {
        val observer = volumeObserver ?: return
        try {
            getContext().contentResolver.unregisterContentObserver(observer)
        } catch (_: Throwable) {
        }
        volumeObserver = null
    }

    private fun enterActivityFullscreen() {
        val activity = findActivity(getContext()) ?: return
        if (previousOrientation == null) {
            previousOrientation = activity.requestedOrientation
            previousSystemUiVisibility = activity.window.decorView.systemUiVisibility
            hadFullscreenWindowFlag =
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0
        }
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        activity.window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        // Do not merely enlarge the component inside the nvue page. The page content area can
        // still retain status-bar/safe-area insets after rotation. Move the same native player
        // view into a window-level overlay so controls use the complete physical screen.
        if (fullscreenContainer == null) {
            val parent = hostView.parent as? ViewGroup
            val decor = activity.findViewById<FrameLayout>(android.R.id.content)
            if (parent != null && decor != null) {
                try {
                    embeddedParent = parent
                    embeddedIndex = parent.indexOfChild(hostView)
                    embeddedLayoutParams = hostView.layoutParams
                    parent.removeView(hostView)

                    val container = object : FrameLayout(activity) {
                        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                            if (event.keyCode == KeyEvent.KEYCODE_BACK && isFullscreen) {
                                if (!isControlsLocked && event.action == KeyEvent.ACTION_UP) {
                                    exitFullscreen()
                                }
                                return true
                            }
                            return super.dispatchKeyEvent(event)
                        }
                    }.apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        isFocusableInTouchMode = true
                        systemUiVisibility = activity.window.decorView.systemUiVisibility
                        applyFullscreenSafeInsets(this)
                        addView(
                            hostView,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            ),
                        )
                        addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                            val availableWidth =
                                (view.width - view.paddingLeft - view.paddingRight).coerceAtLeast(1)
                            val availableHeight =
                                (view.height - view.paddingTop - view.paddingBottom).coerceAtLeast(1)
                            val params = hostView.layoutParams as? FrameLayout.LayoutParams
                            if (params?.width != availableWidth || params.height != availableHeight) {
                                hostView.layoutParams = FrameLayout.LayoutParams(
                                    availableWidth,
                                    availableHeight,
                                    Gravity.TOP or Gravity.START,
                                )
                            }
                        }
                    }
                    decor.addView(
                        container,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    container.requestApplyInsets()
                    container.requestFocus()
                    registerFullscreenBackHandler(activity)
                    fullscreenContainer = container
                } catch (t: Throwable) {
                    Log.e(TAG, "enter fullscreen container failed", t)
                    runCatching {
                        (hostView.parent as? ViewGroup)?.removeView(hostView)
                        if (hostView.parent == null && parent.parent != null) {
                            val index = embeddedIndex.coerceIn(0, parent.childCount)
                            parent.addView(hostView, index, embeddedLayoutParams)
                        }
                    }.onFailure { restoreError ->
                        Log.e(TAG, "restore embedded player failed", restoreError)
                    }
                    embeddedParent = null
                    embeddedIndex = -1
                    embeddedLayoutParams = null
                }
            }
        }
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun rotateFullscreen() {
        if (!isFullscreen || isControlsLocked) return
        val activity = findActivity(getContext()) ?: return
        fullscreenRotatedToPortrait = !fullscreenRotatedToPortrait
        activity.requestedOrientation = if (fullscreenRotatedToPortrait) {
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun setFullscreenLocked(locked: Boolean) {
        if (!isFullscreen && locked) return
        isControlsLocked = locked
        val activity = findActivity(getContext()) ?: return
        activity.requestedOrientation = if (locked) {
            when (activity.resources.configuration.orientation) {
                android.content.res.Configuration.ORIENTATION_PORTRAIT ->
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        } else if (fullscreenRotatedToPortrait) {
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    /**
     * 真机横屏时刘海、圆角和手势导航区仍可能覆盖 Activity 的物理全屏区域。
     * 模拟器通常没有这些 inset，所以不能只依赖 MATCH_PARENT。这里把播放器约束在
     * 当前设备的安全区域内；导航栏临时滑出时 WindowInsets 也会重新下发。
     */
    private fun applyFullscreenSafeInsets(container: FrameLayout) {
        container.setOnApplyWindowInsetsListener { view, insets ->
            var left = insets.systemWindowInsetLeft
            var right = insets.systemWindowInsetRight
            var bottom = insets.systemWindowInsetBottom
            var top = 0

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                insets.displayCutout?.let { cutout ->
                    left = maxOf(left, cutout.safeInsetLeft)
                    top = maxOf(top, cutout.safeInsetTop)
                    right = maxOf(right, cutout.safeInsetRight)
                    bottom = maxOf(bottom, cutout.safeInsetBottom)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val gestures = insets.mandatorySystemGestureInsets
                left = maxOf(left, gestures.left)
                right = maxOf(right, gestures.right)
                bottom = maxOf(bottom, gestures.bottom)
            }

            // 视频区域上下使用对称安全留白，避免仅扣除底部导航区后画面视觉中心上移。
            top = maxOf(top, bottom)

            // 使用左右相同的最大安全边距，避免刘海方向变化后整套控件偏左或偏右。
            val horizontalSafeInset = maxOf(left, right)
            left = horizontalSafeInset
            right = horizontalSafeInset

            if (view.paddingLeft != left || view.paddingTop != top ||
                view.paddingRight != right || view.paddingBottom != bottom
            ) {
                view.setPadding(left, top, right, bottom)
            }
            insets
        }
    }

    private fun registerFullscreenBackHandler(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            fullscreenBackCallback != null
        ) return
        val callback = OnBackInvokedCallback {
            if (isFullscreen && !isControlsLocked) exitFullscreen()
        }
        runCatching {
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                callback,
            )
            fullscreenBackCallback = callback
        }.onFailure { error ->
            Log.w(TAG, "register fullscreen back callback failed", error)
        }
    }

    private fun unregisterFullscreenBackHandler(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        fullscreenBackCallback?.let { callback ->
            runCatching {
                activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
            }.onFailure { error ->
                Log.w(TAG, "unregister fullscreen back callback failed", error)
            }
        }
        fullscreenBackCallback = null
    }

    private fun exitActivityFullscreen() {
        val activity = findActivity(getContext()) ?: return
        unregisterFullscreenBackHandler(activity)
        if (previousOrientation == null) return

        try {
            fullscreenContainer?.let { container ->
                container.removeView(hostView)
                (container.parent as? ViewGroup)?.removeView(container)
                embeddedParent?.let { parent ->
                    if (parent.parent != null && hostView.parent == null) {
                        val index = embeddedIndex.coerceIn(0, parent.childCount)
                        parent.addView(hostView, index, embeddedLayoutParams)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "exit fullscreen container failed", t)
        }
        fullscreenContainer = null
        embeddedParent = null
        embeddedIndex = -1
        embeddedLayoutParams = null

        previousOrientation?.let { activity.requestedOrientation = it }
        previousSystemUiVisibility?.let { activity.window.decorView.systemUiVisibility = it }
        if (!hadFullscreenWindowFlag) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        previousOrientation = null
        previousSystemUiVisibility = null
        hadFullscreenWindowFlag = false
    }

    private fun findActivity(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
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
    private fun applyConfig(config: PlayerConfig): Boolean {
        return try {
            manager.applyConfig(config)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "apply player config failed", t)
            onError(PlayerErrorMapper.map(t))
            false
        }
    }

    /**
     * 用累积的全部 prop 重建一份完整 PlayerConfig 并下发。
     * 仅当 src 非空时才真正下发，避免初始化阶段（src 尚未到达）用空地址 prepare 导致黑屏。
     */
    private fun applyProps() {
        if (propSrc.isEmpty()) return
        hostView.showShutter()
        vodController?.resetProgress()
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
        vodController?.setPlaying(propAutoplay)
        liveController?.setPlaying(propAutoplay)
        showVodLoading(currentMode == PlayerConfig.Mode.VOD && propAutoplay)
        liveController?.showError(null)
        liveController?.showLoading(propAutoplay)
        hasAppliedSource = applyConfig(config)
    }

    /** 合并同一轮 nvue 属性 setter，避免每个属性都重新 prepare 当前媒体。 */
    private fun scheduleApplyProps() {
        if (propsApplyScheduled) return
        propsApplyScheduled = true
        hostView.post {
            propsApplyScheduled = false
            applyProps()
        }
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
                        onPlayToggle = { playByUser() },
                        onPauseToggle = { pauseByUser() },
                        onSeekTo = { ms -> seekVodTo(ms) },
                        onSpeedChange = { s ->
                            lastUserSpeed = s
                            manager.setSpeed(s)
                            fireSpeedChange(s)
                        },
                        onPrevEpisode = { fireEvent("prevepisode") },
                        onNextEpisode = { fireEvent("nextepisode") },
                        onMuteToggle = { m ->
                            propMuted = m
                            manager.setMuted(m)
                            fireMuteChange(m)
                        },
                        onFullscreenToggle = { fs ->
                            if (fs) requestFullscreen() else exitFullscreen()
                        },
                        onRotate = { rotateFullscreen() },
                        onLockChange = { locked -> setFullscreenLocked(locked) },
                        onBack = {
                            fireEvent("back", mapOf("detail" to mapOf("isFullscreen" to isFullscreen)))
                        },
                    )
                }
                vodController?.apply {
                    attachTo(hostView)
                    setTitle(propTitle)
                    setMuted(propMuted)
                    setSpeed(lastUserSpeed)
                    setPlaying(manager.getState()["isPlaying"] == true)
                    show()
                }
            }
            PlayerConfig.Mode.LIVE -> {
                showVodLoading(false)
                if (liveController == null) {
                    liveController = LiveControllerView(
                        context = getContext(),
                        hostView = hostView,
                        onPlayToggle = { playByUser() },
                        onPauseToggle = { pauseByUser() },
                        onMuteToggle = { m ->
                            propMuted = m
                            manager.setMuted(m)
                            fireMuteChange(m)
                        },
                        onFullscreenToggle = { fs ->
                            if (fs) requestFullscreen() else exitFullscreen()
                        },
                        onGoLiveEdge = { manager.goLiveEdge() },
                        onBack = {
                            fireEvent("back", mapOf("detail" to mapOf("isFullscreen" to isFullscreen)))
                        },
                    )
                }
                liveController?.apply {
                    attachTo(hostView)
                    setTitle(propTitle)
                    setMuted(propMuted)
                    setPlaying(manager.getState()["isPlaying"] == true)
                    show()
                }
            }
        }
    }

    private fun onError(err: PlayerErrorMapper.MappedError) {
        vodController?.setPlaying(false)
        showVodLoading(false)
        liveController?.setPlaying(false)
        liveController?.showLoading(false)
        liveController?.showError(err.message)
        val detail = mapOf(
            "code" to err.code,
            "message" to err.message,
            "retryable" to err.retryable,
            "nativeCode" to err.nativeCode,
        )
        fireEvent("error", mapOf("detail" to detail))
    }

    private fun onStateChanged(state: PlayerManager.State) {
        // 对齐 FongMi：播放及播放中的缓冲阶段保持屏幕常亮；暂停、结束或停止后释放。
        hostView.keepScreenOn =
            state == PlayerManager.State.PLAYING || state == PlayerManager.State.BUFFERING
        when (state) {
            PlayerManager.State.PLAYING -> {
                vodController?.setPlaying(true)
                showVodLoading(false)
                liveController?.setPlaying(true)
                liveController?.showLoading(false)
            }
            PlayerManager.State.PAUSED, PlayerManager.State.ENDED -> {
                vodController?.setPlaying(false)
                showVodLoading(false)
                liveController?.setPlaying(false)
                liveController?.showLoading(false)
            }
            PlayerManager.State.BUFFERING -> {
                showVodLoading(currentMode == PlayerConfig.Mode.VOD)
                liveController?.showLoading(true)
            }
            PlayerManager.State.READY -> {
                val playing = manager.getState()["isPlaying"] == true
                vodController?.setPlaying(playing)
                showVodLoading(false)
                liveController?.setPlaying(playing)
                liveController?.showLoading(false)
            }
            PlayerManager.State.IDLE -> Unit
        }
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
        vodController?.updateProgress(positionMs, durationMs, bufferedMs)
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
        hostView.hideShutter()
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

    /** 用户在播放器原生控制层切换倍速后，回传新的倍速给 JS（用于续播恢复） */
    private fun fireSpeedChange(speed: Float) {
        fireEvent("speedchange", mapOf("detail" to mapOf("speed" to speed)))
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
