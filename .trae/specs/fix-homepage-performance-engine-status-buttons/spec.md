# 修复主页加载慢 + 统一引擎状态判断 + 会话页按钮两列 Spec

## Why
1. **主页加载缓慢（安装后尤其明显）**：`HomeScreen.init` 在主线程同步调用 `refreshValues()` → `refreshMemStorage()` → `appStorageBytes()`，后者会**递归遍历整个应用私有目录**（`filesDir` 下含 `files/rootfs` 引擎运行时，node_modules 数千文件/符号链接），首帧渲染被严重阻塞（实测 MainActivity onCreate ≈1865ms）。
2. **引擎「是否启动」判断不统一/不准确**：主页 `engineReady()` 用 `ShizukuHelper.isRunning && isGranted`（Shizuku 授权状态 ≠ 引擎运行状态），主页「引擎状态」卡会显示错误；会话页 `ConversationScreen.refreshStatus()` 与看门狗已统一用 `EngineProcess.probe(127.0.0.1:3080)` 端口探活。需把主页也统一为探活。
3. **会话页按钮观感别扭**：启动/停止/打开会话/检查更新 4 个全宽按钮竖排，一排一个太占空间，改为一排两个。

## What Changes
- `app/.../HomeScreen.kt`：
  - `appStorageBytes()` 排除 `files/rootfs`（引擎运行时不算用户数据，且避免主线程遍历大目录）。
  - 初始与 30s 低频的内存/存储测量改为 IO 协程（首帧立即渲染，数值异步回填）；`refreshValues()` 不再同步触发存储遍历。
  - `engineReady()` 由 Shizuku 权限判断改为 `EngineProcess.probe`（协程 + `Dispatchers.IO`，主线程不探活）；主页「引擎状态」卡初始显示「检测中…」，异步探活后回填「运行中/未运行」。
- `app/.../ConversationScreen.kt`：4 个按钮改两排两列——第 1 排「启动引擎 | 停止引擎」，第 2 排「打开会话 | 检查并更新引擎」（水平 LinearLayout + `weight=1f` + 间距；`LoadingButton` 保留 loading 行为）；点击/启停/状态刷新逻辑不变。
- `app/res/values/strings.xml`：新增 `home_status_engine_checking=检测中…`。
- 文档同步：`PITFALLS.md`、`PROJECT_STRUCTURE.md`、`USER_HABITS.md`、`/workspace/INDEX.md`、本 spec。

## Impact
- 影响代码：`app/.../HomeScreen.kt`、`app/.../ConversationScreen.kt`、`app/res/values/strings.xml`。
- 受影响 spec：`fix-engine-startup-crash-share-autostart`（引擎启动链路，已完成，本轮不改启动逻辑）。
- 回归风险（⚠️）：
  - `appStorageBytes()` 口径变化：不再计入 rootfs 体积 → 主页「存储占用」数值会比之前小，属预期（引擎运行时非用户数据）。
  - 主页「引擎状态」卡语义变化：由「Shizuku 权限」改为「引擎端口探活」→ 更准确；引擎启动中会短暂显示「检测中…」。
  - `ConversationScreen` 仅改布局，点击/启停/loading 行为不变。
  - ⚠️ 无公共方法签名变更、无全局状态/单例变更、无 SharedPreferences key 变更。

## ADDED Requirements

### Requirement: 主页快速渲染（不阻塞主线程）
`HomeScreen` 首帧渲染 SHALL 不因存储测量阻塞：`appStorageBytes` 排除 `files/rootfs`，内存/存储测量在 IO 线程完成、主线程回填。

#### Scenario: 安装后进入主页
- **WHEN** App 首次进入主页
- **THEN** 首帧立即显示（无长时间白屏），存储/内存数值随后异步回填

### Requirement: 引擎运行状态统一为端口探活
主页「引擎状态」卡 SHALL 与会话页/看门狗一致，用 `EngineProcess.probe(127.0.0.1:3080)` 判断引擎是否运行；Shizuku 授权状态不再作为引擎运行依据。

#### Scenario: 引擎启动后主页显示「运行中」
- **WHEN** 引擎进程对 3080 正常响应
- **THEN** 主页引擎状态卡显示「运行中」

#### Scenario: 引擎未启动
- **WHEN** 引擎未运行且探活超时
- **THEN** 主页引擎状态卡显示「未运行」

### Requirement: 会话页按钮两列布局
会话页操作按钮 SHALL 两排两列排布：第 1 排「启动引擎|停止引擎」、第 2 排「打开会话|检查并更新引擎」；保留原有点击/启停/loading 行为。

#### Scenario: 会话页显示
- **WHEN** 进入会话页
- **THEN** 按钮每排两个、等宽排布，不溢出屏幕

## MODIFIED Requirements

### Requirement: 存储占用展示
展示 App 自身存储（排除引擎运行时 rootfs），口径调整后数值变化属预期，不视为回退。

## REMOVED Requirements
（无删除项）
