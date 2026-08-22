# Tasks

- [x] Task 1: 新增 `RuntimePermissions`（engine 模块）
  - 新增 `engine/src/main/java/com/yoyo/dshmobile/engine/RuntimePermissions.kt`：
    - `fun resolveTermuxExecPreload(usrDir: File): File?`：优先硬编码 `usr/lib/libtermux-exec-ld-preload.so`；不存在时通配 `usr/lib/libtermux-exec*ld-preload*.so`，命中即复制为目标路径；都无返回 null。
    - `fun ensureExecutable(usrDir: File)`：对 `usr/bin/*` 与关键 `usr/lib`（termux-exec preload）幂等补设 exec 位（owner/group/other 任一已有 exec 位则保留，否则补 owner-exec），并调用 `stampAndroidExecAttr`。
    - `fun stampAndroidExecAttr(files: List<File>)`：经 `/system/bin/setfattr -n security.android.exec -v 1 <file>` 分批（每批 ≤64）打属性；失败静默（内核不支持则忽略）。
  - 全部失败 silent、重复调用无副作用（移植旧项目 `dsh-mobile-apk/.../RuntimePermissions.kt`）。

- [x] Task 2: 修复 `EngineProcess` 直接启动 node（去 proot）
  - 改 `engine/.../EngineProcess.kt`：
    - `buildArgs(rootfsDir)` 返回 `[ <rootfs>/usr/bin/node, "--expose-internals", <rootfs>/usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js, "web", "--port", "3080" ]`（不再用 proot，不再用 `/opt/dsh/web`）。
    - `start(context, rootfsDir)`：先 `RuntimePermissions.ensureExecutable(usrDir)`；构造 env：`PATH=<usr>/bin:/system/bin`、`LD_LIBRARY_PATH=<usr>/lib`、`HOME=<rootfs>/home`、`DSH_HOME=<rootfs>/home/.dsh`、`TMPDIR=<rootfs>/home/tmp`(mkdirs)、`TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE=force`、`TERMUX_EXEC__EXECVE_CALL__INTERCEPT=<0|1>`（preload 存在则 1）、`TERMUX__ROOTFS=<rootfs>`、`TERMUX__PREFIX=<usr>`、`TERMUX_APP__DATA_DIR`、`TERMUX_APP__LEGACY_DATA_DIR`、`TERMUX_VERSION=0.118.3`；preload 非空时加 `LD_PRELOAD=<preload>`；`.directory(File(rootfsDir,"home").apply{mkdirs()})`。
    - 直接 exec 抛 IOException 且含 "Permission denied" 时，回退 `/system/bin/linker64` + 原参数重试。
    - 其余（输出读线程、探活、stop）保持。

- [x] Task 3: 修复 `RuntimeUpdater.extractTarXz` 保留符号链接 + exec 位 + exec 属性
  - 改 `engine/.../RuntimeUpdater.kt` 的 `extractTarXz`：
    - `entry.isSymbolicLink`：`out.parentFile?.mkdirs()` → `Files.deleteIfExists(out.toPath())` → `Files.createSymbolicLink(out.toPath(), Paths.get(entry.linkName))`（替代当前「跳过」逻辑）。
    - 普通文件：写完后按 `(entry.mode and 0x49)!=0 || name.startsWith("usr/bin/") || name.startsWith("usr/lib/")` 设置 exec 位；记录可执行文件列表。
    - 解压结束后：收集的可执行文件打 `security.android.exec` 属性（复用 RuntimePermissions），并 `RuntimePermissions.ensureExecutable(File(destDir,"usr"))`。
    - 解压完成时断言关键文件存在（`usr/bin/node`、`usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js`、termux-exec preload），缺失则抛异常走既有回滚。
  - 保留现有路径安全（`..` 拒绝）与进度回调逻辑。

- [x] Task 4: 修复 `EngineService` 启动崩溃防护
  - 改 `engine/.../EngineService.kt` 的 `onStartCommand`：`EngineProcess.start` 用 `runCatching` 包裹；失败时 `Logs.logEvent(this,"Engine","engine-start-fail", t)` + `updateNotification(getString(R.string.engine_start_fail))`，**不启动看门狗**，返回 `START_STICKY`；成功才启动看门狗。`restartEngine` 同样 runCatching。

- [x] Task 5: 修复「发送日志」分享（FileProvider 根目录须为目录）
  - `app/.../AboutActivity.kt` 的 `zipLogs()`：目标由 `File(cacheDir,"logs_export.zip")` 改为 `File(File(cacheDir,"logs_export"),"logs_export.zip")`（先 `mkdirs`）。
  - `app/res/xml/file_paths.xml`：`<cache-path name="logs_export" path="logs_export.zip" />` → `<cache-path name="logs_export" path="logs_export/" />`。
  - 保持 `sendLogs` 既有失败 toast + 写日志逻辑。

- [x] Task 6: 自动启动引擎（设置开关 + 启动钩子）
  - `app/res/values/strings.xml` 新增：`settings_auto_start=自动启动引擎`、`settings_auto_start_desc=打开应用时自动启动引擎（默认关闭）`。
  - `app/.../SettingsScreen.kt`：新增 `buildSwitchRow(title, desc, checked, onCheckedChange)`（Switch 组件、主题色 dh_primary）；在权限模式行上方加「自动启动引擎」开关行，读写 SharedPreferences `engine_prefs` 的 `auto_start`（默认 false）。
  - `app/.../DshApp.kt`：`onCreate` 末尾读取 `engine_prefs/auto_start`，为 true 且 `EngineRootfs.isExtracted(this)` 时 `EngineService.start(this)`（默认 false，不改变现有行为）。

- [x] Task 7: 编译验证 + 文档同步
  - `./gradlew :app:assembleRelease --no-daemon` 编译通过；版本 1.0 不变，APK 输出 `deepseek-harness-1.0-release.apk`。
  - 同步 `PITFALLS.md`（proot 不存在 / FileProvider 根目录须为目录 / symlink+exec 属性约定）、`PROJECT_STRUCTURE.md`、`USER_HABITS.md`、`/workspace/INDEX.md`、本 spec。

# Task Dependencies
- Task 2、3 依赖 Task 1（RuntimePermissions）；Task 4 依赖 Task 2（EngineProcess 修复后仍可能失败，需防护）。
- Task 5、6 相互独立，可并行；Task 7 依赖 1–6 全部完成。
