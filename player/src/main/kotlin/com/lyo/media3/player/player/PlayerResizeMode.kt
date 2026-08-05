package com.lyo.media3.player.player

/** 与 Fongmi select_scale / PlayerView.setResizeMode(index) 一致的五种模式。 */
enum class PlayerResizeMode(val index: Int, val label: String) {
    ORIGINAL(0, "原始"),
    RATIO_16_9(1, "16:9"),
    RATIO_4_3(2, "4:3"),
    FILL(3, "填充"),
    ZOOM(4, "裁剪");

    companion object {
        fun fromIndex(index: Int): PlayerResizeMode = values().firstOrNull { it.index == index } ?: ORIGINAL
    }
}
