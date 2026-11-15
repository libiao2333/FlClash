# FlClash · 流体云分支

基于 [FlClash](https://github.com/chen08209/FlClash)（[chen08209/FlClash](https://github.com/chen08209/FlClash)）的个人分支。
除下面列出的改动外，其余代码与上游保持一致。

本分支只做一件事：**把 Android 的流量悬浮通知改造成 Android 16 Live Updates（实时更新），以便在 OPPO ColorOS 上以「流体云」的形式展示上下行速率**，并通过 GitHub Actions 自动打出 APK。

## 改了什么

### 1. 声明实时更新权限

`android/app/src/main/AndroidManifest.xml` 增加：

```xml
<uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS" />
```

这是普通权限，安装即授予，不需要运行时申请。

### 2. 通知改用 ProgressStyle

`android/service/.../modules/NotificationModule.kt`

- `SDK_INT >= 36`（`Build.VERSION_CODES.BAKLAVA`）时改用平台 `Notification.Builder` + `Notification.ProgressStyle`：
  - 进度条按下行速率填充，上行速率用 `ProgressStyle.Point` 打点，两者共用 0–100 刻度；
  - 换算规则：**1MB/s = 100%**，超过饱和（常量 `LIVE_UPDATE_FULL_SCALE`，想让低速也顶满就调小它）；
  - `setOngoing(true)` + `setContentTitle(...)` + 无自定义视图，满足提升为实时更新的硬性条件；
  - `setShortCriticalText("↓3.4MB/s")` 填充状态栏胶囊（系统限制约 7 个字符）；
  - `setRequestPromotedOngoing(true)` 申请提升为流体云，是否真的提升由系统与用户设置决定，
    用 `NotificationManager.canPostPromotedNotifications()` 判断资格。
- `SDK_INT <= 35` 时完全走原来的 `NotificationCompat.Builder` 逻辑，行为与改造前一致。

### 3. 暴露原始流量数值

`android/service/.../models/Traffic.kt`

`getSpeedTrafficText()` 改为 `getSpeedTraffic(): Traffic?`，直接返回上下行字节数（进度条需要数值）；
新增 `Traffic.shortSpeedText` 供状态栏胶囊使用。`speedText` 格式未变。

### 4. 自动构建

`.github/workflows/build-android.yml`

推送到 `main` 即触发：JDK 17 + Go 1.27.1 + Flutter 3.47.6 + NDK r28c，
拉取子模块、`flutter build apk --release --target-platform android-arm64`，
产物 `build/app/outputs/flutter-apk/app-release.apk` 上传为 Artifact。

需要在仓库 Secrets 里配置 `KEYSTORE`（base64）、`KEY_ALIAS`、`STORE_PASSWORD`、`KEY_PASSWORD`
才会使用正式签名；不配则用 debug 签名，包名为 `com.follow.clash.dev`。

## 已知限制

- 「请求提升」不等于「一定提升」：还需用户在系统设置里允许该应用发布实时更新，且依赖 ROM 实现
  （ColorOS 需 Android 16 / ColorOS 16 及以上，部分版本只渲染不提升）。
- 现有逻辑在灭屏后停止刷新通知，锁屏与胶囊上停留的是灭屏前的最后一帧数据。
- 只构建 `android-arm64` 一种 ABI。

## 许可

上游项目以 GPL-3.0 发布，本分支沿用同一许可，见 [LICENSE](LICENSE)。
