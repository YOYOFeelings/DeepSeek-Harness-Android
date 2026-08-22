# Tasks
- [x] Task 1: 校验并加固 `RuntimePermissions.ensureNodeLibsReal`
  - [x] SubTask 1.1: 复核 8 库名与 node ELF DT_NEEDED 完全一致（libz/libcares/libsqlite3/libcrypto.3/libssl.3/libicui18n.78/libicuuc.78/libc++_shared）
  - [x] SubTask 1.2: 实体化用「删旧链接→同前缀真实库复制实体」路径，幂等且失败静默；确认 FUSE 下读回为真实文件
  - [x] SubTask 1.3: 保持返回 Map<库名→是否到位> 供诊断
- [x] Task 2: EngineProcess.start 补齐启动前健壮性链路
  - [x] SubTask 2.1: 先 `ensureNodeLibsReal(usrDir)` 并打印 `node-deps real=…` 诊断（保留既有实现）
  - [x] SubTask 2.2: 关键文件校验（node/bin.js/termux-exec preload 存在+非空+exec 位），失败先 `ensureExecutable` + `resolveTermuxExecPreload` 自愈并记日志
  - [x] SubTask 2.3: 启动前 `pkill -f lib/bin.js` 清理残留，防 3080 EADDRINUSE；复用运行中句柄时不清理
  - [x] SubTask 2.4: 全部包 `runCatching`，不阻塞、不引发崩溃
- [x] Task 3: 构建与验证
  - [x] SubTask 3.1: `assembleRelease` 编译通过（无新增错误）
  - [x] SubTask 3.2: 核对签名 SHA-256 前缀 `5696…25ff`（规则 8.8）
  - [x] SubTask 3.3: 用快照内 node 做沙箱回归：运行 `ensureNodeLibsReal` 相关逻辑后 `readelf -d` 缺失库名均已在 usr/lib 实体化
- [x] Task 4: 更新文档
  - [x] SubTask 4.1: PITFALLS.md 追加本轮「符号链接实体化 + 启动前校验/残留清理」记录
  - [x] SubTask 4.2: PROJECT_STRUCTURE.md 若涉及文件新增则登记（无新增文件则注明）

# Task Dependencies
- [Task 2] depends on [Task 1]
- [Task 3] depends on [Task 2]
- [Task 4] depends on [Task 3]