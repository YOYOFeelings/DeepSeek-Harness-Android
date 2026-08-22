# CheckList

- [x] `RuntimePermissions` 新增（engine 模块）：resolveTermuxExecPreload / ensureExecutable / stampAndroidExecAttr，失败 silent、幂等。
- [x] `EngineProcess` 去 proot：直接用 `usr/bin/node --expose-internals usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web --port 3080`，注入 LD_LIBRARY_PATH / LD_PRELOAD / PATH / HOME / DSH_HOME / TMPDIR / TERMUX_*，目录=rootfs/home，linker64 回退；不再引用 `/opt/dsh/web`。
- [x] `RuntimeUpdater.extractTarXz` 保留符号链接、补设 usr/bin 与 usr/lib exec 位、打 `security.android.exec` 属性、解压后断言 node/bin.js/termux-exec preload 关键文件存在。
- [x] `EngineService.onStartCommand` 引擎启动 runCatching：失败写日志 + 通知「引擎启动失败」、不启动看门狗、不闪退。
- [x] `AboutActivity.zipLogs` 输出到 `cacheDir/logs_export/logs_export.zip`；`file_paths.xml` 根目录改为 `path="logs_export/"`；发送日志不再抛 StringIndexOutOfBoundsException。
- [x] 设置页新增「自动启动引擎」开关（默认关闭，`engine_prefs/auto_start` 持久化）；`DshApp.onCreate` 开启时自动 `EngineService.start`。
- [x] 版本号 1.0 不变，`assembleRelease` 编译通过，APK 输出名 `deepseek-harness-1.0-release.apk`，签名 SHA-256 前缀 `5696…25ff` 不变（实测 `56968172140b…2b25ff`）。
- [x] 三件套文档（PITFALLS/PROJECT_STRUCTURE/USER_HABITS）与 `/workspace/INDEX.md`、本 spec 同步。
- [x] 未越界修改：不触碰终端/插件/权限模式/引导页逻辑；`EngineProcess` 公共方法签名不变；SharedPreferences 仅新增 `auto_start` key。
