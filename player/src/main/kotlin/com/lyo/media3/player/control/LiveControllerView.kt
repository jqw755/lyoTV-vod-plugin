package com.lyo.media3.player.control

import android.content.Context
import android.graphics.Color
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
) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val autoHideMs = 4000L
    private var autoHideRunnable: Runnable? = null
    private var isShowing = false
    private var isPlaying = false
    private var isMuted = false
    private var isFullscreen = false

    private val topBar: LinearLayout
    private val centerPlayBtn: TextView
    private val bottomBar: LinearLayout
    private val titleText: TextView
    private val lineBadge: TextView
    private val muteBtn: TextView
    private val fullscreenBtn: TextView
    private val liveEdgeBtn: TextView
    private val loadingView: ProgressBar
    private val errorText: TextView

    init {
        setBackgroundColor(Color.TRANSPARENT)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        // 顶部栏：频道名 + 线路序号 + 静音 + 全屏
        topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0x66000000)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP)
        }
        titleText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        lineBadge = TextView(context).apply {
            setTextColor(0xFFfe8027.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), 0, dp(8), 0)
        }
        muteBtn = makeBtn("🔊") { toggleMute() }
        fullscreenBtn = makeBtn("⛶") { toggleFullscreen() }
        topBar.addView(titleText)
        topBar.addView(lineBadge)
        topBar.addView(muteBtn)
        topBar.addView(fullscreenBtn)

        // 中央播放按钮
        centerPlayBtn = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            text = "▶"
            gravity = Gravity.CENTER
            setBackgroundColor(0x66000000)
            val size = dp(64)
            layoutParams = LayoutParams(size, size, Gravity.CENTER)
            setOnClickListener {
                if (isPlaying) onPauseToggle() else onPlayToggle()
                show()
            }
        }

        // 底部栏：回到直播
        bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(0x99000000.toInt())
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
        muteBtn.text = if (muted) "🔇" else "🔊"
    }

    fun setFullscreen(fs: Boolean) {
        isFullscreen = fs
        fullscreenBtn.text = if (fs) "⤢" else "⛶"
    }

    fun setPlaying(playing: Boolean) {
        isPlaying = playing
        centerPlayBtn.text = if (playing) "⏸" else "▶"
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
        muteBtn.text = if (isMuted) "🔇" else "🔊"
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

    private fun makeBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onClick(); show() }
        }
    }

    private fun dp(v: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()
    }
}
