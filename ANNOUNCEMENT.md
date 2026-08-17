# DeepSeek Harness Android — 更新公告

## v0.11.5

**中文**

v0.11.5 为**关键修复版**，重点解决 arm64 架构设备（绝大多数主流手机）引擎无法启动的问题。

- **修复 arm64 设备引擎无法启动（核心）**
  - 根因：设备 ABI 为 arm64-v8a 时，内嵌 x86_64 快照的 node 二进制架构不匹配，引擎报
    `error: "/data/data/com.dshmobile.shell/files/usr/bin/node" is for EM_X86_64 (62) instead of EM_AARCH64 (183)`，
    直接无法启动。
  - 附带问题：在线修复时备用清单仅含 x86_64 快照，arm64 设备报「发布清单中无 arm64 架构快照」。
  - 修复：备用清单地址切换到上游 `kelai141/dsh-mobile-apk` 的 v0.10.8 双 ABI 发布
    （同一清单同时含 `snapshot-arm64.tar.xz` 与 `snapshot-x86_64.tar.xz`），arm64 设备会自动
    下载匹配架构的运行时快照（SHA256 校验后原子切换）并成功启动引擎。
  - 兼容性已验证：上游 arm64 快照与内嵌快照的 dsh（0.1.0-rc.6）/ node（v24.18.0）版本完全一致，
    为无损 drop-in 替换，会话 / 凭据 / 设置 / 插件数据均自动保留。
- **修复更新源误报「不可达」**（v0.11.4 延续）
  - 最新发布未附带 MANIFEST.txt 时，自动回退到带清单的历史发布（主/备清单地址去重回退），
    不再把「清单缺失」误报为「更新源不可达」；网络层失败与清单缺失的错误提示已明确区分。
- 引擎启动、看门狗、镜像测速、APK 自更新等其余能力保持不变。

**English**

v0.11.5 is a **critical fix release** addressing engine startup failure on arm64 devices (the vast majority of mainstream phones).

- **Fix: engine cannot start on arm64 devices (core)**
  - Root cause: when the device ABI is arm64-v8a, the embedded x86_64 snapshot's node binary architecture does not match,
    the engine errors with `error: ".../node" is for EM_X86_64 (62) instead of EM_AARCH64 (183)` and cannot start.
  - Related: the online fix used a fallback manifest containing only the x86_64 snapshot, so arm64 devices reported
    "no arm64 architecture snapshot in release manifest".
  - Fix: the fallback manifest URL now points to the upstream `kelai141/dsh-mobile-apk` v0.10.8 dual-ABI release
    (the same manifest contains both `snapshot-arm64.tar.xz` and `snapshot-x86_64.tar.xz`). arm64 devices now
    automatically download the matching-architecture runtime snapshot (SHA256-verified, atomic switch) and start successfully.
  - Compatibility verified: the upstream arm64 snapshot shares the same dsh (0.1.0-rc.6) / node (v24.18.0) versions
    as the embedded snapshot — a lossless drop-in replacement. Sessions, credentials, settings, and plugin data are preserved.
- **Fix: update sources falsely reported as unreachable** (from v0.11.4)
  - When the latest release lacks MANIFEST.txt, the app now automatically falls back to a historical release with a manifest
    (deduplicated primary/fallback candidate URLs); "missing manifest" is no longer misreported as "source unreachable".
    Network-layer failures and missing-manifest cases now show distinct messages.
- Engine startup, watchdog, mirror speed testing, and APK self-update remain unchanged.

## v0.11.0

**中文**

- 版本号提升至 v0.11.0（versionCode 11）。
- 优化「关于」页：改为全屏单列纵向布局，元素按顺序排列（返回导航 → 应用 Logo + 名称 + 版本号 → 系统信息 → 全部操作按钮），所有按钮完整可见，删除多余灰色空白区域。
- 下载/安装进度改为终端内 ASCII 进度条，不再出现换行异常。
- 优化引擎重启逻辑：引擎已下载/就绪后，点击「重启引擎」直接重启引擎，不再重复下载。
- 修复日志导出、引擎错误报告、多源测速、公告拉取等多项细节。
- 主介绍已更新为中文，并新增官方 QQ 群信息（QQ 群 1：200317338；QQ 群 2：932593560）。

**English**

- Version bumped to v0.11.0 (versionCode 11).
- Redesigned the "About" page: full-screen single-column layout, elements arranged in order (back navigation → app logo + name + version → system info → all action buttons), all buttons fully visible, extra blank grey area removed.
- Download/install progress now uses an ASCII progress bar in the terminal; no more line-wrap glitches.
- Improved engine restart logic: once the engine is downloaded/ready, "Restart engine" restarts the engine directly instead of downloading again.
- Fixed several details including log export, engine error reports, multi-mirror speed test, and announcement fetching.
- The main introduction is now in Chinese, and official QQ group info was added (QQ Group 1: 200317338; QQ Group 2: 932593560).
