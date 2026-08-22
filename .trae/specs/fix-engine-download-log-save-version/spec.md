# 修复引擎下载 + 日志保存方式 + 版本号改 1.0 Spec

## Why
用户实测反馈两处功能问题：
1. **引擎无法安装/启动**：arm64 设备从回退源下载 rootfs 时 `FileNotFoundException: .../v0.10.8/snapshot/snapshot-arm64.tar.xz`（404）。根因是 `RuntimeUpdater.checkForUpdate` 把 manifest 里的**子目录路径**（`snapshot/snapshot-arm64.tar.xz`）拼进下载 URL，回退源实际是把快照文件放在 release 根目录（无 `snapshot/` 子目录）。旧项目 (`dsh-mobile-apk`) 的做法是取**文件名 basename** 拼接，可正确下载。
2. **日志导出方式**：用户要求「保存」= 直接保存为 zip 到用户指定目录；「发送」= 调起系统分享选择接收 App。当前 `AboutActivity.saveLogs` 只是把 zip 写到私有 cache 目录并 toast 路径，**不是**保存到用户指定目录；`sendLogs` 已正确（ACTION_SEND 分享）。
3. **版本号**：要求把版本号改为 **1.0**（当前 `0.13.0-Data`）。

## What Changes
- `engine/.../RuntimeUpdater.kt`：`checkForUpdate` 命中 snapshot 行后，下载 URL 用 `path.substringAfterLast('/')`（basename）与清单基址拼接，**不再**使用带子目录的完整 path。
- `app/.../AboutActivity.kt`：`saveLogs` 改用 SAF（`ActivityResultContracts.CreateDocument("application/zip")`）让用户选择保存位置/文件名，把打包好的 zip 写入所选文档；`sendLogs` 保持 ACTION_SEND 系统分享不变。
- `app/build.gradle.kts`：`versionName` 由 `0.13.0-Data` 改为 `1.0`（`versionCode` 保持 `1` 不动）。
- 文档同步：`PITFALLS.md`（§19 补 basename 拼接约定）、`PROJECT_STRUCTURE.md`、`USER_HABITS.md`、`/workspace/INDEX.md`、本 spec。

## Impact
- 影响代码：`RuntimeUpdater.kt`、`AboutActivity.kt`、`app/build.gradle.kts`。
- 受影响 spec：无（本产品无既有 spec 覆盖这三处）。
- 回归风险：`download()/apply()`、`ConversationScreen` 调用链、`EngineProcess/EngineRootfs/EngineService`（端口 3080 启动）均不改动；`sendLogs`/ZIP 分享链路不改。

## ADDED Requirements

### Requirement: 日志「保存」落盘到用户指定目录
`AboutActivity` 的「保存日志」SHALL 通过 SAF 文件保存对话框让用户选择目标位置与文件名，并把打包好的日志 zip 写入所选位置；保存成功 toast 实际保存位置。日志为空时 toast「暂无日志文件」并中止。

#### Scenario: 成功保存
- **WHEN** 用户点击「保存日志」并确认保存位置/文件名
- **THEN** zip 写入用户所选文档，并提示已保存

#### Scenario: 无日志
- **WHEN** 日志目录为空或打包失败
- **THEN** 提示「暂无日志文件」，不弹保存对话框

### Requirement: 引擎按 basename 解析 rootfs 下载 URL
`RuntimeUpdater.checkForUpdate` SHALL 用 manifest 中匹配行 `path.substringAfterLast('/')`（basename）与清单基址拼接为下载 URL，确保回退源 `.../v0.10.8/snapshot-arm64.tar.xz` 可访问。

#### Scenario: arm64 设备
- **WHEN** arm64 设备命中回退源 `snapshot/snapshot-arm64.tar.xz` 行
- **THEN** 下载 URL = `.../v0.10.8/snapshot-arm64.tar.xz`（basename，已验证 HTTP 200，75MB），可下载→SHA256→解压→启动

## MODIFIED Requirements

### Requirement: 版本号 = 1.0
`app` 模块 `versionName` 改为 `1.0`；`versionCode` 保持 `1`。APK 输出名随之变为 `deepseek-harness-1.0-release.apk`，`AboutActivity` 显示的版本号为 1.0。

## REMOVED Requirements
（无删除项）