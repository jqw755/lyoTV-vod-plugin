package com.lyo.media3.player.control

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.lyo.media3.player.player.LyoPlayerView

/**
 * 直播控制层（§8.3）。
 *
 * 只实现：
 * - 播放/暂停
 * - 当前频道名称
 * - 当前线路序号
 * - 静音
 * - 全屏
 * - 回到直播
 * - 加载和错误状态
 *
 * 不显示点播进度、总时长、倍速、上一集、下一集。
 */
class LiveControllerView(
    context: Context,
    private val hostView: LyoPlayerView,
    private val onPlayToggle: () -> Unit,
    private val onPauseToggle: () -> Unit,
    private val onMuteToggle: (Boolean) -> Unit,
    private val onFullscreenToggle: (Boolean) -> Unit,
    private val onGoLiveEdge: () -> Unit,
    private val onBack: () -> Unit,
) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val autoHideMs = 4000L
    private var autoHideRunnable: Runnable? = null
    private var isShowing = false
    private var isPlaying = false
    private var isMuted = false
    private var isFullscreen = false

    private val topBar: LinearLayout
    private val centerPlayBtn: PlayerIconView
    private val bottomBar: LinearLayout
    private val titleText: TextView
    private val lineBadge: TextView
    private val muteBtn: PlayerIconView
    private val fullscreenBtn: PlayerIconView
    private val backBtn: PlayerIconView
    private val liveEdgeBtn: TextView
    private val loadingView: ProgressBar
    private val errorText: TextView

    init {
        setBackgroundColor(Color.TRANSPARENT)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        // 顶部栏：返回 + 频道名 + 线路序号
        topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(6), dp(21), dp(6))
            background = topGradient()
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP)
            translationY = -dp(3).toFloat()
        }
        titleText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        lineBadge = TextView(context).apply {
            setTextColor(0xFFfe8027.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), 0, dp(8), 0)
        }
        backBtn = PlayerIconView(context, PlayerIconView.Icon.BACK).apply {
            setOnClickListener {
                if (isFullscreen) onFullscreenToggle(false) else onBack()
                show()
            }
        }
        muteBtn = makeIconBtn(PlayerIconView.Icon.VOLUME) { toggleMute() }
        fullscreenBtn = makeIconBtn(PlayerIconView.Icon.FULLSCREEN) { toggleFullscreen() }
        topBar.addView(backBtn)
        topBar.addView(titleText)
        topBar.addView(lineBadge)

        // 中央播放按钮：纯矢量图标，无半透明按钮底
        centerPlayBtn = PlayerIconView(context, PlayerIconView.Icon.PLAY).apply {
            layoutParams = LayoutParams(dp(68), dp(68), Gravity.CENTER)
            setOnClickListener {
                if (isPlaying) onPauseToggle() else onPlayToggle()
                show()
            }
        }

        // 底部栏：直播状态 + 音量 + 全屏
        bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(4), dp(21), 0)
            background = bottomGradient()
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        liveEdgeBtn = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = "回到直播"
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onGoLiveEdge(); show() }
        }
        bottomBar.addView(liveEdgeBtn)
        bottomBar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        bottomBar.addView(muteBtn)
        bottomBar.addView(fullscreenBtn)

        // 加载 / 错误状态
        loadingView = ProgressBar(context).apply {
            layoutParams = LayoutParams(dp(40), dp(40), Gravity.CENTER)
            visibility = View.GONE
        }
        errorText = TextView(context).apply {
            setTextColor(0xFFfe8027.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), dp(12))
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }

        addView(topBar)
        addView(centerPlayBtn)
        addView(bottomBar)
        addView(loadingView)
        addView(errorText)

        visibility = View.GONE
        isShowing = false
    }

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

    fun setLineInfo(current: Int, total: Int) {
        if (total > 1) {
            lineBadge.text = "线路 ${current + 1}/$total"
            lineBadge.visibility = View.VISIBLE
        } else {
            lineBadge.visibility = View.GONE
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        muteBtn.icon = if (muted) PlayerIconView.Icon.MUTED else PlayerIconView.Icon.VOLUME
    }

    fun setFullscreen(fs: Boolean) {
        isFullscreen = fs
        fullscreenBtn.icon = if (fs) PlayerIconView.Icon.EXIT_FULLSCREEN else PlayerIconView.Icon.FULLSCREEN
    }

    fun setPlaying(playing: Boolean) {
        isPlaying = playing
        centerPlayBtn.icon = if (playing) PlayerIconView.Icon.PAUSE else PlayerIconView.Icon.PLAY
    }

    fun showLoading(show: Boolean) {
        loadingView.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            errorText.visibility = View.GONE
        }
    }

    fun showError(msg: String?) {
        if (msg.isNullOrEmpty()) {
            errorText.visibility = View.GONE
        } else {
            errorText.text = msg
            errorText.visibility = View.VISIBLE
            loadingView.visibility = View.GONE
        }
    }

    fun show() {
        visibility = View.VISIBLE
        isShowing = true
        scheduleAutoHide()
    }

    fun hide() {
        visibility = View.GONE
        isShowing = false
        cancelAutoHide()
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

    private fun dp(v: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()
    }
}
