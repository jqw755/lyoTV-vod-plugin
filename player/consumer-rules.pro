# consumer-rules.pro
# 给宿主 APK 用的混淆规则；插件本身不混淆，宿主可以混淆但需保留以下类。

# Media3 / ExoPlayer 公开 API
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Lyo-Media3Player 组件入口
-keep class com.lyo.media3.player.** { *; }

# uni-app 原生组件 SDK 接口
-keep class io.dcloud.feature.uniapp.ui.component.** { *; }
-keep class io.dcloud.feature.uniapp.annotation.** { *; }

# fastjson（宿主基座已自带，这里仅防混淆）
-keep class com.alibaba.fastjson.** { *; }
