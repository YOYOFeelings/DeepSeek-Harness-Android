# DeepSeek-Harness-Android (YOYO 重构版)

![DeepSeek Harness](https://img.shields.io/badge/DeepSeek_Harness-blue?style=flat&logo=DeepSeek&logoSize=auto&color=%232D5F9E)
![Android](https://img.shields.io/badge/Android-blue?style=flat&logo=Android&logoSize=auto&color=%2397CA00)
![License MIT](https://img.shields.io/badge/License-MIT-yellowgreen?style=flat)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple?style=flat&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-2024.12.01-4285F4?style=flat&logo=jetpackcompose&logoColor=white)

> ⚠️ **重要说明：这是一个二次重构版本。**
> 本项目由 [YOYOFeelings](https://github.com/YOYOFeelings)（中文昵称「孤独的」）在
> [kelai141/dsh-mobile-apk](https://github.com/kelai141/dsh-mobile-apk) 基础上进行深度重构，
> 在保留原版全部能力的同时，重构为多模块架构，引入 Jetpack Compose 和原生 View 混合 UI，
> 并持续修复稳定性问题和迭代新功能。

## 项目简介

**DeepSeek-Harness-Android** 是 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 的安卓壳应用，提供完整的移动端 Web Agent 体验。

### 核心特性

- **内嵌运行时**：随包内置 Termux 运行时快照（~70MB xz 压缩），包含 Node.js、Bash、coreutils、DSH 及插件；首启约 10 秒解压，完全离线运行
- **混合 UI 架构**：Jetpack Compose + 原生 View 混合实现，白色简洁统一主题
- **多模块设计**：`:app`（UI 壳）、`:core`（共享逻辑）、`:engine`（引擎运行时）、`:native`（可选 NDK）
- **保活机制**：前台服务（"dsh 引擎运行中"）+ 5 秒看门狗（引擎崩溃自动重启）
- **在线更新**：manifest 驱动的快照热替换，支持多镜像源并发测速
- **SAF 桥**：`pickDirectory` 把所选目录映射为真实路径（`/storage/emulated/0/…`）
- **引导页系统**：首次启动引导页，支持 Shizuku/Root 权限检测
- **插件管理**：内置插件 + 已安装插件统一管理
- **会话管理**：引擎状态监控、启动/停止控制、日志查看

---

## 功能详细说明

### 1. 引擎管理

- **引擎状态监控**：实时显示引擎运行状态（运行中/未运行/检测中）
- **启动/停止控制**：一键启动或停止引擎进程
- **自动重启**：看门狗机制，引擎崩溃后自动重启
- **端口探测**：通过 TCP 连接探测 127.0.0.1:3080 判断引擎状态

### 2. 会话管理

- **WebView 会话**：内嵌 WebView 加载 `http://127.0.0.1:3080`
- **日志查看**：实时查看引擎日志输出
- **下载进度**：显示文件下载进度条
- **状态弹窗**：MD3 风格状态弹窗，显示引擎启动状态

### 3. 插件系统

- **内置插件**：预装引擎状态插件、会话插件
- **插件扫描**：自动扫描已安装插件
- **插件管理**：查看、启用、禁用插件

### 4. 更新系统

- **运行时更新**：GitHub Releases 拉取，支持多镜像源
- **APK 更新**：应用内检查更新，支持测试版
- **版本管理**：查看当前版本、最新版本、往期版本

### 5. 设置系统

- **通用设置**：主题、语言等基础设置
- **权限模式**：Shizuku/Root 权限管理
- **开发者设置**：密码保护的开发者选项
- **日志管理**：查看、导出、分享日志

---

## 项目结构

```
dsh-mobile-apk-yoyo/
├── app/                              # :app 模块 —— Kotlin UI 壳
│   ├── build.gradle.kts              # 应用构建配置
│   └── src/main/
│       ├── AndroidManifest.xml       # 权限声明 + 引导页/主页
│       ├── java/com/yoyo/dshmobile/shell/
│       │   ├── MainActivity.kt       # 主界面壳（响应式导航）
│       │   ├── AboutActivity.kt      # 关于页（折叠视差滚动）
│       │   ├── OnboardingActivity.kt # 引导页宿主（Launcher）
│       │   ├── onboarding/           # 引导页相关组件
│       │   └── ui/                   # UI 组件和屏幕
│       │       ├── Ui.kt             # 通用样式工具
│       │       ├── screen/           # 主界面各功能页
│       │       ├── PluginStore.kt    # 插件存储管理
│       │       └── theme/Theme.kt    # Compose 主题
│       └── res/                      # 资源文件
├── core/                             # :core 模块 —— 共享逻辑
│   ├── build.gradle.kts
│   └── src/main/
│       └── java/com/yoyo/dshmobile/shell/
│           ├── core/AppConstants.kt  # 应用常量
│           └── log/                  # 日志系统
├── engine/                           # :engine 模块 —— 引擎运行时核心
│   ├── build.gradle.kts
│   └── src/main/
│       └── java/com/yoyo/dshmobile/engine/
│           ├── EngineRootfs/         # 文件系统管理
│           ├── EngineProcess/        # 引擎进程管理
│           ├── EngineService/        # 前台服务
│           ├── EngineWatchdog/       # 看门狗机制
│           ├── RuntimeUpdater/       # 运行时更新
│           └── RuntimePermissions/   # 权限管理
├── native/                           # :native 模块 —— 可选 NDK C++
│   ├── build.gradle.kts
│   └── src/main/cpp/                 # C++ 原生代码
├── docs/                             # 文档目录
├── keystore/                         # 签名密钥库
├── build.gradle.kts                  # 顶层构建脚本
├── settings.gradle.kts               # 模块声明
└── gradle.properties                 # Gradle 属性配置
```

---

## 技术栈

- **语言**：Kotlin 2.0.21
- **UI 框架**：Jetpack Compose + 原生 View
- **构建工具**：Gradle 8.11.1 + AGP 8.8.2
- **最低 SDK**：Android 8.0 (API 26)
- **目标 SDK**：Android 14 (API 34)
- **编译 SDK**：Android 15 (API 36)
- **Java 版本**：17
- **依赖管理**：Gradle Version Catalog

---

## 构建说明

### 环境要求

- JDK 17+
- Android SDK（compileSdk 36）
- Gradle 8.11.1（由 wrapper 提供）

### 构建步骤

```bash
# 1. 克隆项目
git clone https://github.com/YOYOFeelings/dsh-mobile-apk-yoyo.git
cd dsh-mobile-apk-yoyo

# 2. 准备运行时快照（必须）
#    方式 A：从 GitHub Releases 下载 snapshot-x86_64.tar.xz
#    方式 B：在 Termux 设备上自打（scripts/make-snapshot.sh）后拉取
mkdir -p engine/src/main/assets/rootfs
cp snapshot.tar.xz engine/src/main/assets/rootfs/snapshot.tar.xz

# 3. 构建 Debug 版本
./gradlew assembleDebug

# 4. 构建 Release 版本
./gradlew assembleRelease
```

### 构建产物

- **Debug 版本**：`deepseek-harness-{versionName}-debug.apk`
- **Release 版本**：`deepseek-harness-{versionName}-release.apk`

产物会自动复制到项目根目录。

---

## 权限说明

### 普通权限

- `INTERNET`：网络访问（WebView + 引擎探测）
- `VIBRATE`：振动反馈
- `WAKE_LOCK`：保持屏幕常亮

### 运行时权限

- `POST_NOTIFICATIONS`：通知权限（Android 13+）
- `READ_MEDIA_*`：媒体访问权限（Android 13+）
- `READ_EXTERNAL_STORAGE`：存储读取权限（Android 12 及以下）

### 特殊权限

- **Shizuku**：通过 Shizuku 获取 Root 权限
- **Root**：通过 su 命令获取 Root 权限

---

## 模块说明

### :app 模块

- **职责**：UI 壳，包含所有界面和交互逻辑
- **主要组件**：
  - `MainActivity`：主界面，响应式导航（底部导航栏/侧边导航栏）
  - `OnboardingActivity`：引导页宿主（Launcher）
  - `AboutActivity`：关于页（折叠视差滚动）
  - 各功能屏幕：主页、插件、会话、设置等

### :core 模块

- **职责**：共享逻辑，供其他模块依赖
- **主要组件**：
  - `AppConstants`：应用常量定义
  - `I18n`：国际化支持
  - `Logs` / `LogFox`：日志系统

### :engine 模块

- **职责**：引擎运行时核心，管理引擎生命周期
- **主要组件**：
  - `EngineRootfs`：文件系统管理
  - `EngineProcess`：引擎进程管理（启动/停止/监控）
  - `EngineService`：前台服务（保活）
  - `EngineWatchdog`：看门狗机制（自动重启）
  - `RuntimeUpdater`：运行时更新
  - `RuntimePermissions`：权限管理

### :native 模块（可选）

- **职责**：NDK C++ 原生代码
- **启用条件**：`gradle.properties` 中 `enableNative=true`
- **主要功能**：原生桥接、性能优化

---

## 主要界面

### 1. 引导页

- **功能**：首次启动引导，权限检测
- **组件**：
  - Shizuku 状态检测
  - Root 权限检测
  - 存储权限检测
  - 通知权限检测

### 2. 主页

- **功能**：仪表盘，显示关键信息
- **组件**：
  - 引擎状态卡片（运行时间、内存、存储）
  - 公告卡片
  - 工作区目录选择
  - 快捷操作按钮
  - 更新横幅

### 3. 插件页

- **功能**：插件管理
- **组件**：
  - 内置插件列表（带"内置"徽标）
  - 已安装插件列表

### 4. 会话页

- **功能**：引擎会话管理
- **组件**：
  - 引擎状态显示
  - 启动/停止按钮
  - 打开会话按钮
  - 检查更新按钮
  - 引擎日志区
  - 下载进度条

### 5. 设置页

- **功能**：应用设置
- **组件**：
  - 关于页面入口
  - 更新设置
  - 日志设置
  - 自动启动引擎开关
  - 权限模式选择
  - 开发者设置（密码保护）

---

## 主题系统

### 颜色定义

所有颜色定义在 `colors.xml` 中，禁止在代码中硬编码：

| 颜色名 | 色值 | 用途 |
|---|---|---|
| `dh_primary` | `#2D5F9E` | 主色 accent |
| `dh_on_primary` | `#FFFFFF` | 主色上的文字 |
| `dh_background` | `#F4F5F7` | 页面背景 |
| `dh_surface` | `#FFFFFF` | 卡片/表面 |
| `dh_text_primary` | `#1A1A1A` | 主要文字 |
| `dh_text_secondary` | `#5F6368` | 次要文字 |
| `dh_text_faint` | `#9AA0A6` | 弱化文字 |
| `dh_divider` | `#E0E0E0` | 分隔线 |
| `dh_danger` | `#D93025` | 危险/错误 |
| `dh_success` | `#188038` | 成功 |
| `dh_warning` | `#F9AB00` | 警告 |
| `dh_link` | `#1A73E8` | 链接颜色 |

### 主题规范

- **白色简洁风**：所有界面统一白色背景
- **单一来源**：颜色/样式一律读 `colors.xml`/`themes.xml`/`styles.xml`
- **禁止硬编码**：界面中禁止直接使用十六进制颜色值

---

## 在线更新协议

1. **清单拉取**：从 GitHub Releases 拉取 `MANIFEST.txt`
2. **ABI 匹配**：按设备架构匹配对应的快照文件
3. **多镜像源**：内置多镜像源 + 自动测速
4. **下载校验**：SHA-256 校验 + 解压到 staging 目录
5. **原子切换**：`usr` → `usr-old` → 新 `usr`，自动重启引擎

---

## 开发者设置

### 进入方式

设置页 → 最底部 → 点击 10 次 → 输入密码 `123456`

### 可用功能

- **更新直接提示**：跳过版本检查，强制视为有新版
- **开发者日志**：详细日志输出

---

## 调试信息

### 日志系统

- **存储位置**：应用私有目录
- **轮转策略**：50MB 自动轮转
- **日志类型**：用户行为、logcat、崩溃快照
- **导出方式**：保存到 SAF 目录或系统分享

### 崩溃处理

- **崩溃捕获**：自动捕获未处理异常
- **崩溃快照**：保存崩溃堆栈和设备信息
- **崩溃日志**：保存到私有目录，可导出分析

---

## 二改 / Fork 说明

- **原作者**：[kelai141](https://github.com/kelai141)
  - 原仓库：[kelai141/dsh-mobile-apk](https://github.com/kelai141/dsh-mobile-apk)
- **二改作者**：[YOYOFeelings](https://github.com/YOYOFeelings)（孤独的）
  - 本仓库：[YOYOFeelings/dsh-mobile-apk-yoyo](https://github.com/YOYOFeelings/dsh-mobile-apk-yoyo)

### 重构内容

- **多模块架构**：重构为 `:app`、`:core`、`:engine`、`:native` 四个模块
- **UI 重构**：引入 Jetpack Compose + 原生 View 混合 UI
- **引导页重构**：全新引导页系统，支持权限检测
- **主题统一**：白色简洁统一主题，颜色单一来源
- **代码优化**：重构引擎管理、更新系统、日志系统

---

## 许可证

MIT License. Copyright同时署名 **kelai141**（原作者）与 **YOYOFeelings**（二改作者）。

第三方组件按各自许可（见依赖声明）。

---

## 贡献

欢迎提交 Issue 和 Pull Request！

### 开发规范

- 遵循 Kotlin 编码规范
- 界面开发遵循主题规范
- 提交前确保代码通过检查
- 新增功能需要添加相应文档

---

## 联系方式

- **GitHub**：[YOYOFeelings](https://github.com/YOYOFeelings)
- **QQ 群**：欢迎加入交流群反馈问题

---

## 更新日志

详见 [ANNOUNCEMENT.md](ANNOUNCEMENT.md)

---

## 相关链接

- [DeepSeek Harness 官方仓库](https://github.com/deepseek-ai/deepseek-harness)
- [原版 Android 壳](https://github.com/kelai141/dsh-mobile-apk)
- [Android SDK 下载](https://developer.android.com/studio#command-tools)
- [Kotlin 官方文档](https://kotlinlang.org/docs/home.html)
- [Jetpack Compose 官方文档](https://developer.android.com/jetpack/compose)