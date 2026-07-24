package com.lyo.media3.player.control

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.lyo.media3.player.player.LyoPlayerView

/**
 * 播放器手势控制器：
 * - 单击：切换控制层显隐（onSingleTap）
 * - 双击：切换播放/暂停（onDoubleTapTogglePlay）-- 双击播放器空白处即切换
 * - 长按：临时倍速（onLongPressSpeedStart / End）
 *
 * 设计约束（docs/android-media3-nvue-player-refactor-plan.md §8.2）：
 * - 不实现双击快退 / 快进（按用户要求：双击 = 播放/暂停）。
 * - 长按倍速值默认 2.0x（与 useVideoPlayer.js 的 long_press_speed 一致）。
 *
 * 注：长按触发通过 SimpleOnGestureListener.onLongPress 实现，
 * 松手通过 onTouchEvent 的 ACTION_UP / ACTION_CANCEL 检测。
 */
class GestureController(
    private val view: LyoPlayerView,
    private val onLongPressSpeedStart: (Float) -> Unit,
    private val onLongPressSpeedEnd: () -> Unit,
    private val onDoubleTapTogglePlay: () -> Unit,
    private val onSingleTap: () -> Unit,
) {
    companion object {
        /** 长按临时倍速值 */
        const val LONG_PRESS_SPEED = 2.0f
    }

    private val handler = Handler(Looper.getMainLooper())
    private var longPressActive = false

    private val detector = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            // 双击播放器空白处：切换播放 / 暂停
            onDoubleTapTogglePlay()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (longPressActive) return
            longPressActive = true
            onLongPressSpeedStart(LONG_PRESS_SPEED)
        }
    })

    init {
        view.playerView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                if (longPressActive) {
                    longPressActive = false
                    onLongPressSpeedEnd()
                }
            }
            detector.onTouchEvent(event)
            true
        }
    }
}
