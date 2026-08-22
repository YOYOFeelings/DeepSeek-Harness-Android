# CheckList

- [x] `runUpdate`「已是最新」门槛修正：`manifest.version` 为空时不跳过，已装 rootfs 后点击更新能进入下载流程（不再误判）
- [x] `doUpdate` 覆盖确认：`cacheDir/rootfs-new.tar.xz` 存在或 rootfs 已安装时，先弹「将覆盖现有引擎数据，确定继续吗？」（取消/确定），确定才进镜像选择/下载
- [x] 启动引擎 MD3 状态弹窗：点击「启动引擎」弹出，展示「检查运行时→启动中→探活中」动向，实时刷新日志尾部
- [x] 启动成功：探活通过显示「运行中」，约 1s 后自动关闭弹窗并刷新状态
- [x] 启动失败：弹窗显示「启动失败: <原因>」+ 最近日志，提供「重试/关闭」；重试重新走启动流程
- [x] 运行时未就绪：弹窗提示「请先检查并更新引擎」，提供「去更新/关闭」
- [x] `EngineService` companion 启动结果状态（lastStartSeq/lastStartFailed/lastStartError）在 onStartCommand 与 restartEngine 均正确写入，公共方法签名不变
- [x] 主页「存储占用」重新含 `files/rootfs`（IO 协程测量），不再显示 ≈0 MB
- [x] 主页「插件数量」随 30s 周期动态刷新（非静态快照）
- [x] 无越界修改：不动引擎启动链路实现（EngineProcess/RuntimeUpdater.apply/EngineWatchdog）、不动设置/插件/引导页；公共方法签名不变、无 SharedPreferences key 变更
- [x] `assembleRelease` 编译通过，APK 输出项目根 `deepseek-harness-1.0-release.apk`，签名前缀 `5696…25ff` 不变
- [x] 三件套文档（PITFALLS/PROJECT_STRUCTURE/USER_HABITS）与 `/workspace/INDEX.md`、本 spec 同步
- [x] 编译完成后告知用户 APK 下载链接
