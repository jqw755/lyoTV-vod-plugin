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
import android.widget.ScrollView
import android.widget.TextView
import com.lyo.media3.player.player.LyoPlayerView
import com.lyo.media3.player.player.PlayerResizeMode

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
    private var currentScale = PlayerResizeMode.fromIndex(
        context.getSharedPreferences("lyo_player", Context.MODE_PRIVATE).getInt(
            "scale_live",
            context.getSharedPreferences("lyo_player", Context.MODE_PRIVATE).getInt("scale_vod", 0),
        )
    )
    private var sidePanel: LyoSidePanel? = null
    private var fullscreenSafeHorizontal = 0

    private val topBar: LinearLayout
    private val topChrome: FrameLayout
    private val centerPlayBtn: PlayerIconView
    private val bottomBar: LinearLayout
    private val bottomChrome: FrameLayout
    private val titleText: TextView
    private val lineBadge: TextView
    private val muteBtn: PlayerIconView
    private val fullscreenBtn: PlayerIconView
    private val backBtn: PlayerIconView
    private val liveEdgeBtn: TextView
    private val scaleBtn: TextView
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
            background = null
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
            background = null
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        liveEdgeBtn = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = "回到直播"
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onGoLiveEdge(); show() }
        }
        scaleBtn = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = currentScale.label
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { openScalePanel() }
            visibility = View.GONE
        }
        bottomBar.addView(liveEdgeBtn)
        bottomBar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        bottomBar.addView(scaleBtn)
        bottomBar.addView(muteBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(14) })
        bottomBar.addView(fullscreenBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(14) })

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

        topChrome = FrameLayout(context).apply {
            background = topGradient()
            clipChildren = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP)
            addView(topBar)
        }
        bottomChrome = FrameLayout(context).apply {
            background = bottomGradient()
            clipChildren = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            addView(bottomBar)
        }

        addView(topChrome)
        addView(centerPlayBtn)
        addView(bottomChrome)
        addView(loadingView)
        addView(errorText)
        hostView.setResizeMode(currentScale)

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

    fun setFullscreenSafePadding(horizontal: Int) {
        fullscreenSafeHorizontal = horizontal.coerceAtLeast(0)
        setPadding(0, 0, 0, 0)
        applyFullscreenChromeLayout()
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
        scaleBtn.visibility = if (fs) View.VISIBLE else View.GONE
        topBar.translationY = if (fs) dp(8).toFloat() else -dp(3).toFloat()
        bottomBar.translationY = if (fs) -dp(8).toFloat() else 0f
        applyFullscreenChromeLayout()
    }

    private fun applyFullscreenChromeLayout() {
        val horizontalPadding = dp(12) + if (isFullscreen) fullscreenSafeHorizontal else 0
        topBar.setPadding(horizontalPadding, dp(6), horizontalPadding, dp(6))
        bottomBar.setPadding(horizontalPadding, dp(4), horizontalPadding, 0)
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

    private fun openScalePanel() {
        val panel = sidePanel ?: LyoSidePanel(
            context,
            maxWidthDp = 240,
            onDismiss = {
                sidePanel = null
                show()
            },
        ).also { sidePanel = it }
        panel.configure(
            widthPx = (hostView.width * 200f / 750f).toInt() + dp(30),
            backgroundColor = Color.WHITE,
        )
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        PlayerResizeMode.values().forEach { mode ->
            val tv = TextView(context).apply {
                val selected = mode == currentScale
                setTextColor(if (selected) Color.WHITE else 0xFF333333.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                text = mode.label
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(if (selected) 0xFFfe8027.toInt() else 0xFFF2F2F2.toInt())
                    cornerRadius = dp(6).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    currentScale = mode
                    scaleBtn.text = mode.label
                    hostView.setResizeMode(mode)
                    context.getSharedPreferences("lyo_player", Context.MODE_PRIVATE)
                        .edit().putInt("scale_live", mode.index).apply()
                    sidePanel?.dismiss()
                }
            }
            list.addView(tv)
        }
        scroll.addView(list)
        root.addView(scroll)
        panel.setContent(root)
        panel.show(hostView)
        cancelAutoHide()
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
