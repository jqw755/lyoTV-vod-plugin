package com.lyo.media3.player.player

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView

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

    /** 内嵌的 Media3 PlayerView */
    val playerView: PlayerView = PlayerView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
        // 自定义控制层由 [VodControllerView] / [LiveControllerView] 接管，
        // 关闭 PlayerView 自带的控制器
        useController = false
        setShutterBackgroundColor(0xFF000000.toInt())
    }

    init {
        addView(playerView)
        setBackgroundColor(0xFF000000.toInt())
    }

    /** 把 ExoPlayer 实例 attach 到 PlayerView（全屏切换前后都用同一个 player） */
    fun attachPlayer(player: Player?) {
        playerView.player = player
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
}
