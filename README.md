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
  - 大标题固定 `FlClash`，副标题横排 `↑1.2MB/s   ↓3.4MB/s`；
  - 进度条优先表示**套餐流量已用比例**（来自订阅的 `subscription-userinfo`），拿不到套餐信息时回退为按下行速率填充、上行用 `ProgressStyle.Point` 打点（1MB/s = 100%，常量 `LIVE_UPDATE_FULL_SCALE`）；
  - `setOngoing(true)` + `setContentTitle(...)` + 无自定义视图，满足提升为实时更新的硬性条件；
  - `setShortCriticalText("↑1.2M ↓3.4M")` 填充状态栏胶囊，速率定宽（figure space 补位）避免胶囊宽度跳动；
  - `setRequestPromotedOngoing(true)` 申请提升为流体云，是否真的提升由系统与用户设置决定，
    用 `NotificationManager.canPostPromotedNotifications()` 判断资格；
  - 操作按钮为纯文字「断开」（`Notification.Action` 不传图标）。
- `SDK_INT <= 35` 或关闭实时更新开关时，完全走原来的 `NotificationCompat.Builder` 逻辑。

### 3. 暴露原始流量数值

`android/service/.../models/Traffic.kt`

`getSpeedTrafficText()` 改为 `getSpeedTraffic(): Traffic?`，直接返回上下行字节数；
新增 `directionalText`（卡片副标题）与 `formatRate()`（胶囊定宽短速率）。`speedText` 格式未变。

### 3.1 设置项

设置 → 通用 → 启动与后台（仅 Android）新增：

- 实时更新通知（流体云）：总开关；
- 状态栏同时显示上传速率；
- 速率数字定宽（防跳动）。

三个开关经 `SharedState` 下发到服务，改完立即生效；低于 API 36 的设备忽略这些开关。

### 3.2 控制中心磁贴

`TileService` 点击直接切换服务，不再经过 `QuickActionActivity`，行为与 Clash Meta 磁贴一致。


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

上游项目以 **GPL-3.0** 发布，本分支沿用同一许可，完整条款见 [LICENSE](LICENSE)。

依 GPL-3.0 第 5 条，此处明确声明：本仓库是对 `chen08209/FlClash` 的修改版本（2026-11-21 起），
修改内容即上文所列的实时更新通知、设置项、磁贴行为与构建工作流，其余代码与上游一致。
本分支同样以 GPL-3.0 提供完整源码，任何人可自由使用、修改与再分发，但再分发时须保留本许可与上述修改声明。
