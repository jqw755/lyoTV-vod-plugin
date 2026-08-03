package com.lyo.media3.player.control

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import com.lyo.media3.player.player.LyoPlayerView
import java.math.BigDecimal
import java.util.Locale

/**
 * 点播控制层（§8.2）。
 *
 * 只实现：
 * - 中央播放/暂停按钮
 * - 底部进度条 + 当前时间/总时长
 * - 倍速按钮
 * - 上一集 / 下一集
 * - 静音
 * - 全屏
 * - 控制栏自动隐藏（4s 无操作）
 *
 * 不在此处实现双击快退/快进、长按临时倍速（这些由 [GestureController] 负责）。
 */
class VodControllerView(
    context: Context,
    private val hostView: LyoPlayerView,
    private val onPlayToggle: () -> Unit,
    private val onPauseToggle: () -> Unit,
    private val onSeekTo: (Long) -> Unit,
    private val onSpeedChange: (Float) -> Unit,
    private val onPrevEpisode: () -> Unit,
    private val onNextEpisode: () -> Unit,
    private val onSelectEpisode: (Int) -> Unit,
    private val onMuteToggle: (Boolean) -> Unit,
    private val onFullscreenToggle: (Boolean) -> Unit,
    private val onRotate: () -> Unit,
    private val onLockChange: (Boolean) -> Unit,
    private val onBack: () -> Unit,
) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val autoHideMs = 4000L
    private var autoHideRunnable: Runnable? = null
    private var isShowing = false
    private var isPlaying = false
    private var isMuted = false
    private var isFullscreen = false
    private var isLocked = false
    private var currentSpeed = 1f
    private var isTrackingProgress = false
    private var progressDurationMs = 0L

    // 控件
    private val centerPlayBtn: PlayerIconView
    private val bottomBar: LinearLayout
    private val topBar: LinearLayout
    private val seekBar: SeekBar
    private val currentTimeText: TextView
    private val durationText: TextView
    private val speedBtn: TextView
    private val prevBtn: PlayerIconView
    private val nextBtn: PlayerIconView
    private val muteBtn: PlayerIconView
    private val fullscreenBtn: PlayerIconView
    private val backBtn: PlayerIconView
    private val rotateBtn: PlayerIconView
    private val lockBtn: PlayerIconView
    private val episodeBtn: TextView
    private val centerControls: LinearLayout
    private val rightControls: LinearLayout
    private val titleText: TextView

    private val speedOptions = floatArrayOf(1f, 1.5f, 1.75f, 2f, 3f, 4f)
    private var speedPopup: LinearLayout? = null

    // 侧边栏（选集 + 倍速）
    private var sidePanel: LyoSidePanel? = null
    private val episodes: MutableList<String> = mutableListOf()
    private var currentEpisodeIndex: Int = -1
    // 切集 loading：切集时置 true，期间拦截重复点击
    private var isEpisodeLoading: Boolean = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        // 顶部栏：返回 + 标题
        topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = topGradient()
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP)
            translationY = -dp(3).toFloat()
        }
        titleText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        backBtn = PlayerIconView(context, PlayerIconView.Icon.BACK).apply {
            setOnClickListener {
                if (isFullscreen) onFullscreenToggle(false) else onBack()
                show()
            }
        }
        topBar.addView(backBtn)
        topBar.addView(titleText)

        // 中央播放按钮：只显示图标，不添加圆形/半透明遮罩
        centerPlayBtn = PlayerIconView(context, PlayerIconView.Icon.PLAY).apply {
            setOnClickListener {
                if (isPlaying) onPauseToggle() else onPlayToggle()
                show()
            }
        }

        // 底部栏：上方进度，下方常用操作
        bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 左右使用相同边距；全屏安全区由外层容器统一补偿，避免竖屏操作栏偏左。
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = bottomGradient()
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        prevBtn = makeIconBtn(PlayerIconView.Icon.PREVIOUS) { onPrevEpisode() }.apply {
            background = episodeIconBackground()
        }
        nextBtn = makeIconBtn(PlayerIconView.Icon.NEXT) { onNextEpisode() }.apply {
            background = episodeIconBackground()
        }
        muteBtn = makeIconBtn(PlayerIconView.Icon.VOLUME) { toggleMute() }
        fullscreenBtn = makeIconBtn(PlayerIconView.Icon.FULLSCREEN) { toggleFullscreen() }
        rotateBtn = makeIconBtn(PlayerIconView.Icon.ROTATE) { onRotate() }
        lockBtn = makeIconBtn(PlayerIconView.Icon.UNLOCK) { toggleLock() }
        episodeBtn = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = "选集"
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { onEpisodeClick() }
        }
        centerControls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            translationY = -dp(3).toFloat()
            addView(prevBtn)
            addView(centerPlayBtn, LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                marginStart = dp(40)
                marginEnd = dp(40)
            })
            addView(nextBtn)
        }
        currentTimeText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = "00:00"
            setPadding(dp(4), 0, dp(4), 0)
        }
        val trackHeight = dp(3)
        fun trackDrawable(color: Int): ClipDrawable {
            val shape = GradientDrawable().apply {
                setColor(color)
                cornerRadius = trackHeight / 2f
            }
            return ClipDrawable(shape, Gravity.LEFT, ClipDrawable.HORIZONTAL)
        }
        val progressLayer = trackDrawable(Color.WHITE)
        val secondaryLayer = trackDrawable(Color.parseColor("#80FFFFFF"))
        val backgroundLayer = GradientDrawable().apply {
            setColor(Color.parseColor("#33FFFFFF"))
            cornerRadius = trackHeight / 2f
        }
        val progressDrawable = LayerDrawable(arrayOf(backgroundLayer, secondaryLayer, progressLayer)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.secondaryProgress)
            setId(2, android.R.id.progress)
        }
        val thumbDiameter = dp(15)
        val thumbDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setSize(thumbDiameter, thumbDiameter)
        }
        seekBar = SeekBar(context).apply {
            // 15dp 圆点只需要 17dp 布局高度；避免把整个底栏向播放器中央顶起。
            layoutParams = LinearLayout.LayoutParams(0, dp(17), 1f)
            max = 1000
            setProgressDrawable(progressDrawable)
            maxHeight = trackHeight
            isEnabled = false
            thumb = thumbDrawable
            thumbOffset = thumbDiameter / 2
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser || progressDurationMs <= 0L) return
                    currentTimeText.text = formatTime(progressToPosition(p))
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {
                    isTrackingProgress = true
                    cancelAutoHide()
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    isTrackingProgress = false
                    if (progressDurationMs > 0L) {
                        val target = progressToPosition(sb?.progress ?: 0)
                        currentTimeText.text = formatTime(target)
                        onSeekTo(target)
                    }
                    show()
                }
            })
        }
        durationText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = "00:00"
            setPadding(dp(4), 0, dp(4), 0)
        }
        speedBtn = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = "1x"
            val speedIcon = resolvePlayerDrawable(context, "lyo_ic_speed")
            if (speedIcon != 0) {
                val drawable = resources.getDrawable(speedIcon, context.theme).apply {
                    setBounds(0, 0, dp(34), dp(34))
                }
                setCompoundDrawablesRelative(drawable, null, null, null)
            }
            compoundDrawablePadding = dp(4)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            // 竖向 padding 会把操作行撑高，并连带把上方进度条顶向播放器中央。
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { onSpeedClick() }
        }
        val progressRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            translationY = -dp(8).toFloat()
            addView(currentTimeText)
            addView(seekBar)
            addView(durationText)
        }
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            translationY = 0f
            addView(episodeBtn, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(speedBtn)
            addView(muteBtn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(10)
            })
            addView(fullscreenBtn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(10)
            })
        }
        bottomBar.addView(progressRow)
        bottomBar.addView(actionRow)

        addView(topBar)
        addView(centerControls)
        addView(bottomBar)
        rightControls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ).apply { marginEnd = dp(12) }
            translationY = -dp(3).toFloat()
            addView(lockBtn, LinearLayout.LayoutParams(dp(40), dp(36)).apply {
                bottomMargin = dp(14)
            })
            addView(rotateBtn, LinearLayout.LayoutParams(dp(40), dp(36)).apply {
                topMargin = dp(14)
            })
        }
        addView(rightControls)

        // 默认隐藏，等首次播放或用户单击后再显示
        visibility = View.GONE
        isShowing = false
    }

    /** attach 到 hostView（在 playerView 之上） */
    fun attachTo(host: LyoPlayerView) {
        (parent as? ViewGroup)?.removeView(this)
        host.addView(this)
    }

    fun removeFromParent() {
        (parent as? ViewGroup)?.removeView(this)
    }

    fun setTitle(title: String) {
        titleText.text = title
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        muteBtn.icon = if (muted) PlayerIconView.Icon.MUTED else PlayerIconView.Icon.VOLUME
    }

    fun setFullscreen(fs: Boolean) {
        isFullscreen = fs
        if (!fs && isLocked) {
            isLocked = false
            onLockChange(false)
        }
        fullscreenBtn.icon = if (fs) PlayerIconView.Icon.EXIT_FULLSCREEN else PlayerIconView.Icon.FULLSCREEN
        applyControlVisibility()
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed
        speedBtn.text = formatSpeed(speed)
    }

    /** 设置选集列表（侧边栏用）。 */
    fun setEpisodes(names: List<String>) {
        episodes.clear()
        episodes.addAll(names)
    }

    /** 设置当前选集索引（侧边栏高亮用）。 */
    fun setCurrentEpisodeIndex(index: Int) {
        currentEpisodeIndex = index
    }

    /** 切集 loading 状态：切集时置 true，播放就绪后置 false。 */
    fun setEpisodeLoading(loading: Boolean) {
        isEpisodeLoading = loading
    }

    fun setPlaying(playing: Boolean) {
        isPlaying = playing
        centerPlayBtn.icon = if (playing) PlayerIconView.Icon.PAUSE else PlayerIconView.Icon.PLAY
    }

    fun showLoading(show: Boolean) {
        // 保留播放按钮占位，避免加载时上一集/下一集突然靠拢。
        centerPlayBtn.visibility = if (show) View.INVISIBLE else View.VISIBLE
    }

    fun updateProgress(positionMs: Long, durationMs: Long, bufferedMs: Long) {
        if (durationMs <= 0) {
            resetProgress()
            return
        }
        progressDurationMs = durationMs
        seekBar.isEnabled = true
        if (!isTrackingProgress) {
            val safePosition = positionMs.coerceIn(0L, durationMs)
            val safeBuffered = bufferedMs.coerceIn(safePosition, durationMs)
            seekBar.progress = positionToProgress(safePosition, durationMs)
            seekBar.secondaryProgress = positionToProgress(safeBuffered, durationMs)
            currentTimeText.text = formatTime(safePosition)
        }
        durationText.text = formatTime(durationMs)
    }

    fun resetProgress() {
        progressDurationMs = 0L
        isTrackingProgress = false
        seekBar.isEnabled = false
        seekBar.progress = 0
        seekBar.secondaryProgress = 0
        currentTimeText.text = "00:00"
        durationText.text = "00:00"
    }

    /** 显示控制层；4s 后自动隐藏 */
    fun show() {
        visibility = View.VISIBLE
        isShowing = true
        applyControlVisibility()
        scheduleAutoHide()
    }

    fun hide() {
        visibility = View.GONE
        isShowing = false
        cancelAutoHide()
        dismissSpeedPopup()
    }

    fun toggleVisible() {
        if (isShowing) hide() else show()
    }

    private fun toggleMute() {
        isMuted = !isMuted
        muteBtn.icon = if (isMuted) PlayerIconView.Icon.MUTED else PlayerIconView.Icon.VOLUME
        onMuteToggle(isMuted)
        show()
    }

    private fun toggleFullscreen() {
        onFullscreenToggle(!isFullscreen)
    }

    private fun toggleLock() {
        if (!isFullscreen) return
        isLocked = !isLocked
        lockBtn.icon = if (isLocked) PlayerIconView.Icon.LOCK else PlayerIconView.Icon.UNLOCK
        onLockChange(isLocked)
        applyControlVisibility()
        show()
    }

    private fun applyControlVisibility() {
        rotateBtn.visibility = if (isFullscreen && !isLocked) View.VISIBLE else View.GONE
        lockBtn.visibility = if (isFullscreen) View.VISIBLE else View.GONE
        episodeBtn.visibility = if (isFullscreen && !isLocked) View.VISIBLE else View.GONE
        topBar.visibility = if (isLocked) View.GONE else View.VISIBLE
        centerControls.visibility = if (isLocked) View.GONE else View.VISIBLE
        bottomBar.visibility = if (isLocked) View.GONE else View.VISIBLE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val playParams = centerPlayBtn.layoutParams as? LinearLayout.LayoutParams ?: return
        // FongMi 横屏使用 40dp；竖屏空间较窄，缩为 20dp，避免切集按钮离播放键过远。
        val margin = dp(if (w > h) 24 else 10)
        if (playParams.marginStart != margin || playParams.marginEnd != margin) {
            playParams.marginStart = margin
            playParams.marginEnd = margin
            centerPlayBtn.layoutParams = playParams
        }
        val horizontalPadding = dp(if (w > h) 12 else 10)
        topBar.setPadding(horizontalPadding, dp(6), horizontalPadding, dp(6))
        bottomBar.setPadding(horizontalPadding, dp(4), horizontalPadding, 0)
        (rightControls.layoutParams as? LayoutParams)?.let { params ->
            if (params.marginEnd != horizontalPadding) {
                params.marginEnd = horizontalPadding
                rightControls.layoutParams = params
            }
        }
    }

    private fun onEpisodeClick() {
        // 仅全屏可打开侧边栏
        if (!isFullscreen || isLocked) return
        // 切集 loading 期间不重复打开
        if (isEpisodeLoading && sidePanel == null) return
        openSidePanel(SidePanelType.EPISODES)
    }

    private fun onSpeedClick() {
        if (!isFullscreen || isLocked) {
            toggleSpeedPopup()
            return
        }
        openSidePanel(SidePanelType.SPEED)
    }

    private enum class SidePanelType { EPISODES, SPEED }

    private fun openSidePanel(type: SidePanelType) {
        val panel = sidePanel ?: LyoSidePanel(
            context,
            maxWidthDp = 240,
            onDismiss = {
                sidePanel = null
                show()
            },
        ).also { sidePanel = it }
        if (type == SidePanelType.EPISODES) {
            // 原生层按播放器实际宽度换算 200rpx（设计基准宽度 750rpx）。
            panel.configure(
                widthPx = (hostView.width * 200f / 750f).toInt() + dp(30),
                backgroundColor = Color.WHITE,
            )
        } else {
            panel.configure()
        }
        panel.setContent(buildPanelContent(type))
        panel.show(hostView)
        cancelAutoHide()
    }

    /**
     * 构造侧边栏内容：[SidePanelType.EPISODES] 只放选集列表，
     * [SidePanelType.SPEED] 只放倍速列表。
     */
    private fun buildPanelContent(type: SidePanelType): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        when (type) {
            SidePanelType.EPISODES -> {
                val episodeScroll = ScrollView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    )
                }
                val episodeList = EpisodeFlowLayout(context)
                episodes.forEachIndexed { index, name ->
                    val tv = TextView(context).apply {
                        val selected = index == currentEpisodeIndex
                        setTextColor(if (selected) Color.WHITE else 0xFF333333.toInt())
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        text = name
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        background = GradientDrawable().apply {
                            setColor(if (selected) 0xFFfe8027.toInt() else 0xFFF2F2F2.toInt())
                            cornerRadius = dp(6).toFloat()
                        }
                        setOnClickListener {
                            // 当前集点中拦截，避免再次刷新播放该集
                            if (index == currentEpisodeIndex) return@setOnClickListener
                            // 切集 loading 期间拦截，避免无反应默默切用户以为不知道就继续多点
                            if (isEpisodeLoading) return@setOnClickListener
                            isEpisodeLoading = true
                            currentEpisodeIndex = index
                            onSelectEpisode(index)
                            sidePanel?.dismiss()
                        }
                    }
                    episodeList.addView(tv, ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        rightMargin = dp(8)
                        bottomMargin = dp(8)
                    })
                }
                episodeScroll.addView(episodeList)
                root.addView(episodeScroll)
            }
            SidePanelType.SPEED -> {
                val speedTitle = TextView(context).apply {
                    setTextColor(0xFFfe8027.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    text = "倍速"
                    setPadding(0, dp(4), 0, dp(8))
                }
                root.addView(speedTitle)
                speedOptions.forEach { s ->
                    val tv = TextView(context).apply {
                        setTextColor(if (s == currentSpeed) 0xFFfe8027.toInt() else Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        text = formatSpeed(s)
                        setPadding(0, dp(8), 0, dp(8))
                        setOnClickListener {
                            currentSpeed = s
                            onSpeedChange(s)
                            speedBtn.text = formatSpeed(s)
                            sidePanel?.dismiss()
                        }
                    }
                    root.addView(tv)
                }
            }
        }
        return root
    }

    private fun toggleSpeedPopup() {
        if (speedPopup != null) {
            dismissSpeedPopup()
            show()
            return
        }
        val popup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        speedOptions.forEach { s ->
            val tv = TextView(context).apply {
                setTextColor(if (s == currentSpeed) 0xFFfe8027.toInt() else Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                text = formatSpeed(s)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener {
                    currentSpeed = s
                    onSpeedChange(s)
                    speedBtn.text = formatSpeed(s)
                    dismissSpeedPopup()
                    show()
                }
            }
            popup.addView(tv)
        }
        addView(popup)
        speedPopup = popup
        cancelAutoHide()
    }

    private fun dismissSpeedPopup() {
        val p = speedPopup ?: return
        removeView(p)
        speedPopup = null
    }

    private fun scheduleAutoHide() {
        cancelAutoHide()
        val r = Runnable { if (isPlaying) hide() }
        autoHideRunnable = r
        handler.postDelayed(r, autoHideMs)
    }

    private fun cancelAutoHide() {
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        autoHideRunnable = null
    }

    private fun makeIconBtn(icon: PlayerIconView.Icon, onClick: () -> Unit): PlayerIconView {
        return PlayerIconView(context, icon).apply {
            setOnClickListener { onClick(); show() }
        }
    }

    private fun topGradient() = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xB8000000.toInt(), 0x00000000)
    )

    private fun bottomGradient() = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0x00000000, 0xCC000000.toInt())
    )

    /** 切集图标的半透明圆形背景（容器为 36×36dp 正方形）。 */
    private fun episodeIconBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(0x66000000)
        cornerRadius = dp(18).toFloat()
    }

    private fun dp(v: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun progressToPosition(progress: Int): Long {
        if (progressDurationMs <= 0L || seekBar.max <= 0) return 0L
        return (progress.coerceIn(0, seekBar.max).toLong() * progressDurationMs / seekBar.max)
            .coerceIn(0L, progressDurationMs)
    }

    private fun positionToProgress(positionMs: Long, durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        return (positionMs.coerceIn(0L, durationMs) * seekBar.max / durationMs)
            .coerceIn(0L, seekBar.max.toLong())
            .toInt()
    }

    private fun formatSpeed(speed: Float): String {
        return BigDecimal(speed.toString()).stripTrailingZeros().toPlainString() + "x"
    }
}

/** 简单流式布局：选集按钮按内容宽度横向排列，空间不足时自动换行。 */
private class EpisodeFlowLayout(context: Context) : ViewGroup(context) {
    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams =
        p?.let(::MarginLayoutParams) ?: generateDefaultLayoutParams()

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var lineWidth = 0
        var lineHeight = 0
        var totalHeight = paddingTop + paddingBottom
        var maxLineWidth = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, totalHeight)
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (lineWidth > 0 && lineWidth + childWidth > availableWidth) {
                maxLineWidth = maxOf(maxLineWidth, lineWidth)
                totalHeight += lineHeight
                lineWidth = 0
                lineHeight = 0
            }
            lineWidth += childWidth
            lineHeight = maxOf(lineHeight, childHeight)
        }
        maxLineWidth = maxOf(maxLineWidth, lineWidth)
        totalHeight += lineHeight
        setMeasuredDimension(
            resolveSize(maxLineWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(totalHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val availableWidth = r - l - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x > paddingLeft && x - paddingLeft + childWidth > availableWidth) {
                x = paddingLeft
                y += lineHeight
                lineHeight = 0
            }
            val left = x + lp.leftMargin
            val top = y + lp.topMargin
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
            x += childWidth
            lineHeight = maxOf(lineHeight, childHeight)
        }
    }
}
