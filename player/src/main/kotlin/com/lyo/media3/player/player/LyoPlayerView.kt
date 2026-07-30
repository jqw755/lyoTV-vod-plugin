package com.lyo.media3.player.player

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.math.BigDecimal

/**
 * 自定义 PlayerView 容器。
 *
 * - 普通模式：16:9 高度（屏幕宽度 × 9 / 16），内嵌在 nvue 组件树。
 * - 全屏模式：扩展到可用屏幕，请求横屏，隐藏 nvue 下方内容和系统栏
 *   （docs/android-media3-nvue-player-refactor-plan.md §11）。
 *
 * 全屏实现说明：nvue 的 Component 宿主 View 是 nvue 渲染树中的节点，
 * 直接把它从原父容器 detach 再 attach 到 android.R.id.content 不能稳定覆盖整个窗口
 * （nvue 自己管理布局）。所以全屏切换由 [LyoPlayerComponent.requestFullscreen] /
 * [exitFullscreen] 通过 nvue 业务层 + 一个全屏 [FullscreenDialog] 完成：
 * 业务层切到全屏 nvue 根（visibility 控制下方内容），并把 PlayerView 复用 attach 到
 * 全屏容器；退出时 attach 回原位置。
 *
 * 本类只负责 PlayerView 的初始化、attach/detach、横竖屏适配。
 *
 * 关键约束（§11）：
 * - 复用同一个 ExoPlayer 实例，全屏切换不重新创建 player。
 * - 全屏切换不得重新解析 URL、丢失进度或触发自动换源。
 */
class LyoPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * 使用 TextureView 而不是 SurfaceView。
     * SurfaceView 是独立系统图层，uni-app 缓存 tab/nvue 页面时会残留最后一帧，
     * 甚至穿透显示到新打开的详情页；TextureView 会正常参与页面合成与隐藏。
     */
    private val textureView: TextureView = TextureView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
    }
    private var attachedPlayer: Player? = null
    private var videoAspectRatio = 0f
    private val videoSizeListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val width = videoSize.width
            val height = videoSize.height
            videoAspectRatio = if (width > 0 && height > 0) {
                width * videoSize.pixelWidthHeightRatio / height
            } else {
                0f
            }
            updateTextureViewSize()
        }
    }

    /** 换源到首帧之间遮黑，避免显示上一条流的最后一帧。 */
    private val shutterView: View = View(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(0xFF000000.toInt())
        isClickable = false
    }

    /**
     * 专门接收视频空白区域手势。
     *
     * 不能只监听 FrameLayout：触摸事件会先命中 TextureView，父容器的
     * OnTouchListener 在 nvue 中收不到完整事件序列。该透明层位于视频画面之上、
     * 控制层之下，不影响控制按钮点击，也不参与视频渲染。
     */
    private val gestureTouchView: View = View(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(0x00000000)
        isClickable = true
    }

    private val speedHintView: TextView = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        includeFontPadding = false
        setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), 0xCC000000.toInt())
        val speedIcon = resources.getIdentifier("lyo_ic_speed", "drawable", context.packageName)
        if (speedIcon != 0) {
            val drawable = resources.getDrawable(speedIcon, context.theme).apply {
                setBounds(0, 0, dp(20), dp(20))
            }
            setCompoundDrawablesRelative(null, null, drawable, null)
        }
        compoundDrawablePadding = dp(3)
        visibility = View.GONE
        isClickable = false
        isFocusable = false
        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply {
            topMargin = dp(14)
        }
    }
    private val bufferingView: ProgressBar = ProgressBar(context).apply {
        indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        visibility = View.GONE
        isClickable = false
        isFocusable = false
        layoutParams = LayoutParams(dp(42), dp(42), Gravity.CENTER)
    }
    private var speedHintAnimator: ObjectAnimator? = null

    private val seekHintView: TextView = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        includeFontPadding = false
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = GradientDrawable().apply {
            setColor(0x99000000.toInt())
            cornerRadius = dp(100).toFloat()
        }
        visibility = View.GONE
        isClickable = false
        isFocusable = false
        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        )
    }

    init {
        addView(textureView)
        addView(shutterView)
        addView(gestureTouchView)
        addView(bufferingView)
        addView(speedHintView)
        addView(seekHintView)
        setBackgroundColor(0xFF000000.toInt())
    }

    /** 把 ExoPlayer 连接到实际的视频输出面。 */
    fun attachPlayer(player: Player?) {
        if (attachedPlayer !== player) {
            attachedPlayer?.removeListener(videoSizeListener)
            attachedPlayer = player
            player?.addListener(videoSizeListener)
        }
        player?.videoSize?.let(videoSizeListener::onVideoSizeChanged)
        (player as? ExoPlayer)?.setVideoTextureView(textureView)
    }

    fun detachPlayer(player: Player?) {
        (player as? ExoPlayer)?.clearVideoTextureView(textureView)
        if (attachedPlayer === player) {
            player?.removeListener(videoSizeListener)
            attachedPlayer = null
            videoAspectRatio = 0f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTextureViewSize(w, h)
    }

    /**
     * TextureView 默认 MATCH_PARENT 会把视频强制拉伸到容器比例。
     * 按视频实际宽高比做 aspect-fit，剩余区域显示黑边，不裁剪也不拉伸人物。
     */
    private fun updateTextureViewSize(containerWidth: Int = width, containerHeight: Int = height) {
        if (containerWidth <= 0 || containerHeight <= 0 || videoAspectRatio <= 0f) return
        val containerAspectRatio = containerWidth.toFloat() / containerHeight
        val targetWidth: Int
        val targetHeight: Int
        if (videoAspectRatio > containerAspectRatio) {
            targetWidth = containerWidth
            targetHeight = (containerWidth / videoAspectRatio).toInt().coerceAtLeast(1)
        } else {
            targetHeight = containerHeight
            targetWidth = (containerHeight * videoAspectRatio).toInt().coerceAtLeast(1)
        }
        val params = textureView.layoutParams as? LayoutParams
        if (params?.width == targetWidth && params.height == targetHeight) return
        textureView.layoutParams = LayoutParams(targetWidth, targetHeight, Gravity.CENTER)
    }

    fun setPlayerGestureTouchListener(listener: OnTouchListener) {
        gestureTouchView.setOnTouchListener(listener)
    }

    fun showLongPressSpeedHint(speed: Float) {
        speedHintView.text = "${BigDecimal(speed.toString()).stripTrailingZeros().toPlainString()}x"
        speedHintView.visibility = View.VISIBLE
        speedHintView.bringToFront()
        speedHintAnimator?.cancel()
        speedHintView.translationX = 0f
        speedHintAnimator = ObjectAnimator.ofFloat(
            speedHintView,
            View.TRANSLATION_X,
            0f,
            dp(10).toFloat(),
            0f,
        ).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    fun hideLongPressSpeedHint() {
        speedHintAnimator?.cancel()
        speedHintAnimator = null
        speedHintView.translationX = 0f
        speedHintView.visibility = View.GONE
    }

    fun showSeekHint(deltaMs: Long, targetMs: Long) {
        val action = if (deltaMs >= 0L) "快进到" else "快退到"
        seekHintView.text = "$action ${formatDuration(targetMs)}"
        seekHintView.layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply {
            topMargin = dp(14)
        }
        seekHintView.visibility = View.VISIBLE
        seekHintView.bringToFront()
    }

    fun hideSeekHint() {
        seekHintView.visibility = View.GONE
    }

    fun showBuffering(show: Boolean) {
        bufferingView.visibility = if (show) View.VISIBLE else View.GONE
        if (show) bufferingView.bringToFront()
    }

    fun showVerticalGestureHint(label: String, percent: Int) {
        seekHintView.text = "$label ${percent.coerceIn(0, 100)}%"
        seekHintView.layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        )
        seekHintView.visibility = View.VISIBLE
        seekHintView.bringToFront()
    }

    fun hideVerticalGestureHint() {
        hideSeekHint()
    }

    override fun onDetachedFromWindow() {
        showBuffering(false)
        hideLongPressSpeedHint()
        hideSeekHint()
        super.onDetachedFromWindow()
    }

    fun showShutter() {
        shutterView.visibility = View.VISIBLE
    }

    fun hideShutter() {
        shutterView.visibility = View.GONE
    }

    /** 普通模式：计算 16:9 高度并应用到自身 */
    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}
