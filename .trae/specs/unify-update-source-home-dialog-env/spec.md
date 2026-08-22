# 统一更新源 + 主页更新弹窗 + 环境工具补齐 Spec

## Why
当前下载逻辑分散：
- App 自身 APK 更新走固定 GitHub 地址（`UpdateManager`），不走镜像，国内大概率超时；
- 只有「引擎 runtime」更新走镜像（`EngineMirrors` 25 源，仅 ConversationScreen 可选源）；
- 主页检测到新版本只显示黄色横幅，不弹更新弹窗，用户很容易漏看；
- 引擎环境工具（node / dsh bin.js / termux-exec / 关键 so）缺失时提示不够显眼。

目标：**所有下载统一走一份「下载源」文件**（App APK 与引擎 runtime 共用同一镜像解析 + 同一下载实现 + 同一「选源」入口），主页新版本**自动弹 MD3 更新弹窗**，并**运行时校验引擎工具是否齐全、缺失时显眼提示引导更新**。

## What Changes
- 新增统一下载源文件 `engine/src/main/java/com/yoyo/dshmobile/engine/DownloadSource.kt`：集中镜像表 + `resolve()` + `download()`（带进度），作为 App APK 与引擎 rootfs 下载的唯一入口。
- 主页检测到新版本时：除横幅外，**自动弹出 MD3 更新弹窗**（`MaterialAlertDialogBuilder`）；「确定」跳转更新页，取消关闭。
- 弹窗输入框：新增 `res/layout/` 下的 MD3 描边输入框（复用用户提供的 XML：OutlinedBox，圆角 TopLeft/TopRight/BottomRight=35dp、BottomLeft=50dp），并在输入弹窗中 inflate 使用。
- 引擎环境/工具自检：启动失败时把缺失工具（node / bin.js / termux-exec / 关键 so）以显眼 MD3 弹窗 + Toast 展示，并提供「去更新引擎」入口（下载 rootfs 补齐）。

## Impact
- Affected specs: 引擎更新镜像、App 更新、主页统计横幅、开发者设置弹窗输入框共用样式。
- Affected code:
  - `app/.../ui/screen/HomeScreen.kt`（主页新版本弹窗）
  - `app/.../ui/screen/UpdateManager.kt`（App APK 下载改走统一源）
  - `app/.../ui/screen/ConversationScreen.kt`（引擎镜选择/更新弹窗改走统一源 + 样式）
  - `app/.../ui/Ui.kt`（`outlinedEditText` 改用用户提供的圆角描边 XML 样式）
  - `app/.../res/layout/*.xml`（新增输入框布局）
  - `engine/.../EngineMirrors.kt`（迁移/委托到 DownloadSource）
  - `engine/.../RuntimeUpdater.kt`（下载走统一源）
  - `engine/.../EngineProcess.kt` / `RuntimePermissions.kt`（工具缺失校验结果透出）
  - `app/.../res/values/strings.xml`（新增文案）
  - **不触碰**：引擎后端核心逻辑、`native`、manifest 语法语义他人部分。

## ADDED Requirements

### Requirement: 统一下载源文件
系统 SHALL 提供单一 `DownloadSource`（含镜像表、`resolve(url, mirrorId)`、带进度 `download(...)`），并让 App APK 更新与引擎 rootfs 更新都通过它下载。

#### Scenario: App APK 与引擎共用同一镜像
- **WHEN** 用户在「选源」选择某镜像后分别触发 App 更新与引擎更新
- **THEN** 两者下载 URL 均经该镜像 `resolve`，且复用同一段下载实现；持久化的镜像 id 两处生效

### Requirement: 主页新版本 MD3 更新弹窗
检测到新版本时，系统 SHALL 在主页弹出 MD3 更新弹窗；「确定」进入更新页，「取消」仅关闭（横幅保持可见）。

#### Scenario: 主页有新版
- **WHEN** `fetchBanner` 判定 `isNewer` 为真
- **THEN** 显示横幅并弹 `MaterialAlertDialogBuilder` 弹窗（标题「发现新版本 x.x.x」+ 更新说明摘要）；确定→`onOpenUpdate()`；取消→关闭

### Requirement: MD3 圆角描边输入框样式
弹窗输入框 SHALL 使用用户提供的 OutlinedBox 布局（圆角 35/35/35/50dp、单行、bold hint、浮动标签），并以 `MaterialAlertDialogBuilder(context).setTitle().setView().setPositiveButton().show()` 链式构建。

#### Scenario: 输入框视觉符合示例
- **WHEN** 打开任一需输入/密码的弹窗
- **THEN** 输入框为圆角 OutlinedBox、四周有描边、提示文字粗体、点击标签上浮到左上角

### Requirement: 引擎环境/工具补齐提示
引擎启动失败时，系统 SHALL 将缺失工具（node、dsh bin.js、termux-exec preload、关键 so）以显眼 MD3 弹窗展示，并附「去更新引擎」引导下载补齐；运行时校验逻辑复用现有 `EngineProcess` 检查。

#### Scenario: node 缺失
- **WHEN** rootfs 内 `usr/bin/node` 缺失
- **THEN** 弹 MD3 弹窗列出缺失项，提供「去更新引擎」触发引擎更新（下载含 node 的 rootfs）

## MODIFIED Requirements

### Requirement: 现有更新页与引擎更新保持兼容（回到 GitHub/镜像语义不变）
App 更新页（历史版本/更新日志）、引擎进度的既有交互保持；仅底层下载改走统一源。**BREAKING（内部）**：`UpdateManager.download*`、`RuntimeUpdater.download`、`EngineMirrors.resolve` 收敛到 `DownloadSource`，对外返回值/签名尽量不变。

## REMOVED Requirements
无。