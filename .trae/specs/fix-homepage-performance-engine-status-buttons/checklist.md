# CheckList

- [x] `appStorageBytes()` 排除 `files/rootfs`，主页首帧不被存储遍历阻塞
- [x] 内存/存储测量 IO 协程化：首帧立即渲染，数值异步回填；`refreshValues()` 不再同步触发存储遍历
- [x] 主页「引擎状态」卡改用 `EngineProcess.probe` 端口探活（不再以 Shizuku 授权作为引擎运行依据），含「检测中…」中间态，异步回填「运行中/未运行」
- [x] 会话页按钮两排两列：启动|停止、打开会话|检查更新；等宽、不溢出屏幕；`LoadingButton` loading 行为保留
- [x] 无越界修改：不动引擎启动链路（EngineProcess/EngineService/RuntimeUpdater/EngineWatchdog）、不动设置/插件/引导页；公共方法签名不变、无 SharedPreferences key 变更
- [x] `assembleRelease` 编译通过，APK 输出项目根 `deepseek-harness-1.0-release.apk`，签名前缀 `5696…25ff` 不变
- [x] 三件套文档（PITFALLS/PROJECT_STRUCTURE/USER_HABITS）与 `/workspace/INDEX.md`、本 spec 同步
- [x] 编译完成后告知用户 APK 下载链接
