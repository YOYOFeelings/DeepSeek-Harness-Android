# CheckList

- [x] `EngineMirrors`（Mirror + resolve + GH_HOSTS + 内置镜像表，无用户自定义入口）已新增并在 engine 模块可编译。
- [x] `RuntimeUpdater.download` 透传 `mirror` 并用 `mirror.resolve(url)` 下载；失败记 `update-download-fail` 返回 null。
- [x] `RuntimeUpdater.apply` 报告阶段与解压进度（onPhase 回调，默认参数不破坏其他调用）。
- [x] ConversationScreen 更新流程：镜像选择弹窗（并发测速、逐行延迟、点击即更新、记忆上次选择并置顶）。
- [x] MD3 更新进度弹窗：醒目 ProgressBar + 阶段文案 + 百分比；下载/解压确定、校验/切换不确定；成功/失败明确反馈可重试。
- [x] About「发送日志」分享失败会 toast + 写日志，不再静默。
- [x] 版本号 = 1.0 不变，APK 输出名 `deepseek-harness-1.0-release.apk`。
- [x] 三件套文档与索引同步。
- [x] `assembleRelease` 编译通过；签名 SHA-256 前缀 `5696…25ff`。
- [x] 未改 `EngineProcess/EngineService/EngineRootfs` 启动链路与端口 3080；`download/apply` 仅加带默认值的参数。
