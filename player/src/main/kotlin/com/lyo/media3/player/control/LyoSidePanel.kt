package com.lyo.media3.player.control

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 通用右侧侧边栏组件。
 *
 * - 从播放器右侧向左滑出
 * - 设置最大宽度，不可全盖住播放器
 * - 半透明背景、白色文字
 * - 点击外部关闭
 *
 * 内部布局由具体业务外部控制：通过 [setContent] 注入业务区段（选集列表 / 倍速列表等）。
 */
class LyoSidePanel(
    context: Context,
    private val maxWidthDp: Int = 200,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {

    private val container: LinearLayout
    private val scrim: View
    private var isShowing = false
    private var fixedWidthPx: Int? = null

    init {
        setBackgroundColor(Color.TRANSPARENT)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        // 半透明遮罩：点击关闭
        scrim = View(context).apply {
            setBackgroundColor(0x66000000)
            setOnClickListener { dismiss() }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(scrim)

        // 侧边栏容器：右侧锚定、竖向、半透明背景
        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                setColor(0xCC000000.toInt())
                cornerRadius = 0f
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, Gravity.END)
        }
        addView(container)
    }

    fun configure(widthPx: Int? = null, backgroundColor: Int = 0xCC000000.toInt()) {
        fixedWidthPx = widthPx
        container.background = GradientDrawable().apply {
            setColor(backgroundColor)
            cornerRadius = 0f
        }
    }

    /** 注入业务区段内容。 */
    fun setContent(contentView: View) {
        container.removeAllViews()
        container.addView(contentView)
    }

    fun show(host: ViewGroup) {
        if (isShowing) return
        // 限制最大宽度
        val maxWidthPx = dp(maxWidthDp)
        val hostWidth = host.width
        val panelWidth = fixedWidthPx?.coerceAtMost(hostWidth)
            ?: hostWidth.coerceAtMost(maxWidthPx)
        val params = container.layoutParams as LayoutParams
        params.width = panelWidth
        container.layoutParams = params

        // 初始位置：右侧屏幕外
        container.translationX = panelWidth.toFloat()
        scrim.alpha = 0f

        (parent as? ViewGroup)?.removeView(this)
        host.addView(this)
        isShowing = true

        // 滑入动画
        container.animate()
            .translationX(0f)
            .setDuration(200)
            .start()
        scrim.animate()
            .alpha(1f)
            .setDuration(200)
            .start()
    }

    fun dismiss() {
        if (!isShowing) return
        val panelWidth = container.width
        container.animate()
            .translationX(panelWidth.toFloat())
            .setDuration(200)
            .withEndAction {
                (parent as? ViewGroup)?.removeView(this)
                isShowing = false
                onDismiss()
            }
            .start()
        scrim.animate()
            .alpha(0f)
            .setDuration(200)
            .start()
    }

    fun isPanelShowing(): Boolean = isShowing

    private fun dp(v: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()
    }
}
