# Tasks

- [x] Task 1: 拦截空/过小 rootfs 下载（RuntimeUpdater.download）
  - SHA 校验通过后增加最小体积校验（`< 1 MiB` 视为损坏包）：删除 temp、不 rename、记 `update-download-too-small`、返回 null，保留旧引擎。
  - 修正 `update-download-ok size=` 在 rename 后读取 temp.length()==0 的误导日志：改为 rename 前取 temp 长度。
- [x] Task 2: home/tmp 容错重建（EngineProcess.start）
  - 目录创建前若目标存在但非目录（悬空符号链接/损坏残留），先删除再 `mkdirs()`，记 `home-rebuild`；幂等，重试后仍非目录才抛。
- [x] Task 3: 环境严重缺失 → 抛错引导更新（EngineProcess.start）
  - 自愈（ensureExecutable + resolveTermuxExecPreload）后仍 `verifyCriticalFiles` 非空（node/bin.js/termux-exec 缺失），抛 `IllegalStateException("engine env incomplete: <issues>")`，不拉起注定失败的 node。
- [x] Task 4: 看门狗防重启风暴（EngineWatchdog / EngineService）
  - `EngineWatchdog.loop()`：连续失败触发 restart 时**不再复位 `consecutiveFail`**，随每次失败累加，≥3 次触发 `onStop`。
  - `EngineService.restartEngine()`：启动前做 `EngineProcess.verifyCriticalFiles` 预检，关键文件严重缺失则跳过启动、不起新进程，记 `engine-env-broken-skip-restart`。
- [x] Task 5: 编译验证 + 签名（BUILD SUCCESSFUL；SHA-256 `5696…25ff` 与既有一致）

# Task Dependencies
- [Task 5] 依赖 [Task 1-4]