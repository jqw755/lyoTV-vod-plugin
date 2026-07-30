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
    private val centerControls: LinearLayout
    private val titleText: TextView

    private val speedOptions = floatArrayOf(1f, 1.5f, 1.75f, 2f, 3f, 4f)
    private var speedPopup: LinearLayout? = null

    init {
        setBackgroundColor(Color.TRANSPARENT)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        // 顶部栏：返回 + 标题
        topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(6), dp(21), dp(6))
            background = topGradient()
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP)
        }
        titleText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        backBtn = PlayerIconView(context, PlayerIconView.Icon.BACK, 24).apply {
            setOnClickListener {
                if (isFullscreen) onFullscreenToggle(false) else onBack()
                show()
            }
        }
        topBar.addView(backBtn)
        topBar.addView(titleText)

        // 中央播放按钮：只显示图标，不添加圆形/半透明遮罩
        centerPlayBtn = PlayerIconView(context, PlayerIconView.Icon.PLAY, 42).apply {
            setOnClickListener {
                if (isPlaying) onPauseToggle() else onPlayToggle()
                show()
            }
        }

        // 底部栏：上方进度，下方常用操作
        bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 左右使用相同边距；全屏安全区由外层容器统一补偿，避免竖屏操作栏偏左。
            setPadding(dp(8), dp(4), dp(8), 0)
            background = bottomGradient()
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        prevBtn = makeIconBtn(PlayerIconView.Icon.PREVIOUS) { onPrevEpisode() }
        nextBtn = makeIconBtn(PlayerIconView.Icon.NEXT) { onNextEpisode() }
        muteBtn = makeIconBtn(PlayerIconView.Icon.VOLUME) { toggleMute() }
        fullscreenBtn = makeIconBtn(PlayerIconView.Icon.FULLSCREEN) { toggleFullscreen() }
        rotateBtn = makeIconBtn(PlayerIconView.Icon.ROTATE) { onRotate() }
        lockBtn = makeIconBtn(PlayerIconView.Icon.UNLOCK) { toggleLock() }
        centerControls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
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
        val trackHeight = dp(2)
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
        val thumbDiameter = dp(14)
        val thumbDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setSize(thumbDiameter, thumbDiameter)
        }
        seekBar = SeekBar(context).apply {
            // 14dp 圆点只需要 16dp 布局高度；避免把整个底栏向播放器中央顶起。
            layoutParams = LinearLayout.LayoutParams(0, dp(16), 1f)
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
                    setBounds(0, 0, dp(33), dp(33))
                }
                setCompoundDrawablesRelative(drawable, null, null, null)
            }
            compoundDrawablePadding = dp(4)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            // 竖向 padding 会把操作行撑高，并连带把上方进度条顶向播放器中央。
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { toggleSpeedPopup() }
        }
        val progressRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            translationY = dp(9).toFloat()
            addView(currentTimeText)
            addView(seekBar)
            addView(durationText)
        }
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            translationY = dp(10).toFloat()
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(speedBtn)
            addView(muteBtn)
            addView(fullscreenBtn)
        }
        bottomBar.addView(progressRow)
        bottomBar.addView(actionRow)

        addView(topBar)
        addView(centerControls)
        addView(bottomBar)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ).apply { marginEnd = dp(4) }
            addView(lockBtn)
            addView(rotateBtn)
        })

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

    fun setPlaying(playing: Boolean) {
        isPlaying = playing
        centerPlayBtn.icon = if (playing) PlayerIconView.Icon.PAUSE else PlayerIconView.Icon.PLAY
    }

    fun showLoading(show: Boolean) {
        centerPlayBtn.visibility = if (show) View.GONE else View.VISIBLE
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
        topBar.visibility = if (isLocked) View.GONE else View.VISIBLE
        centerControls.visibility = if (isLocked) View.GONE else View.VISIBLE
        bottomBar.visibility = if (isLocked) View.GONE else View.VISIBLE
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
        val iconDp = when (icon) {
            PlayerIconView.Icon.PREVIOUS,
            PlayerIconView.Icon.NEXT -> 20
            PlayerIconView.Icon.VOLUME -> 25
            PlayerIconView.Icon.MUTED -> 26
            PlayerIconView.Icon.FULLSCREEN,
            PlayerIconView.Icon.EXIT_FULLSCREEN -> 22
            PlayerIconView.Icon.LOCK,
            PlayerIconView.Icon.UNLOCK,
            PlayerIconView.Icon.ROTATE -> 24
            else -> 18
        }
        return PlayerIconView(context, icon, iconDp).apply {
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
