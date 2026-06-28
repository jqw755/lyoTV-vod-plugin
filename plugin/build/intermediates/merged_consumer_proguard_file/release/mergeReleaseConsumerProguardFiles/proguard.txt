# 保持插件核心类不被混淆
-keep class com.fongmi.vod.** { *; }
-keep class com.github.catvod.** { *; }
-keepclassmembers class com.fongmi.vod.VodModule { @io.dcloud.feature.uniapp.annotation.UniJSMethod <methods>; }
