# Tasks

- [x] Task 1: 修复引擎下载 URL（basename 拼接），解决 arm64 rootfs 404
  - `engine/src/main/java/com/yoyo/dshmobile/engine/RuntimeUpdater.kt` 的 `checkForUpdate`：
    - 命中的 snapshot 行取 `filename = snapshot.second.substringAfterLast('/')`。
    - `downloadUrl = manifestUrl.substringBeforeLast('/') + "/" + filename`。
    - 其余（Manifest 结构、`download()/apply()`、`abiName()/findSnapshot()`）不动。
  - 验证：`curl -sIL .../v0.10.8/snapshot-arm64.tar.xz` → 200、`application/octet-stream`、约 75MB。

- [x] Task 2: 日志「保存」改为 SAF 保存到用户指定目录
  - `app/src/main/java/com/yoyo/dshmobile/shell/AboutActivity.kt`：
    - onCreate 用 `registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip"))` 注册保存 launcher，存字段。
    - `saveLogs()`：先 `zipLogs()`，无日志则 toast「暂无日志文件」返回；否则把待存 zip 暂存字段，launcher 启动让用户选位置/文件名；回调返回 uri 时用 `contentResolver.openOutputStream(uri)` 将 zip 内容写入，成功 toast 保存位置。
    - `sendLogs()` 保持 ACTION_SEND 分享不变。
  - `res/values/strings.xml`：新增保存位置选择标题字符串（如 `about_logs_pick_dir`）。

- [x] Task 3: 版本号改为 1.0
  - `app/build.gradle.kts`：`versionName = "1.0"`（`versionCode` 保持 `1`）。
  - 输出 APK 名自动变 `deepseek-harness-1.0-release.apk`。

- [x] Task 4: 文档同步（PITFALLS §19 basename 约定 / PROJECT_STRUCTURE / USER_HABITS / INDEX / 三件套）

# Task Dependencies
- Task 4 依赖 Task 1、2、3（等实现落地后再记录约定）。