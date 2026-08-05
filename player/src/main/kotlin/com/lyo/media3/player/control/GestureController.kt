package com.lyo.media3.player.control

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.provider.Settings
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import com.lyo.media3.player.player.LyoPlayerView
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 播放器手势控制器：
 * - 单击：切换控制层显隐
 * - 双击：切换播放/暂停
 * - 长按：临时倍速
 * - 水平滑动：点播快退/快进，松手后提交 seek
 * - 左侧垂直滑动：调节当前窗口亮度
 * - 右侧垂直滑动：调节媒体音量
 */
class GestureController(
    private val view: LyoPlayerView,
    private val onLongPressSpeedStart: (Float) -> Unit,
    private val onLongPressSpeedEnd: () -> Unit,
    private val getLongPressSpeed: () -> Float,
    private val onDoubleTapTogglePlay: () -> Unit,
    private val onSingleTap: () -> Unit,
    private val canHorizontalSeek: () -> Boolean,
    private val canVerticalAdjust: () -> Boolean,
    private val onHorizontalSeekPreview: (Long) -> Unit,
    private val onHorizontalSeekEnd: (Long) -> Unit,
    private val onHorizontalSeekCancel: () -> Unit,
    private val onVolumeAdjusted: (Int) -> Unit,
) {
    companion object {
        /** 对齐 Fongmi mobile：水平移动 1px 对应 50ms。 */
        const val SEEK_MS_PER_PX = 50L
        const val DIRECTION_THRESHOLD_DP = 20
        const val SYSTEM_EDGE_GUARD_DP = 32
        const val VERTICAL_ADJUST_RANGE = 0.6f
    }

    private var longPressActive = false
    private var directionResolved = false
    private var gestureMode = GestureMode.NONE
    private var seekDeltaMs = 0L
    private var initialBrightness = 0.5f
    private var initialVolume = 0

    private val activity: Activity? = findActivity(view.context)
    private val audioManager =
        view.context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private enum class GestureMode {
        NONE,
        HORIZONTAL_SEEK,
        BRIGHTNESS,
        VOLUME,
    }

    private val detector = GestureDetector(
        view.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                directionResolved = false
                gestureMode = GestureMode.NONE
                seekDeltaMs = 0L
                initialBrightness = currentBrightness()
                initialVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (e1 == null || longPressActive) return true
                val deltaX = e2.x - e1.x
                // 向上滑为正值（调高），向下滑为负值（调低）。
                val deltaY = e1.y - e2.y
                if (!directionResolved) {
                    if (hypot(deltaX, deltaY) < dp(DIRECTION_THRESHOLD_DP)) return true
                    directionResolved = true
                    gestureMode = if (abs(deltaX) >= abs(deltaY)) {
                        if (canHorizontalSeek()) GestureMode.HORIZONTAL_SEEK else GestureMode.NONE
                    } else if (canVerticalAdjust()) {
                        // 从屏幕顶部下拉是查看系统状态栏的系统手势，不能同时调节亮度/音量。
                        if (e1.y <= dp(SYSTEM_EDGE_GUARD_DP) ||
                            e1.y >= view.height - dp(SYSTEM_EDGE_GUARD_DP)
                        ) {
                            GestureMode.NONE
                        } else if (e1.x < view.width / 2f) {
                            GestureMode.BRIGHTNESS
                        } else {
                            GestureMode.VOLUME
                        }
                    } else {
                        GestureMode.NONE
                    }
                }
                when (gestureMode) {
                    GestureMode.HORIZONTAL_SEEK -> {
                        seekDeltaMs = (deltaX * SEEK_MS_PER_PX).toLong()
                        onHorizontalSeekPreview(seekDeltaMs)
                    }

                    GestureMode.BRIGHTNESS -> adjustBrightness(deltaY)
                    GestureMode.VOLUME -> adjustVolume(deltaY)
                    GestureMode.NONE -> Unit
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                onDoubleTapTogglePlay()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (longPressActive || directionResolved) return
                longPressActive = true
                onLongPressSpeedStart(getLongPressSpeed())
            }
        },
    )

    init {
        view.setPlayerGestureTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    if (gestureMode == GestureMode.HORIZONTAL_SEEK) {
                        onHorizontalSeekEnd(seekDeltaMs)
                    } else if (gestureMode == GestureMode.BRIGHTNESS ||
                        gestureMode == GestureMode.VOLUME
                    ) {
                        view.hideVerticalGestureHint()
                    }
                    endLongPressIfNeeded()
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (gestureMode == GestureMode.HORIZONTAL_SEEK) {
                        onHorizontalSeekCancel()
                    } else {
                        view.hideVerticalGestureHint()
                    }
                    endLongPressIfNeeded()
                }
            }
            detector.onTouchEvent(event)
            true
        }
    }

    private fun endLongPressIfNeeded() {
        if (!longPressActive) return
        longPressActive = false
        onLongPressSpeedEnd()
    }

    private fun adjustBrightness(deltaY: Float) {
        val activity = activity ?: return
        val height = view.height.coerceAtLeast(1)
        val brightness =
            (initialBrightness + deltaY * VERTICAL_ADJUST_RANGE / height).coerceIn(0f, 1f)
        val attributes = activity.window.attributes
        attributes.screenBrightness = brightness
        activity.window.attributes = attributes
        view.showVerticalGestureHint("亮度", (brightness * 100f).toInt())
    }

    private fun adjustVolume(deltaY: Float) {
        val manager = audioManager ?: return
        val height = view.height.coerceAtLeast(1)
        val maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volume = (initialVolume + deltaY * VERTICAL_ADJUST_RANGE / height * maxVolume)
            .toInt()
            .coerceIn(0, maxVolume)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        val percent = volume * 100 / maxVolume
        onVolumeAdjusted(percent)
        view.showVerticalGestureHint("音量", percent)
    }

    private fun currentBrightness(): Float {
        val windowBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
        if (windowBrightness >= 0f) return windowBrightness.coerceIn(0f, 1f)
        val systemBrightness = Settings.System.getInt(
            view.context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            128,
        )
        return (systemBrightness / 255f).coerceIn(0f, 1f)
    }

    private fun dp(value: Int): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        view.resources.displayMetrics,
    )

    private fun findActivity(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val base = current.baseContext
            if (base === current) return null
            current = base
        }
        return current as? Activity
    }
}
