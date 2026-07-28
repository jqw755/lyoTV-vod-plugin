package com.lyo.media3.player.player

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

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
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(9), dp(16), dp(9))
        background = GradientDrawable().apply {
            setColor(0xB8000000.toInt())
            cornerRadius = dp(8).toFloat()
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
        addView(speedHintView)
        setBackgroundColor(0xFF000000.toInt())
    }

    /** 把 ExoPlayer 连接到实际的视频输出面。 */
    fun attachPlayer(player: Player?) {
        (player as? ExoPlayer)?.setVideoTextureView(textureView)
    }

    fun detachPlayer(player: Player?) {
        (player as? ExoPlayer)?.clearVideoTextureView(textureView)
    }

    fun setPlayerGestureTouchListener(listener: OnTouchListener) {
        gestureTouchView.setOnTouchListener(listener)
    }

    fun showLongPressSpeedHint(speed: Float) {
        speedHintView.text = String.format(Locale.US, "%.1fx 倍速播放", speed)
        speedHintView.visibility = View.VISIBLE
        speedHintView.bringToFront()
    }

    fun hideLongPressSpeedHint() {
        speedHintView.visibility = View.GONE
    }

    fun showShutter() {
        shutterView.visibility = View.VISIBLE
    }

    fun hideShutter() {
        shutterView.visibility = View.GONE
    }

    /** 普通模式：计算 16:9 高度并应用到自身 */
    fun applyEmbedSize() {
        val w = resources.displayMetrics.widthPixels
        val h = (w * 9f / 16f).toInt()
        layoutParams = layoutParams?.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = h
        } ?: LayoutParams(LayoutParams.MATCH_PARENT, h)
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()
}
