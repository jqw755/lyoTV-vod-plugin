# proguard-rules.pro
# 插件模块不开启混淆（minifyEnabled=false），这里仅声明规则供宿主参考。

# Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# 组件入口（uni-app 通过反射注册）
-keep class com.lyo.media3.player.** { *; }

# uni-app SDK
-keep class io.dcloud.feature.uniapp.** { *; }
