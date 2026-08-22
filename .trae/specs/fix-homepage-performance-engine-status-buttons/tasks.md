# Tasks

- [x] Task 1: 主页加载性能优化（HomeScreen.kt）
  - [x] 1.1 `appStorageBytes()` 排除 `files/rootfs`（引擎运行时不计入，且避免主线程遍历大目录）
  - [x] 1.2 初始与 30s 低频的内存/存储测量改 IO 协程收集 + 主线程回填；`refreshValues()` 不再同步触发存储遍历
  - [x] 1.3 首帧立即渲染（uptime 同步填充，mem/stor 异步回填）

- [x] Task 2: 主页引擎状态改为端口探活（HomeScreen.kt + strings.xml）
  - [x] 2.1 `app/res/values/strings.xml` 新增 `home_status_engine_checking=检测中…`
  - [x] 2.2 `engineReady()` 由 `ShizukuHelper.isRunning && isGranted` 改为 `EngineProcess.probe`（协程 + `Dispatchers.IO`，主线程不探活）
  - [x] 2.3 主页「引擎状态」卡初始「检测中…」，异步探活后回填「运行中/未运行」

- [x] Task 3: 会话页按钮两列布局（ConversationScreen.kt）
  - [x] 3.1 第 1 排「启动引擎 | 停止引擎」（水平 LinearLayout + weight=1f + 间距）
  - [x] 3.2 第 2 排「打开会话 | 检查并更新引擎」（`LoadingButton` 保留 loading 行为）
  - [x] 3.3 保留点击/启停/状态刷新（probe 驱动）逻辑不变

- [x] Task 4: 编译验证 + 文档同步
  - [x] 4.1 `./gradlew :app:assembleRelease --no-daemon --max-workers=1`（PITFALLS §18 环境变量）编译通过，APK 输出项目根 `deepseek-harness-1.0-release.apk`
  - [x] 4.2 `apksigner verify --print-certs` 核对签名 SHA-256 前缀 `5696…25ff` 不变
  - [x] 4.3 同步 `PITFALLS.md` / `PROJECT_STRUCTURE.md` / `USER_HABITS.md` / `/workspace/INDEX.md` / 本 spec
  - [x] 4.4 编译完成后告知用户 APK 下载链接

# Task Dependencies
- Task 1、Task 2 均改 `HomeScreen.kt`，由同一 sub-agent 顺序完成（先性能后状态），避免同文件并行冲突。
- Task 3 独立于 Task 1/2，可并行。
- Task 4 依赖 Task 1–3 全部完成。
