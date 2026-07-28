package com.lyo.media3.player.control

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.widget.ImageView

/**
 * 播放器图片图标按钮。
 *
 * 所有图标来自 drawable 图片资源，不使用 emoji、字体图标或 Canvas 绘制。
 */
class PlayerIconView(
    context: Context,
    icon: Icon,
    iconDp: Int = 24,
) : ImageView(context) {

    enum class Icon { BACK, PLAY, PAUSE, PREVIOUS, NEXT, VOLUME, MUTED, FULLSCREEN, EXIT_FULLSCREEN }

    var icon: Icon = icon
        set(value) {
            field = value
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
            // 当前素材未包含“退出全屏”，先复用展开图标，避免伪造素材。
            Icon.EXIT_FULLSCREEN -> "lyo_ic_fullscreen"
        }

    init {
        scaleType = ScaleType.FIT_CENTER
        setColorFilter(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        val containerWidth = if (iconDp > 24) 72 else 40
        val containerHeight = if (iconDp > 24) 72 else 36
        val horizontalPadding = ((containerWidth - iconDp) / 2).coerceAtLeast(0)
        val verticalPadding = ((containerHeight - iconDp) / 2).coerceAtLeast(0)
        setPadding(
            dp(horizontalPadding),
            dp(verticalPadding),
            dp(horizontalPadding),
            dp(verticalPadding),
        )
        minimumWidth = dp(containerWidth)
        minimumHeight = dp(containerHeight)
        isClickable = true
        isFocusable = true
        applyIcon(icon)
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
