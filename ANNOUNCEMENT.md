# DeepSeek Harness Android — 更新公告

## v0.11.6

**中文**

v0.11.6 为**体验修复版**，重点修复终端滚动异常、测速弹窗滑动、更新源下载反复换源等问题。

- **修复终端输出滚动异常与换行**
  - 修复追加文本时「先跳到顶端再回落」的滚动跳动问题：改用嵌套 `post` 延迟滚动，确保在布局完成后按真实高度滚动到底部；
  - 追加 `setSelection(textView.length())` 保持光标在末尾，避免可选中 TextView 内部滚动复位；
  - 加固长行自动换行：`setMaxLines(Int.MAX_VALUE)` 兜底，确保超宽行按屏幕宽度软换行。
- **修复测速弹窗无法上下滑动**
  - 自定义内容型弹窗（测速源列表 26+ 行）现在包入 `ScrollView` 并限制最大高度约屏高 55%，所有源完整可见并可上下滑动。
- **修复更新时一直换源无法正常下载**
  - 根因：下载 URL 已带加速域名，每个源 `resolve` 后仍为同一 URL，导致反复重试同一失败下载；
  - 修复：使用原始 GitHub 资产 URL，按源逐个正确添加前缀；优先使用已拉取到清单的源，失败时最多尝试 3 个源后中止报错，不再无限换源。
- **所有源并发测速**
  - 测速改为「所有源并发探测」（线程池 + CountDownLatch），不再逐个串行，速度大幅提升；
  - 测速弹窗新增进度条动画与实时状态反馈（每源完成后立即显示延迟或"不可用"）。
- **下载超时放宽至 120 秒**
  - 慢速但可用的更新源不再被误判为失败。
- **README 完善**
  - 新增「项目结构」章节，详细列出关键文件路径及其职责，便于后续开发维护。

**English**

v0.11.6 is an **experience improvement release** fixing terminal scroll glitches, speed-test dialog scrolling, and update source download retry loops.

- **Fix terminal scroll glitch and line wrapping**
  - Fixed the "jumps to top then scrolls down" scroll glitch: replaced `fullScroll(FOCUS_DOWN)` with nested `post` scroll that fires after layout completes for the real content height;
  - Added `setSelection(textView.length())` to keep the cursor at the end, preventing the selectable TextView from resetting scroll;
  - Hardened line wrapping: `setMaxLines(Int.MAX_VALUE)` ensures long lines soft-wrap correctly.
- **Fix speed-test dialog not scrollable**
  - Custom-content dialogs (26+ source rows) are now wrapped in a `ScrollView` with a max height of ~55% screen height — all sources are visible and scrollable.
- **Fix download loop (always switching sources)**
  - Root cause: the download URL already carried an accelerator domain, so `resolve()` returned the same URL for every source, causing repeated retries of the same failed download;
  - Fix: use the raw GitHub asset URL and prepend each source's prefix individually; prefer the source that already fetched the manifest, retry at most 3 sources before aborting.
- **Concurrent speed testing for all sources**
  - All sources are probed concurrently (thread pool + CountDownLatch), no longer serial — dramatically faster;
  - New progress bar animation and real-time status feedback in the speed-test dialog.
- **Download read timeout increased to 120s**
  - Slow but working sources are no longer misjudged as failed.
- **README improved**
  - Added a "Project Structure" section listing key file paths with their responsibilities.

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
