package com.lyo.media3.player.control

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import com.lyo.media3.player.player.LyoPlayerView
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
    private val onMuteToggle: (Boolean) -> Unit,
    private val onFullscreenToggle: (Boolean) -> Unit,
) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val autoHideMs = 4000L
    private var autoHideRunnable: Runnable? = null
    private var isShowing = false
    private var isPlaying = false
    private var isMuted = false
    private var isFullscreen = false
    private var currentSpeed = 1f

    // 控件
    private val centerPlayBtn: TextView
    private val bottomBar: LinearLayout
    private val topBar: LinearLayout
    private val seekBar: SeekBar
    private val currentTimeText: TextView
    private val durationText: TextView
    private val speedBtn: TextView
    private val prevBtn: TextView
    private val nextBtn: TextView
    private val muteBtn: TextView
    private val fullscreenBtn: TextView
    private val titleText: TextView

    private val speedOptions = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
    private var speedPopup: LinearLayout? = null

    init {
        setBackgroundColor(Color.TRANSPARENT)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        // 顶部栏：标题 + 静音 + 全屏
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
        muteBtn = makeBtn("🔇") { toggleMute() }
        fullscreenBtn = makeBtn("⛶") { toggleFullscreen() }
        topBar.addView(titleText)
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

        // 底部栏：上一集 / 进度条+时间 / 倍速 / 下一集
        bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(0x99000000.toInt())
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        prevBtn = makeBtn("⏮") { onPrevEpisode() }
        currentTimeText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            text = "00:00"
            setPadding(dp(4), 0, dp(4), 0)
        }
        seekBar = SeekBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar?) { cancelAutoHide() }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val p = sb?.progress ?: 0
                    val ratio = p.toFloat() / 1000f
                    val target = (ratio * (sb?.tag as? Long ?: 0L)).toLong()
                    onSeekTo(target)
                    show()
                }
            })
        }
        durationText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            text = "00:00"
            setPadding(dp(4), 0, dp(4), 0)
        }
        nextBtn = makeBtn("⏭") { onNextEpisode() }
        speedBtn = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = "1.0x"
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { toggleSpeedPopup() }
        }
        bottomBar.addView(prevBtn)
        bottomBar.addView(currentTimeText)
        bottomBar.addView(seekBar)
        bottomBar.addView(durationText)
        bottomBar.addView(nextBtn)
        bottomBar.addView(speedBtn)

        addView(topBar)
        addView(centerPlayBtn)
        addView(bottomBar)

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
        muteBtn.text = if (muted) "🔇" else "🔊"
    }

    fun setFullscreen(fs: Boolean) {
        isFullscreen = fs
        fullscreenBtn.text = if (fs) "⤢" else "⛶"
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed
        speedBtn.text = String.format(Locale.US, "%.1fx", speed)
    }

    fun setPlaying(playing: Boolean) {
        isPlaying = playing
        centerPlayBtn.text = if (playing) "⏸" else "▶"
    }

    fun updateProgress(positionMs: Long, durationMs: Long, bufferedMs: Long) {
        if (durationMs <= 0) {
            seekBar.tag = 0L
            return
        }
        seekBar.tag = durationMs
        val posRatio = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        seekBar.progress = (posRatio * 1000).toInt()
        val bufRatio = (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)
        seekBar.secondaryProgress = (bufRatio * 1000).toInt()
        currentTimeText.text = formatTime(positionMs)
        durationText.text = formatTime(durationMs)
    }

    /** 显示控制层；4s 后自动隐藏 */
    fun show() {
        visibility = View.VISIBLE
        isShowing = true
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
        muteBtn.text = if (isMuted) "🔇" else "🔊"
        onMuteToggle(isMuted)
        show()
    }

    private fun toggleFullscreen() {
        onFullscreenToggle(!isFullscreen)
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
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        speedOptions.forEach { s ->
            val tv = TextView(context).apply {
                setTextColor(if (s == currentSpeed) 0xFFfe8027.toInt() else Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                text = String.format(Locale.US, "%.1fx", s)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener {
                    currentSpeed = s
                    onSpeedChange(s)
                    speedBtn.text = String.format(Locale.US, "%.1fx", s)
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

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }
}
