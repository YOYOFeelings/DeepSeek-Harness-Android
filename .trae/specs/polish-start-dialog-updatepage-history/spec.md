# 启动弹窗简化 + 更新页改进与历史版本下载 Spec

## Why
1. 启动引擎弹窗交互偏重（转圈+状态行+「查看日志」开关+内联失败文案），希望简化为「纯转圈 + 启动失败时独立错误弹窗」。
2. 设置里的「版本更新」页（`UpdateScreen`）信息有限：无手动刷新、进度反馈粗（只有百分比）、无历史版本入口。希望加刷新、细化进度，并新增「往期版本」下载功能。

## What Changes
- **ConversationScreen.kt**：删除 `showStartDialog()/showStartNotReady()/StartUi`，新增 `showStartDialogSimple()` 与 `showStartErrorDialog()`，重写 `doStart()`（20s 探活，失败/超时弹错误弹窗，未解压引导更新）。`showStartNotReady` 调整为 `showUpdateRequiredDialog()`。
- **UpdateScreen.kt**：版本卡片加手动刷新；进度条增高且圆角；下载中显示「下载中…」+ 已下载/总大小；完成后按钮态「安装/已下载」；新增可折叠「往期版本」列表（LinearLayout + 循环），点击下载该历史 APK 并安装，复用主进度区。
- **UpdateManager.kt**：`ReleaseInfo` 增 `publishedAt: String = ""`；新增 `fetchHistoryReleases(): List<ReleaseInfo>`（GitHub releases list，含 tag/apk/published_at，过滤掉最新版）。
- **strings.xml**：新增 `engine_retry`、`engine_start_failed_title`、`engine_start_failed_unknown`、`engine_no_rootfs_update_required`、`update_history_title`、`update_history_empty`、`update_history_download`、`update_history_downloading`、`update_history_install`、`update_refresh`。

## Impact
- Affected specs: 引擎交互、设置-版本更新。
- Affected code: `app/.../ui/screen/ConversationScreen.kt`、`app/.../ui/screen/UpdateScreen.kt`、`app/.../ui/screen/UpdateManager.kt`、`app/src/main/res/values/strings.xml`。
- **不涉及**：引擎后端、更新下载核心链路（`RuntimeUpdater`/引擎 rootfs）、其它页面。全部继续使用现有 `themedDialog/roundedBg/color/dp` 与 dh_ 主题令牌、传入的 `scope`。

## ADDED Requirements
### Requirement: 启动弹窗简化
系统 SHALL 用「纯不确定转圈 + 启动失败独立错误弹窗」替代旧的含日志开关的启动弹窗。

#### Scenario: 正常启动
- **WHEN** 用户点「启动引擎」且 rootfs 已解压
- **THEN** 弹出不可取消、无按钮的纯转圈弹窗（「引擎启动中…」），探活成功（20s 内）后关闭并刷新状态。

#### Scenario: 启动失败 / 超时
- **WHEN** 20s 内探活失败或超过 20s
- **THEN** 关闭转圈弹窗，弹出「启动失败」错误弹窗，内含错误信息与最近 30 行引擎日志，提供「重试」(`doStart()`) 与「关闭」。

#### Scenario: rootfs 未就绪
- **WHEN** 点「启动引擎」但运行时未解压
- **THEN** 关闭转圈弹窗，弹出「去更新」引导弹窗（复用 `showUpdateRequiredDialog`），点击进入更新流程。

### Requirement: 更新页 GUI 改进
系统 SHALL 在版本卡片提供手动刷新、提升进度条观感、细化下载状态、完成后按钮态切换。

- 刷新：版本卡片右上「刷新」，点击重新拉取最新版与历史列表。
- 进度条：高度 ≥8dp、圆角（自定义 drawable）。
- 下载中：按钮文字「下载中…」；进度百分比下方显示「已下载/总大小」（如 `45.2MB/78.1MB`）。
- 下载完成：按钮文字「安装」（手动触发）或「已下载」。

### Requirement: 往期版本下载
系统 SHALL 在更新说明卡片下方提供可折叠「往期版本」列表，每项含版本号、发布日期（若有）与「下载」按钮；点击从服务器下载该版本 APK 并安装，复用主进度区并标注正在下载的版本号；列表为空/获取失败显示「暂无历史版本」。

## MODIFIED Requirements
### Requirement: 更新页数据获取归并
系统 SHALL 在刷新时同时更新「最新版本信息」与「往期版本列表」，二者通过 `UpdateManager` 网络基础方法（httpGet）实现；`fetchHistoryReleases()` 返回 `List<ReleaseInfo>`（含 `publishedAt`）。

## REMOVED Requirements
### Requirement: 旧启动弹窗（转圈+日志开关+内联失败文案）
**Reason**: 用户要求简化交互，改为纯转圈 + 独立错误弹窗。
**Migration**: `doStart()`、`showStartDialog()`、`showStartNotReady()`、`StartUi` 一并替换；`showStartNotReady` 改为 `showUpdateRequiredDialog()`（保留「去更新」引导能力）。