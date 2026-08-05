package com.lyo.media3.player.control

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.widget.ImageView

/**
 * 播放器图片图标按钮。
 *
 * 所有图标来自 drawable 图片资源，不使用 emoji、字体图标或 Canvas 绘制。
 */
class PlayerIconView(
    context: Context,
    icon: Icon,
) : ImageView(context) {

    enum class Icon {
        BACK, PLAY, PAUSE, PREVIOUS, NEXT, VOLUME, MUTED,
        FULLSCREEN, EXIT_FULLSCREEN, LOCK, UNLOCK, ROTATE
    }

    private var desiredWidthPx = 0
    private var desiredHeightPx = 0

    var icon: Icon = icon
        set(value) {
            field = value
            applySize(value)
            applyIcon(value)
        }

    private val Icon.drawableName: String
        get() = when (this) {
            Icon.BACK -> "lyo_ic_back"
            Icon.PLAY -> "lyo_ic_play"
            Icon.PAUSE -> "lyo_ic_pause"
            Icon.PREVIOUS -> "lyo_ic_previous"
            Icon.NEXT -> "lyo_ic_next"
            Icon.VOLUME -> "lyo_ic_volume"
            Icon.MUTED -> "lyo_ic_muted"
            Icon.FULLSCREEN -> "lyo_ic_fullscreen"
            Icon.EXIT_FULLSCREEN -> "lyo_ic_exit_fullscreen"
            Icon.LOCK -> "lyo_ic_lock"
            Icon.UNLOCK -> "lyo_ic_unlock"
            Icon.ROTATE -> "lyo_ic_rotate"
        }

    init {
        scaleType = ScaleType.FIT_CENTER
        setColorFilter(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        applySize(icon)
        isClickable = true
        isFocusable = true
        applyIcon(icon)
    }

    private fun applySize(value: Icon) {
        // 中央播放按钮保持 40dp；
        val iconDp = when (value) {
            Icon.PLAY, Icon.PAUSE -> 48
            Icon.PREVIOUS, Icon.NEXT -> 20
            Icon.VOLUME -> 22
            Icon.MUTED -> 25
            Icon.FULLSCREEN, Icon.EXIT_FULLSCREEN -> 20
            // 音量按钮创建时是 VOLUME，切到 MUTED 后仍沿用构造时的 baseIconDp。
            // 静音态必须在这里单独放大，修改 makeIconBtn(MUTED) 不会影响已创建的按钮。
            else -> 24
        }
        // 切集按钮用正方形容器，配合圆角=半边长可呈现完美圆形背景。
        val isEpisode = value == Icon.PREVIOUS || value == Icon.NEXT
        val isCenterPlay = value == Icon.PLAY || value == Icon.PAUSE
        val containerWidth = when {
            isEpisode -> 36
            isCenterPlay -> 72
            else -> 40
        }
        val containerHeight = when {
            isEpisode -> 36
            isCenterPlay -> 72
            else -> 36
        }
        desiredWidthPx = dp(containerWidth)
        desiredHeightPx = dp(containerHeight)
        val horizontalPadding = ((containerWidth - iconDp) / 2).coerceAtLeast(0)
        val verticalPadding = ((containerHeight - iconDp) / 2).coerceAtLeast(0)
        // 播放三角形虽然矢量边界居中，但视觉重心偏左；仅将图案向右补偿 2dp。
        // 切换为暂停时恢复对称 padding，按钮容器本身始终保持居中且尺寸不变。
        val opticalOffset = if (value == Icon.PLAY) 2 else 0
        // 必须固定按钮容器尺寸。仅设置 minimumWidth/minimumHeight 时，
        // ImageView 会按 PNG 固有尺寸继续扩张，padding 无法把图标限制到 iconDp。
        setPadding(
            dp(horizontalPadding + opticalOffset),
            dp(verticalPadding),
            dp((horizontalPadding - opticalOffset).coerceAtLeast(0)),
            dp(verticalPadding),
        )
        minimumWidth = dp(containerWidth)
        minimumHeight = dp(containerHeight)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            View.resolveSize(desiredWidthPx, widthMeasureSpec),
            View.resolveSize(desiredHeightPx, heightMeasureSpec),
        )
    }

    private fun applyIcon(value: Icon) {
        val resId = resolvePlayerDrawable(context, value.drawableName)
        if (resId != 0) setImageResource(resId) else setImageDrawable(null)
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()
}

/**
 * The custom fat-AAR task merges resources and classes.jar files, but it does not
 * package the player module's generated R class. Resolve merged resources through
 * the host application's package to avoid crashing controller construction.
 */
internal fun resolvePlayerDrawable(context: Context, name: String): Int =
    context.resources.getIdentifier(name, "drawable", context.packageName)
