---
name: build-and-install
description: 编译 vod 插件 fat AAR → 打包 lyoTVMobile APK → adb 安装到手机
metadata:
  project: lyoTV-vod-plugin
---

# 构建并安装 lyoTV 到手机

完整三步流程：编译 fat AAR → 打包 APK → 安装到手机并启动。

## 步骤

### 1. 编译 fat AAR
在 vod-plugin 项目目录执行：
```bash
cd E:/jqw/programs/lyoTV-vod-plugin
./gradlew :plugin:fatAar --no-daemon
```
确认输出：`✅ Fat AAR 生成完毕: ... plugin-release.aar (XXXX KB)`

### 2. 打包 APK
复制 AAR 并在 Android 集成工程中编译：
```bash
cp E:/jqw/programs/lyoTV-vod-plugin/plugin/build/outputs/aar/plugin-release.aar \
   E:/jqw/programs/Android-SDK@5.07.82603_20260414/HBuilder-Integrate-AS/simpleDemo/libs/

cd E:/jqw/programs/Android-SDK@5.07.82603_20260414/HBuilder-Integrate-AS
./gradlew :simpleDemo:assembleDebug
```
确认输出：`BUILD SUCCESSFUL`

### 3. 安装到手机
```bash
ADB="D:/MyConfiguration/qiwei.jing/AppData/Local/Android/Sdk/platform-tools/adb.exe"
APK="E:/jqw/programs/Android-SDK@5.07.82603_20260414/HBuilder-Integrate-AS/simpleDemo/build/outputs/apk/debug/simpleDemo-debug.apk"

"$ADB" install -r "$APK"
"$ADB" shell monkey -p uni.app.UNI8112C78 -c android.intent.category.LAUNCHER 1
```
确认：`Success` + 手机上应用启动

## 前置检查
- 手机 USB 连接：`adb devices` 应有设备
- 如果 uni-app 前端有改动，需先在 HBuilderX 中导出（确保 `E:/jqw/programs/lyoTVMobile/unpackage/dist/build/app-plus` 存在）
- 如果只是 java 插件代码改动，从步骤 1 开始即可

## 如果失败
- fatAar 失败：检查 `plugin/build.gradle` 的 includeGroups 白名单是否覆盖了新增依赖
- APK 编译失败：检查 AAR 是否已复制到位、Android SDK 路径是否正确
- 安装失败：检查 `adb devices` 确认手机连接、开发者模式是否开启
- 报"原生插件未注册"：检查 `simpleDemo/src/main/assets/dcloud_uniplugins.json` 格式是否标准（必须用 `plugins` 数组嵌套格式，不能是扁平 `pluginsName`+`class`）
