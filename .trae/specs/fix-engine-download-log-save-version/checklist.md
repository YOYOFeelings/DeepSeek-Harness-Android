# CheckList

- [x] RuntimeUpdater 下载 URL 使用 manifest path 的 basename 拼接（无 `snapshot/` 子目录），arm64 落回退源可下载。
- [x] AboutActivity「保存日志」经 SAF 保存到用户指定目录并 toast 位置；无日志提示「暂无日志文件」。
- [x] AboutActivity「发送日志」保持系统分享（ACTION_SEND + FileProvider）不变。
- [x] versionName = 1.0，versionCode = 1，APK 输出名 `deepseek-harness-1.0-release.apk`。
- [x] 三件套文档与索引同步（PITFALLS §19、PROJECT_STRUCTURE、USER_HABITS、INDEX）。
- [x] `assembleRelease` 编译通过；签名 SHA-256 前缀 `5696…25ff`。
- [x] 未改 `download()/apply()`、`ConversationScreen` 调用链、EngineProcess/EngineRootfs/EngineService 启动与端口 3080。