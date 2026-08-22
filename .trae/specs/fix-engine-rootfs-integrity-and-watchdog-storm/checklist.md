# Checklist

- [x] Task1 已实现：RuntimeUpdater.download 在 SHA 通过后增加 `>=1 MiB` 最小体积校验，`< 1MiB` 记 `update-download-too-small`、删除 temp 不 rename 并返回 null；`update-download-ok` 日志在 rename 前取 temp 长度（不再总打印 size=0）
- [x] Task2 已实现：EngineProcess.start 对 home/tmp 目标存在但非目录时**先删再 mkdirs**（记 `home-rebuild`），幂等，重试后仍非目录才抛 `cannot create dir`
- [x] Task3 已实现：EngineProcess.start 自愈后 `verifyCriticalFiles` 仍非空(NODE/BIN_JS/PRELOAD)即抛 `IllegalStateException("engine env incomplete: …")`，不产生 `proc: Cannot find module` 空转
- [x] Task4 已实现：EngineWatchdog 连续失败触发 restart 后 **consecutiveFail 不复位**、随失败累加、≥3 次触发 onStop；EngineService.restartEngine 先 `verifyCriticalFiles` 预检，严重缺失时跳过启动并记 `engine-env-broken-skip-restart`
- [x] 编译通过：`./gradlew :app:assembleRelease` 成功；签名 SHA-256 `5696…25ff` 与既有一致