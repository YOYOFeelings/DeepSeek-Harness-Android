# dsh-mobile-apk-yoyo 纯项目目录

> 每次执行任务前必须先读取本文件，了解项目目录与结构。

## 项目简介

- **deepseek HARNESS**（DeepSeek-Harness-Android 的 yoyo 重构版）安卓壳。
- 包名：`com.yoyo.dshmobile.shell`（applicationId / namespace）。
- 双渠道发布：正式版 + Data 测试版（App 名称 = deepseek HARNESS，版本号 = 1.0）。
- 当前重构主题：**引导页重构为安卓原生 View + 白色简洁统一主题**（颜色/样式单一来源，禁止页面硬编码）。

## 多模块结构

| 模块 | 类型 | 职责 |
|---|---|---|
| `:app` | application | Kotlin UI 壳（MainActivity / OnboardingActivity / onboarding / ui.theme / 会话页） |
| `:core` | library | 共享逻辑（namespace `com.yoyo.dshmobile.shell.core`；含共享日志 `log/Logs.kt`、`log/LogFox.kt`） |
| `:engine` | library | 引擎运行时核心（namespace `com.yoyo.dshmobile.engine`：EngineRootfs/EngineProcess/EngineService/EngineWatchdog/RuntimeUpdater） |
| `:native` | library（可选） | NDK C++ 模块（namespace `com.yoyo.dshmobile.shell.ffi`），由 `enableNative` 控制是否编译/内置 |

- `enableNative`：`gradle.properties` 与 `settings.gradle.kts` 中统一控制；`true` 时包含并内置 `:native`（打包 .so 入 APK），`false`（离线/无 NDK）时移出模块，`:app` 的依赖与 `BuildConfig.ENABLE_NATIVE` 随同一属性联动。
- 构建技术栈：AGP 8.8.2 / Kotlin 2.0.21 / Gradle 8.11.1；minSdk 26 / targetSdk 34 / compileSdk 36；Java 17。

## 目录树（截至当前）

```
dsh-mobile-apk-yoyo/                  # 项目根（DeepSeek-Harness 安卓壳 yoyo 重构版）
├── app/                              # :app 模块 —— Kotlin UI 壳
│   ├── build.gradle.kts              # 应用构建：applicationId/版本、enableNative 联动、Compose/DataStore/Shizuku/engine 依赖
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml       # 权限声明 + 引导页(Launcher)/主页 + ShizukuProvider（EngineService/FGS 已在 :engine 模块 manifest）
│       ├── java/com/yoyo/dshmobile/shell/
│       │   ├── MainActivity.kt       # 主界面壳：响应式导航（竖屏底部 BottomNavigationView / 横屏·平板侧边 NavigationRailView），Tab：主页/插件/会话/设置；含 SAF 目录选择器(OpenDocumentTree)
│       │   ├── AboutActivity.kt      # 独立「关于」页（全屏，折叠视差滚动，CoordinatorLayout 布局）；「发送日志」弹窗：保存日志=SAF 选目录落盘 zip，发送日志=系统分享（zip 落 cacheDir/logs_export/ 经 FileProvider 暴露，修复分享抛 StringIndexOutOfBoundsException）【新增】
│       │   ├── OnboardingActivity.kt # 引导页宿主（Launcher）：首启显示引导页，非首启直接进入 MainActivity 并关闭自身
│       │   ├── onboarding/
│       │   │   ├── OnboardingDataStore.kt   # DataStore 首启状态（is_first_launch）读写
│       │   │   ├── OnboardingScreen.kt      # Compose 引导页（本次保留不删）
│       │   │   ├── OnboardingScreenView.kt  # 引导页原生 View 组装（4 页 + 底部圆点 + ViewModel 状态绑定，本次新增；OnboardingActivity 由此承载）
│       │   │   ├── OnboardingViewModel.kt   # 引导页状态：Shizuku/Root/存储/通知权限 StateFlow + refreshShizukuStatus() + 授权结果回调
│       │   │   ├── ShizukuHelper.kt         # Shizuku 封装：服务/授权检测、requestPermission、runCommand（反射 newProcess）
│       │   │   ├── RootHelper.kt            # Root 封装：su 可用性检测与命令执行
│       │   │   └── RemotePolicyLoader.kt    # 远程用户协议拉取 + 超时/失败回退内置占位文本（本次新增）
│       │   └── ui/
│       │       ├── Ui.kt               # 通用样式工具：dp/color/roundedBg/screenTopBar/themedDialog + 间距令牌 SPACE_*（单一来源）
│       │       ├── ShimmerView.kt      # 骨架屏 shimmer 加载动画（主页首次进入，颜色走 R.color）
│       │       ├── LoadingButton.kt    # 带 loading 态的描边按钮（spinner+禁用+隐藏文字）
│       │       ├── screen/             # 主界面各功能页（原生 View，白色简洁风）
│       │       │   ├── HomeScreen.kt      # 主页仪表盘：状态卡片(运行时间秒级自增+内存/存储30s低频 IO 异步刷新，存储含 files/rootfs 引擎数据)/公告(随版本更新)/工作区(SAF目录)/快捷操作/更新横幅；「引擎状态」卡用 EngineProcess.probe 端口探活异步回填（检测中→运行中/未运行）；「插件数量」随 30s 周期动态刷新
│       │       │   ├── PluginsScreen.kt   # 插件页：内置(带「内置」徽标)与已安装插件列表（本次改造）
│       │       │   ├── ConversationScreen.kt # 会话导航页：引擎状态/启动/停止/打开会话/检查更新 + 内嵌引擎日志区(轮询 app-events.log 尾部)与下载横向进度条；更新=更新覆盖确认(已有数据时先弹 MD3 确认，确定才进镜像选择)+镜像选择弹窗(并发测速/点击即更/记忆上次)+MD3 进度弹窗(下载/校验/解压醒目进度)；启动=MD3 状态弹窗(状态行+日志区，未就绪/失败原因/运行中/超时终态，空版本误判已修复可强制更新)；操作按钮两排两列（启动|停止、打开会话|检查更新，lpBtnCell 等宽）
│       │       │   ├── ChatScreen.kt      # 对话页：会话交互
│       │       │   ├── PermissionModeScreen.kt # 设置内「权限模式」选择页
│       │       │   ├── SettingsScreen.kt  # 设置页：关于(进 AboutActivity)/更新/日志/「自动启动引擎」开关(默认关)/权限模式跳页/最底部「开发者设置」密码+协议门进入（本次新增）
│       │       │   ├── DeveloperSettingsScreen.kt # 开发者自测页：警示横幅 + 「更新直接提示(不检查版本)」展开卡(ValueAnimator 高度动画)，持久化 engine_prefs/dev_force_update【本次新增】
│       │       │   ├── LogsScreen.kt      # 日志页：列表 + 行内展开(可复制，无弹窗)
│       │       │   ├── UpdateScreen.kt    # 独立「更新」页：当前/最新版本 + 更新说明 + 往期版本(卡片式折叠 header，每条可展开更新日志并下载)，dev_force_update 开启时旁路版本比较强制视为有新版
│       │       │   ├── UpdateManager.kt   # 更新管理：GitHub Releases 拉取(含往期 body 更新日志)/带进度下载/安装；含 Announce 公告取数
│       │       │   ├── DeviceExecutor.kt  # 设备命令执行封装
│       │       ├── PluginStore.kt         # 插件存储(单一来源)：内置插件(assets/plugins 首次复制到 filesDir/plugins，bundled 标记) + 已安装插件统一扫描；loadPlugins/pluginCount
│       │       ├── assets-dir/plugins/    # APK 内置插件示例：engine-status.json / session.json【本次新增，首次启动复制到 filesDir/plugins】
│       │       │   └── WorkspacePrefs.kt  # 工作区目录偏好(DataStore)：保存/读取目录 URI 与显示名【新增】
│       │       ├── engine/SessionActivity.kt # 引擎会话 WebView 页（留在 :app），import 引擎类自 :engine
│       │       └── theme/Theme.kt      # Compose 主题（ModuleDataTheme，保留）
│       └── res/
│           ├── drawable/             # ic_home / ic_plugin / ic_settings 导航图标 + ic_terminal(被权限模式/主页复用) + bg_card_rounded（关于页白底圆角卡）
│           ├── drawable-nodpi/       # ic_launcher_foreground.png
│           ├── mipmap-anydpi-v26/    # ic_launcher.xml
│           ├── layout/               # layout_about_collapsing.xml（关于页折叠视差布局）【新增】
│           └── values/
│               ├── colors.xml        # 统一 dh_* 颜色（主题颜色单一来源）
│               ├── themes.xml        # Theme.Data 主题（引用 dh_* 颜色）
│               ├── styles.xml        # 统一按钮/卡片/链接样式（本次新增）
│               └── strings.xml       # 文案：engine_update_overwrite_*（覆盖确认）、engine_start_dialog_*（启动状态弹窗）、dh_ok 等本次新增
├── core/                             # :core 模块 —— 共享逻辑
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/yoyo/dshmobile/shell/
│           ├── core/AppConstants.kt、I18n.kt
│           └── log/Logs.kt、LogFox.kt   # 共享日志（由 :app/:engine 依赖 :core 引用）
├── engine/                           # :engine 模块 —— 引擎运行时核心（namespace com.yoyo.dshmobile.engine）
│   ├── build.gradle.kts              # library：依赖 :core + xz + commons-compress + coroutines + androidx
│   └── src/main/
│       ├── AndroidManifest.xml       # EngineService + FOREGROUND_SERVICE(_DATA_SYNC) + INTERNET
│       ├── assets/rootfs/.gitkeep    # rootfs 资产占位
│       ├── res/values/strings.xml    # 引擎通知/状态文案（EngineService 自用）
│       └── java/com/yoyo/dshmobile/engine/EngineRootfs / EngineProcess / EngineService / EngineWatchdog / RuntimeUpdater / EngineMirrors / RuntimePermissions
│           ├─ EngineService（引擎前台 Service）：runCatching 包裹引擎启动，失败写日志+通知「引擎启动失败」且不起看门狗；companion 暴露 @Volatile lastStartSeq/lastStartFailed/lastStartError 供会话页 MD3 启动状态弹窗实时反馈
│           │           ├─ EngineProcess（引擎进程）：直接启动 rootfs 内 usr/bin/node + usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web --port 3080（去 proot，rootfs 无 proot）；注入 LD_LIBRARY_PATH/LD_PRELOAD(termux-exec)/PATH/HOME/DSH_HOME/TMPDIR/TERMUX_*，工作目录 rootfs/home，直接 exec 被拒回退 system linker（按 Build.SUPPORTED_64_BIT_ABIS 动态选 linker64/linker）；探活 probe 用 TCP connect 127.0.0.1:3080 判定；启动前顺序：materializeSymlinks→ensureNodeLibsReal(node-deps 缺库即抛异常阻断)→verifyCriticalFiles+自愈(start-verify 诊断)→cleanupStaleEngine(pkill+扫描/proc 兜底 kill，防 EADDRINUSE)→env 组装→ensureExecutable→spawn；home/tmp mkdirs 失败抛异常；stop 先 destroy 再中断读线程
90→│           ├─ RuntimePermissions（本次新增，移植旧项目）：resolveTermuxExecPreload（解析/通配复制 termux-exec preload）、ensureExecutable（幂等补 exec 位 + setfattr 批量打 security.android.exec 属性，每批 ≤64）、materializeSymlinks/ensureNodeLibsReal（符号链接实体化 + node 8 个 DT_NEEDED 库真文件化，均用「tmp→rename」原子替换；库版本用 bestOf 精确匹配取最高）
│           ├─ RuntimeUpdater（引擎运行时下载源）：GitHub Release MANIFEST.txt（行格式 sha256/path/size，DEFAULT + FALLBACK），按设备 ABI 匹配 snapshot-{arm64|x86_64}.tar.xz → 下载 → SHA256 校验 → 解压替换；download 支持 mirror 入参，apply 支持 onPhase 阶段/进度回调；extractTarXz 保留符号链接 + 补 usr/bin、usr/lib exec 位 + 打 exec 属性 + 断言关键文件
│           └─ EngineMirrors（镜像/代理源表，本次新增）：Mirror(id,name,prefix) + resolve(url) + GH_HOSTS + 内置 25 项镜像（akaere/gh-proxy/official 等），仅代码内置无用户自定义入口；speedTest 测延迟
├── native/                           # :native 模块 —— 可选 NDK C++（enableNative 控制）
│   ├── build.gradle.kts              # CMake 3.22.1，abiFilters arm64-v8a + x86_64
│   └── src/main/
│       ├── cpp/CMakeLists.txt
│       ├── cpp/native-lib.cpp
│       └── AndroidManifest.xml
├── build.gradle.kts                  # 顶层插件版本声明（apply false）
├── settings.gradle.kts               # 模块声明：:app / :core / :engine，enableNative=true 时含 :native
├── gradle.properties                 # enableNative、android.ndkVersion=27.1.12297006 等
├── gradlew / gradlew.bat             # Gradle Wrapper 8.11.1
├── local.properties                  # sdk.dir=/opt/android-sdk（已 gitignore）
├── PROJECT_STRUCTURE.md              # 本文件（纯项目目录）
├── USER_HABITS.md                    # 用户习惯（每次任务前必读）
├── PITFALLS.md                       # 踩过的坑（每次任务前必读）
└── deepseek-harness-0.13.0-debug.apk # APK 输出到项目根
```

> 说明：`RemotePolicyLoader.kt`、`styles.xml` 为本重构系列新增，落地以最终实现路径为准（可能随实现微调）。

## 统一主题颜色表（本次核心）

颜色与样式**全部从 `colors.xml` / `themes.xml` / `styles.xml` 读取，禁止页面硬编码**；改主题只改这几处。

| 颜色名 | 色值 | 用途 |
|---|---|---|
| `dh_primary` | `#2D5F9E` | 主色 accent |
| `dh_on_primary` | `#FFFFFF` | 主色上的文字（按钮文字等） |
| `dh_background` | `#F4F5F7` | 页面背景 |
| `dh_surface` | `#FFFFFF` | 卡片/表面 |
| `dh_text_primary` | `#1A1A1A` | 主要文字 |
| `dh_text_secondary` | `#5F6368` | 次要文字 |
| `dh_text_faint` | `#9AA0A6` | 弱化文字 |
| `dh_divider` | `#E0E0E0` | 分隔线 |
| `dh_danger` | `#D93025` | 危险/错误 |
| `dh_success` | `#188038` | 成功 |
| `dh_warning` | `#F9AB00` | 警告 |
| `dh_link` | `#1A73E8` | 用户协议蓝色链接 |

## 权限说明

- **存储权限分版本**（读取状态按 SDK 版本区分）：
  - Android 13+（SDK ≥ 33）：`READ_MEDIA_*`（如 READ_MEDIA_IMAGES/VIDEO/AUDIO）；
  - SDK ≤ 32：`READ_EXTERNAL_STORAGE`（manifest 已声明 `maxSdkVersion="32"`）；
  - SDK ≤ 28：需额外 `WRITE_EXTERNAL_STORAGE`。
- **普通权限**：`INTERNET` / `VIBRATE` / `WAKE_LOCK`。
- **通知权限**：`POST_NOTIFICATIONS`（Android 13+ 运行时权限）。
- **Shizuku**：`moe.shizuku.manager.permission.API_V23` + `ShizukuProvider`（authorities = `${applicationId}.shizuku`，exported=true，permission=INTERACT_ACROSS_USERS_FULL）。

## 关键约定

- 纯原生 View 实现（LinearLayout/ScrollView/TextView），白色为主题、简洁、不简陋。
- 统一主题单一来源：颜色/样式一律读 `colors.xml`/`themes.xml`/`styles.xml`，防蓝白割裂。
- 引导页：`OnboardingActivity`（Launcher）→ 首启显示引导页 → 完成后进入 `MainActivity`；首启状态用 DataStore 持久化。
- APK 输出到项目根：`deepseek-harness-{versionName}-debug.apk`。
