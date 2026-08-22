# Tasks

## 任务依赖总览
- Task 1（统一下载源）是 Task 3（引擎更新走统一源）与 Task 2（App 更新走统一源）的前置。
- Task 4（主页弹窗）与 Task 5（输入框样式）相对独立，可并行。
- Task 6（环境工具补齐）依赖 Task 3 的更新入口。
- 全部完成后 Task 7 编译验证 + 签名 + 下载链接。

- [x] Task 1: 新建统一下载源文件 `engine/.../engine/DownloadSource.kt`
  - [x] 将 `EngineMirrors` 的镜像表、`GH_HOSTS`、`resolve()`、`speedTest()` 迁移/委托到 `DownloadSource`（保留 `EngineMirrors.byId/all` 薄兼容层避免大改调用方）。
  - [x] 提供通用 `suspend fun download(context, url, dest: File, mirrorId?, onProgress?): Boolean/File?`（内含时序、UA、超时、断连、进度回调、SHA 可选校验）。
  - [x] 提供 `preferredMirrorId(context)` / `saveMirrorId(context, id)` 统一读写 SharedPreferences(engine_prefs），供 App 与引擎共用。

- [x] Task 2: App APK 下载改走统一源（UpdateManager.kt）
  - [x] `UpdateManager.downloadWithProgress` / `download` 改调 `DownloadSource.download(...)`，并传持久化的镜像 id；原先内联 HttpURLConnection 逻辑移除。
  - [x] 更新页/主页在选源时应体现 App 下载也走所选源。

- [x] Task 3: 引擎 rootfs 下载与「选源」改走统一源（RuntimeUpdater.kt / ConversationScreen.kt）
  - [x] `RuntimeUpdater.download` 与 manifest 拉取返回的 URL 走 `DownloadSource.resolve` + `DownloadSource.download`，签名字段保持 SHA 校验。
  - [x] `ConversationScreen.showMirrorPicker` 改为用 `DownloadSource.all()`/`speedTest()`/持久化的 `preferredMirrorId`（外观交互不变）。

- [x] Task 4: 主页检测新版本 → MD3 更新弹窗（HomeScreen.kt）
  - [x] `fetchBanner` 判定 isNewer 后：除显示横幅外，用 `MaterialAlertDialogBuilder(context).setTitle("发现新版本 vX").setMessage(更新说明/版本).setPositiveButton("确定"){_、_ → onOpenUpdate?.invoke() }.setNeutralButton("取消",null).show()` 弹窗。
  - [x] 弹窗仅每次进主页/该次检查触发一次（用 `flag` 防重复），横幅正常保留。
  - [x] 文案入 strings.xml（`home_update_dialog_title`、`home_update_dialog_msg`、`home_update_dialog_confirm`、`home_update_dialog_cancel`）。

- [x] Task 5: MD3 圆角描边输入框（用户提供 XML）+ MaterialAlertDialogBuilder 链式（layout/Ui.kt）
  - [x] 新增 `res/layout/dialog_rounded_input.xml`，内容即用户提供的 OutlinedBox（`app:boxCornerRadiusTopStart/TopEnd/BottomEnd="35dp"`、`BottomStart="50dp"`，singleLine、hint 粗体）。
  - [x] 新增可复用布局 inflate 帮助方法（`LayoutInflater.from(ctx).inflate(R.layout.dialog_rounded_input, LinearLayout(ctx) , false)`），获取 `R.id.ti` 自定义 hint 与 inputType。
  - [x] 开发者设置密码弹窗等输入场景改用该布局 + `MaterialAlertDialogBuilder` 链式 `.setTitle().setView().setPositiveButton().setNegativeButton().show()`。

- [x] Task 6: 引擎环境/工具补齐提示（EngineProcess/RuntimePermissions 结果透出 + MainActivity/EngineStatus 入口）
  - [x] 复用 `EngineProcess` 启动前检查结果（缺失 node/bin.js/preload/关键 so），在主页/会话页启动失败时用 MD3 弹窗列缺失项 + Toast，并提供「去更新引擎」跳转（走 Task 3 更新入口下载 rootfs 补齐）。
  - [x] 新增 strings：`env_missing_title`、`env_missing_inner`（列项）、`env_go_update` 等。

- [x] Task 7: 编译验证 + 签名 + 下载链接
  - [x] `./gradlew :app:assembleRelease`（JDK 17）编译通过、无新增警告。
  - [x] 签名 SHA-256 前缀 `5696…25ff`（同一 APK 固定签名，规则 8.8）。
  - [x] 给出 APK 下载链接（http://localhost:8899/deepseek-harness-1.0-release.apk）。