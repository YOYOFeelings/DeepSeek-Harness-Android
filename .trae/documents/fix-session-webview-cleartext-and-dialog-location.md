# 修复「打开会话无法加载网页」+ 定位启动引擎弹窗代码

## 一、Summary
软件内「打开会话」用的是 [SessionActivity.kt](file:///workspace/dsh-mobile-apk-yoyo/app/src/main/java/com/yoyo/dshmobile/shell/engine/SessionActivity.kt#L80) 的 `WebView.loadUrl("http://127.0.0.1:3080")`。App `targetSdk = 34`（≥28），而 manifest 未声明明文流量豁免，**Android 9+ 默认禁止 cleartext HTTP**，所以 WebView 加载失败（触发 `onReceivedError` → 显示重试条，重试仍失败）。这是「无法正常加载网页」的根本原因。

修复方式：为本地回环地址放行明文流量（新增 `network_security_config.xml` + 在 application 上声明 `android:networkSecurityConfig`）。

另外：启动引擎弹窗代码位置整理如下，供你自行改样式；本计划**不改动**弹窗外观，只负责修复会话加载。

## 二、Current State Analysis
- 会话页 [SessionActivity.kt](file:///workspace/dsh-mobile-apk-yoyo/app/src/main/java/com/yoyo/dshmobile/shell/engine/SessionActivity.kt)：创建 `WebView`（`javaScriptEnabled=true`、`domStorageEnabled=true`），行 80 `loadUrl("http://127.0.0.1:3080")`；`shouldOverrideUrlLoading` 把非 127.0.0.1 外链交系统浏览器；`onReceivedError` 显示重试条。
- [app/src/main/AndroidManifest.xml](file:///workspace/dsh-mobile-apk-yoyo/app/src/main/AndroidManifest.xml#L27-L33)：`<application>` **没有** `android:usesCleartextTraffic`、**没有** `android:networkSecurityConfig`；`app/build.gradle.kts` 中 `targetSdk=34, minSdk=26`。→ 明文 HTTP 默认被禁。
- 引擎进程对讲端口：`EngineProcess.ENGINE_HOST/ENGINE_PORT`（127.0.0.1:3080），Node 侧为纯 HTTP，必须放行明文。
- 无 `res/xml/*`，也不存在网络配置资源。

### 启动引擎弹窗代码位置（供你修改，本计划不改）
全部在 [ConversationScreen.kt](file:///workspace/dsh-mobile-apk-yoyo/app/src/main/java/com/yoyo/dshmobile/shell/ui/screen/ConversationScreen.kt)：
- `doStart()`：L190–L232 —— 触发启动流程、控制弹窗开/关/状态流转。
- `showStartDialog()`：L239–L320 —— **弹窗本体构造**（转圈 spinner、状态行、查看日志 toggle/日志区），要改样式主要动这里。
- `showStartNotReady()`：L323–L332 —— rootfs 未就绪时的「去更新/关闭」终态弹窗。
- `StartUi` 持有类：L568。
- 弹窗容器：`themedDialog(...)` 在 [Ui.kt:107](file:///workspace/dsh-mobile-apk-yoyo/app/src/main/java/com/yoyo/dshmobile/shell/ui/Ui.kt#L107)（圆角/配色走 dh_ 主题令牌）。
- 文案：`app/src/main/res/values/strings.xml` 下 `engine_start_dialog_*`、`engine_start_failed_inline`、`engine_start_stuck_inline` 等。

## 三、Proposed Changes
改动极小、范围锁定为「会话加载放行明文」：

### 1. 新增网络配置资源 `app/src/main/res/xml/network_security_config.xml`
内容：
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
  <base-config cleartextTrafficPermitted="true" />
</network-security-config>
```
理由：本 App 的 WebView 仅访问 `127.0.0.1:3080` 一个明文端点（外部链接已被 `shouldOverrideUrlLoading` 交给系统浏览器，更新/镜像均为 HTTPS），`base-config` 全量放行最简且安全足够。若后续希望更收敛，可改为 domain-config 限定 `<domain>127.0.0.1</domain>` + `<domain>localhost</domain>`。

### 2. 在 `app/src/main/AndroidManifest.xml` 的 `<application>` 上声明
```xml
android:networkSecurityConfig="@xml/network_security_config"
```
**不改动** `SessionActivity`、引擎启动、更新、镜像等任何其它代码。

## 四、Assumptions & Decisions
- 决定：用 `network_security_config`（而非 `android:usesCleartextTraffic="true"`）。二者均可生效；`base-config` 是官方更推荐、单一配置入口，符合「统一配置源」风格，且便于后续收紧到仅 localhost。
- 假设：Node 侧 3080 服务确为纯 HTTP（当前 `loadUrl` 用 `http://`），因此必须放行明文。假设引擎正常运行后监听 3080。
- 范围：仅修复「网页无法加载」。启动弹窗外观由你自行修改（已在第二节给出精确位置），我不代改。`doOpen()` 是否在引擎未启动时自动拉起引擎属于额外增强，本计划不做（避免越界）。

## 五、Verification
- 代码：确认 `network_security_config.xml` 存在且 manifest 已引用。
- 构建：`./gradlew :app:assembleRelease --no-daemon --max-workers=1 -PenableNative=true`，产出 `deepseek-harness-1.0-release.apk`；`apksigner verify --print-certs` 签名 SHA-256 前缀 `5696…25ff`（规则 8.8 一致）。
- 运行时：安装后①启动引擎→②点「打开会话」，WebView 应能正常加载 `127.0.0.1:3080`，不再出现重试条/ERR_CLEARTEXT_NOT_PERMITTED。
- 交付：给出 APK 本地路径与预览下载链接。
- 回归自检：外部链接仍交系统浏览器打开；镜像/更新（HTTPS）不受影响；引擎启动逻辑、start 弹窗未改动。