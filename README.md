# DeepSeek-Harness-Android

![DeepSeek Harness](https://img.shields.io/badge/DeepSeek_Harness-blue?style=flat&logo=DeepSeek&logoSize=auto&color=%232D5F9E)
![Android](https://img.shields.io/badge/Android-blue?style=flat&logo=Android&logoSize=auto&color=%2397CA00)
![License MIT](https://img.shields.io/badge/License-MIT-yellowgreen?style=flat)
![Fork](https://img.shields.io/badge/Fork-二改版本-orange?style=flat)

> ⚠️ **重要说明：这是一个 fork / 二改版本。**
> 本项目由 [YOYOFeelings](https://github.com/YOYOFeelings)（中文昵称「孤独的」）在
> [kelai141/dsh-mobile-apk](https://github.com/kelai141/dsh-mobile-apk) 基础上二次开发维护，
> 在保留原版全部能力的同时，持续修复稳定性问题并迭代新功能。
> 详情见文末「二改 / Fork 说明」。

[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 的安卓壳：WebView 移动 UI 覆盖
**内嵌 Termux 运行时快照**（解压即跑，无需 Termux app）、SAF 目录桥、保活前台服务、**加固的引擎看门狗**、
以及**运行时在线更新**。一个 APK 装完即用：完整的 dsh web agent，且能**真实执行 bash**。

---

## 加入 QQ 群

欢迎加入官方交流群反馈问题、交流用法、获取更新提醒：

- **QQ 群 1：`200317338`**
- **QQ 群 2：`932593560`**

---

## 项目简介

一个 APK 开箱即用：

- **内嵌运行时**：随包 ~70MB xz 快照（node + bash + coreutils + dsh + 插件）；首启约 10 秒解压、
  从应用自身目录启动引擎；**完全离线**；
- **移动 UI**：系统 WebView 加载 `http://127.0.0.1:3080`，配响应式插件（手机端抽屉/sheet）；
- **保活**：前台服务（"dsh 引擎运行中"）+ 5 秒看门狗（引擎崩溃自动重启）；
- **在线更新**：manifest 驱动的快照热替换（下载 → sha256 → 原子切换 → 自动重启），
  运行时可自更新而无需更新 APK；多内置国内加速源 + 自定义源，**并发测速自动选最快源**；
- **SAF 桥**：`pickDirectory` 把所选目录映射为真实路径（`/storage/emulated/0/…`）；
- **内置日志系统（LogFox）**：私有目录存储、50MB 轮转裁剪、logcat 抓取、用户行为跟踪、崩溃快照，
  设置 → 日志 页可查看 / 统计 / 导出。

---

## 项目结构

```text
DeepSeek-Harness-Android/
├── app/
│   ├── build.gradle.kts          ← AGP, Kotlin, minSdk 26, targetSdk 34
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── snapshot.tar.xz   ← 内嵌 Termux 运行时快照（~70MB xz 压缩）
│       │   └── snapshot.sha256   ← 快照指纹（防升级后重解压覆盖）
│       ├── java/com/dshmobile/shell/
│       │   ├── MainActivity.kt       ← 入口：引导 / 引擎启动 / 在线更新 / 弹窗 / 日志埋点
│       │   ├── AndroidBridge.kt      ← JS 桥协议（checkEngine/pickDirectory/saveConfig 等）
│       │   ├── EngineManager.kt      ← 快照解压 / ELF 架构校验 / 引擎进程管理
│       │   ├── UpdateManager.kt      ← 运行时快照在线更新（manifest 拉取 + 镜像测速 + 下载 + 校验 + 切换）
│       │   ├── ApkUpdateManager.kt   ← APK 自更新（版本检查 + 下载 + 安装）
│       │   ├── LogFox.kt             ← 内置日志采集（用户行为 / logcat / 崩溃快照 / 50MB 轮转）
│       │   ├── SnapshotExtractor.kt  ← xz 解压引擎（带进度回调）
│       │   ├── RuntimePermissions.kt ← 可执行权限（exec 位 / Android 私有 exec 属性）
│       │   ├── TerminalView.kt       ← 终端模拟日志面板（ScrollView + TextView）
│       │   ├── TerminalScreen.kt     ← 终端页（底部导航 Tab 3）
│       │   ├── HomeScreen.kt         ← 主页（状态卡 + 公告 + 崩溃提示 + 操作按钮）
│       │   ├── SettingsScreen.kt     ← 设置页（通用 / 更新 / 存储 / 权限 / 外观 / 日志）
│       │   ├── PluginsScreen.kt      ← 插件管理页
│       │   ├── DialogUi.kt           ← 统一弹窗（Flat Minimalist 圆角白卡）
│       │   ├── I18n.kt               ← 中英文国际化
│       │   ├── Logs.kt               ← 日志目录与文件管理
│       │   ├── AnnouncementManager.kt← 公告拉取（主页公告卡）
│       │   ├── DownloadHistory.kt    ← 下载记录持久化
│       │   ├── EngineProbe.kt        ← 引擎 127.0.0.1:3080 探测
│       │   └── EngineService.kt      ← 前台服务（保活 + 看门狗）
│       └── res/                      ← 最小资源（矢量图标 / 颜色 / 圆角背景）
├── docs/
│   └── design.md                  ← 壳 APK 设计文档（桥协议 / 权限 / 页面结构）
├── ANNOUNCEMENT.md                ← 版本更新公告（中英双语，主页可查）
├── PITFALLS.md                    ← 踩坑记录
├── NOTICE.md                      ← 主页公告（新闻动态）
├── README.md                      ← 本文件
├── build.gradle.kts               ← 根构建脚本
├── settings.gradle.kts
└── gradle/                        ← Gradle 8.11.1 wrapper
```

---

## 构建

要求：JDK 17+、Android SDK（compileSdk 36）；Gradle 8.11.1 由 wrapper 提供。

```sh
# 1. 准备运行时快照（必须，约 70MB，作为 Release 资产分发）
#    方式 A：从 GitHub Releases 下载 snapshot-x86_64.tar.xz
#    方式 B：在 Termux 设备上自打（scripts/make-snapshot.sh）后拉取
mkdir -p app/src/main/assets
cp snapshot/snapshot.tar.xz app/src/main/assets/snapshot.tar.xz

# 2. 构建（缺快照会构建失败并提示）
./gradlew assembleDebug
# 产物: deepseek-harness-{版本号}-debug.apk（项目根目录）
```

---

## 桥协议 v1（`window.androidBridge`）

| 方法 | 签名 | 说明 |
|---|---|---|
| `version` | getter → string | 桥协议版本（`"1.0"`），页面 feature-detect 用 |
| `checkEngine` | () → string | 探测 127.0.0.1:3080；JSON `{running, latencyMs}` |
| `keepScreenOn` | (enable: boolean) | 屏幕常亮 |
| `showNotification` | (title, text) | 通知测试通道（POST_NOTIFICATIONS） |
| `pickDirectory` | (callbackId: string) | SAF 目录选择；结果经 `window.__dshBridge.onDirectoryPicked(callbackId, path)` 异步回传 |

桥协议让 APK 与 dsh 版本解耦：页面按 `androidBridge.version` 做特性检测。

---

## 在线更新协议（多镜像源）

1. App 拉取 GitHub Releases 的 **`MANIFEST.txt`** 作为发布清单，每行一行
   `sha256 path size`（发布运行时快照的校验与地址信息）；
2. 按设备 **ABI** 匹配对应的 `snapshot-{arm64|x86_64}.tar.xz` 资产；
3. 经所选更新源下载：内置**多镜像源** + 自定义源，选源使用**自动测速**，
   不可用的源会自动跳过，也可在「更新」页手动切换或添加自定义加速前缀；
4. 下载完成后先做 **SHA-256 校验** → 解压到 staging 目录（**不碰线上目录**）→
   **原子切换** `usr`（`usr` → `usr-old` → 新 `usr`）→ 杀掉旧引擎 → 看门狗用新运行时重启。

> 与上游一致的建议：Release 按双 ABI（arm64-v8a / x86_64）分发 APK，每个 APK 内嵌匹配架构快照，
> 装好即用、完全离线，无需在线修复；单 APK（如内嵌 x86_64 快照的调试包）在 arm64 设备上需联网
> 按上述协议下载匹配架构快照（更新源可在「更新」页配置）。

---

## 权限

`INTERNET`（WebView + 引擎探测）、`POST_NOTIFICATIONS`（通知通道）、
`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`（保活前台服务）。SAF 目录选择**无需权限**
（由系统文件管理器授权 tree URI）。

---

## ABI 与页大小

- x86_64 快照已端到端验证；
- arm64 快照由官方 Termux aarch64 仓库组装（见 `docs/design.md` §ABI）；
- **16KB 页**构建需在 16KB 设备上产出；
- APK 按 **ABI** 分发（内含快照与架构绑定，装错架构的包无法离线工作）。

---

## License

MIT 协议。版权同时署名 **kelai141**（原作者）与 **YOYOFeelings**（二改作者）。
第三方组件按各自许可（见依赖声明）。设计文档：`docs/design.md`。

---

## 二改 / Fork 说明

- **原作者**：[kelai141](https://github.com/kelai141) —— 原仓库：
  [kelai141/dsh-mobile-apk](https://github.com/kelai141/dsh-mobile-apk)
- **二改作者**：[YOYOFeelings](https://github.com/YOYOFeelings)（孤独的）—— 本仓库：
  [YOYOFeelings/DeepSeek-Harness-Android](https://github.com/YOYOFeelings/DeepSeek-Harness-Android)
- **官方 QQ 群**：群 1 `200317338`、群 2 `932593560`

本仓库是 `kelai141/dsh-mobile-apk` 的 **fork / 二改版本**，由 YOYOFeelings（孤独的）二次开发维护，
继续遵循 **MIT 协议**，完整保留并署名原作者。

感谢原作者 kelai141 的开源贡献；本 fork 在尊重原版权的前提下继续演进，欢迎使用与反馈。
