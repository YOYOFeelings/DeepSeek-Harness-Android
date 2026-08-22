# dsh 引擎运行时骨架 Implementation Plan（AOI：arm64 / 只做规划）

> **For agentic workers:** 本计划用于在**后续会话**按任务逐步实现。当前阶段仅产出规划，不落代码。
> 任务用 checkbox（`- [ ]`）跟踪。

**Goal:** 在现有 yoyo 原生壳里集成 dsh 智能体引擎的**运行时骨架**——解压 rootfs → proot 启动 `127.0.0.1:3080` 引擎 → 前台 Service + 看门狗保活 → 新增 WebView「会话」页承接，并预留「运行时在线更新」与「SAF 目录桥」骨架。

**Architecture:** 采用参考项目 `deepcode-lab/deepseek-harness-mobile` 的架构：APK 内嵌 `rootfs.tar.xz` 资产，首启解压到 `filesDir/rootfs`；用 **proot（rootfs 内 aarch64 glibc 动态链接）** 运行 Node 22 + dsh engine，node 起的 web 引擎监听 `127.0.0.1:3080`；引擎生命周期归前台 Service，5s 看门狗探活并拉起；App 内保留现有原生 shell（终端/插件/日志/设置/权限模式），**新增**一个内嵌 WebView 的「会话」导航页加载引擎端口。

**Tech Stack:** Kotlin（原生 View + 已有工具 dp/color/themedDialog）、WebView、Android DataStore/SharedPreferences、java.io/tar 解压、HttpURLConnection（探活/下载）、`minSdk 26 / targetSdk 34 / compileSdk 36`、arm64-v8a。宿主 shell 不变，不引入额外第三方 UI。

---

## 0. 可行性结论（先回答“能不能适配安卓”）

**可行，但强依赖前置二进制资产。** 关键事实与对应设计：

1. **proot + engine 必须在「rootfs 内部的 glibc」里跑**，不能用 Android bionic。因此编译目标是 **aarch64-linux-gnu（glibc）**，不是 aarch64-linux-android。
2. **编译方式（后续本地做）**：在 dev 机用 `qemu-user` + Debian arm64 chroot 内编译/打包 `rootfs.tar.xz`：
   - 安装 Debian bookworm arm64（busybox/procps/bash/coreutils）、`aarch64-linux-gnu-gcc`；装 Node 22 官方 aarch64-linux 版或源码编译；
   - 编译 proot（源码 `https://github.com/ivanjia/proot-android` 或官方 proot 仓库），产出 rootfs 内 aarch64 动态链接 `proot` 可执行文件（切勿静态链接 Android bionic）；
   - 在 chroot 内 `npm ci` + 构建 dsh engine 到 `opt/dsh`，配置国内镜像；
   - 用 `tar -cJf rootfs.tar.xz -C rootfs .` 打包。产物约 80MB，交由下阶段放入 `app/src/main/assets/rootfs/`。
3. **ABI 仅 arm64**：运行期做 `Build.SUPPORTED_ABIS` / `is64` 断言 + intent 提示，避免 x86_64 误跑崩。
4. **网络离线**：引擎进程内无下载；唯一联网点是「运行时在线更新（可选里程碑）」与现有 App 的公告/协议。

> ⚠️ PITFALLS 新约定（写进规划，实现时遵守）：`proot` 二进制必须在 rootfs 内 glibc 下运行；任何 `/*` 都不要写进注释文本（避免 Kotlin 嵌套块注释）；Shizuku/ROOT 并非引擎运行前提（普通用户也能跑 proot 内引擎）。

---

## 1. 目标文件结构

| 文件（`app/src/main/java/com/yoyo/dshmobile/shell/` 下） | 职责 |
|---|---|
| `engine/EngineRootfs.kt` | 资产 `rootfs.tar.xz` → 校验 SH-256 → 解压 `filesDir/rootfs`；幂等（done 标记文件）。 |
| `engine/EngineProcess.kt` | 组装 proot 命令、`ProcessBuilder` 启动 node engine、端口 3080 探活、优雅停。 |
| `engine/EngineService.kt` | 前台 `Service`：持有命令进程、创建通知、转发探活/拉起（start/stop 幂等）。 |
| `engine/EngineWatchdog.kt` | 5s 轮询 `127.0.0.1:3080`，探测失败则 `forceStop` + 重启。 |
| `engine/RuntimeUpdater.kt` | （可选里程碑）manifest 下载→SHA-256 校验→阶段解压→原子切换→回滚→由 watchdog 重启。 |
| `engine/SessionActivity.kt` | 全屏 WebView 加载 `http://127.0.0.1:3080`；外部链接走系统浏览器；同源页面留在 WebView。 |
| `ui/screen/ConversationScreen.kt` | 原生壳「会话」导航页：显示引擎状态 + “打开会话/启动/停止”按钮 + `SessionActivity` 跳转。 |
| `MainActivity.kt` | 新增导航项 ID_CONVERSATION，Tab/抽屉 + icon + string。 |
| `res/values/strings.xml` | `nav_conversation`、`engine_*`、`session_*` 文案。 |
| `res/drawable/ic_conversation.xml` | 导航图标。 |

模块/资源配置改动：

- `app/src/main/AndroidManifest.xml`：新增 `SessionActivity`、`engine.EngineService`（`foregroundServiceType` 分版本）、`FOREGROUND_SERVICE`/`FOREGROUND_SERVICE_DATA_SYNC` 权限。
- `app/build.gradle.kts`：`assets.srcDirs` 含 `src/main/assets/rootfs`；保持 `abiFilters arm64-v8a`（引擎只在 arm64 生效）。
- `app/src/main/assets/rootfs/rootfs.tar.xz`：占位（本阶段不打包，由后续本地编译产出后放置）。

---

## 2. 里程碑与任务（此后执行；编号可独立完成）

### Task 0：资产与编译（前置，单独推进，不属于本骨架代码）
- [ ] 在本机 `qemu-user` arm64 Debian chroot 内编译 proot、Node 22、dsh engine，产出 `rootfs.tar.xz` 并登记到 `docs/plans/engine-binary-build-notes.md`。
- → 本任务不阻塞骨架开发：骨架用「探活=无引擎则提示」跑通，待资产就绪后切换为真解压。

### Task 1：EngineRootfs —— 资产校验与解压
**Files:**
- Create: `app/src/main/java/com/yoyo/dshmobile/shell/engine/EngineRootfs.kt`

- [ ] **Step 1: 常量与入口**：定义 `rootfsAsset = "rootfs/rootfs.tar.xz"`、`rootfsDir(context) = File(context.filesDir, "rootfs")`、`doneFile = File(rootfsDir, ".extracted")`、期望 SHA-256 常量（写注释为“资产就绪后回填”）。
- [ ] **Step 2: 实现 `ensureExtracted(context)`**：若 `doneFile` 存在→直接返回；否则打开 asset、校验、用 `TarInputStream` 解压（每次写入前 `mkdirs`，遇到 `../` 共 `normalize` 拒绝），全部完成后写 `doneFile`（内容=版本号）。任何异常返回语义化结果而非抛主线程。
- [ ] **Step 3: 暴露 `engineVersion(context)`/`isExtracted(context)`** 供 UI 显示。

### Task 2：EngineProcess —— proot 启动 + 端口探活 + 停止
**Files:**
- Create: `app/src/main/java/com/yoyo/dshmobile/shell/engine/EngineProcess.kt`

- [ ] **Step 1: 命令组装**：`buildArgs(rootfsDir)` 返回 `proot -r <rootfs> -b /proc -b /dev -b /sys -b /dev/urandom:/dev/urandom -w / /usr/bin/node --expose-internals /opt/dsh/web --port 3080`（先把 `proot` 解析为 rootfs 内绝对路径）。
- [ ] **Step 2: `start(context): EngineHandle`**：`ProcessBuilder(args).directory(rootfsDir).redirectErrorStream(true)` 启动，保存句柄；返回可取消的句柄。
- [ ] **Step 3: `probe(context, timeoutMs): Boolean`**：对 `http://127.0.0.1:3080` 发 `GET /healthz OR /`，`HttpURLConnection` 200 即 true；失败 false（每 500ms 重试直到超时）。
- [ ] **Step 4: `stop(handle)`**：`handle.destroy()` → `destroyForcibly()`（若 3s 未退），归还线程。

### Task 3：EngineService + EngineWatchdog —— 保活
**Files:**
- Create: `app/src/main/java/com/yoyo/dshmobile/shell/engine/EngineService.kt`
- Create: `app/src/main/java/com/yoyo/dshmobile/shell/engine/EngineWatchdog.kt`
- Modify: `app/src/main/AndroidManifest.xml`（service 声明 + 权限）

- [ ] **Step 1: Service 骨架**：`startService(context)`（前台通知：标题「deepseek HARNESS 引擎运行中」，频道用现有通知渠道，颜色走 `dh_primary`）；`onStartCommand` 里先 `EngineRootfs.ensureExtracted`，再 `EngineProcess.start`，再起 `EngineWatchdog`；`onDestroy` 停进程。
- [ ] **Step 2: 前台类型分版本**：API ≥ 34 用 `FOREGROUND_SERVICE_DATA_SYNC` + `foregroundServiceType="dataSync"`；API 26–33 仅 `startForeground`；manifest 加 `<uses-permission android:name="android.permission.FOREGROUND_SERVICE">` 与 `FOREGROUND_SERVICE_DATA_SYNC`（`tools:targetApi="34"`）。
- [ ] **Step 3: Watchdog**：单线程循环每 5s `EngineProcess.probe(...)`；失败→`lastDown` 记录并发重启（幂等）；进程退出事件也纳入重启判定；上限（如连续失败 N 次）后停止并写 `Logs.logEvent("Engine","watchdog-stop")`。
- [ ] **Step 4: 注册**：`MainActivity`/引导里按需 `startService`；`AndroidManifest` `android:stopWithTask="false"` 保持常驻。

### Task 4: SessionActivity + ConversationScreen —— 会话页与导航
**Files:**
- Create: `app/src/main/java/com/yoyo/dshmobile/shell/engine/SessionActivity.kt`
- Create: `app/src/main/java/com/yoyo/dshmobile/shell/ui/screen/ConversationScreen.kt`
- Modify: `app/src/main/java/com/yoyo/dshmobile/shell/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/drawable/ic_conversation.xml`

- [ ] **Step 1: SessionActivity**：`WebView` 开启 `setJavaScriptEnabled(true)`，加载 `http://127.0.0.1:3080`；`WebViewClient.shouldOverrideUrlLoading`：host 非 `127.0.0.1` → 交系统浏览器；`onReceivedError` 显示重试提示；`onBackPressed` 进 WebView 可后退。
- [ ] **Step 2: ConversationScreen**：原生壳内卡片展示引擎状态（`EngineRootfs.engineVersion`/`EngineProcess.probe`）、「启动引擎」「停止引擎」「打开会话(→SessionActivity)」；未解压时按钮置灰并提示「先安装运行时」。
- [ ] **Step 3: MainActivity 导航**：新增 `ID_CONVERSATION`，加入 BottomNavigationView/NavigationRailView（icon `ic_conversation` + `nav_conversation`）；`showScreen` 分支复用 `ConversationScreen` 实例（与终端同款缓存）。
- [ ] **Step 4: 文案/图标**：新增 `R.string.nav_conversation`、`engine_starting`、`engine_running`、`engine_stopped`、`engine_no_rootfs`、`session_open` 等；`ic_conversation.xml` 用 `dh_primary` 描边路径。

### Task 5（可后续）: RuntimeUpdater + SAF 桥骨架
- [ ] `RuntimeUpdater.kt`：manifest JSON（`version/url/sha256`）→ 下载到 `cacheDir` → 校验 → 阶段解压（`rootfs-new`）→ `rename(db) 原子切换` → 写版本 → EngineWatchdog 重启。失败回滚旧 rootfs。
- [ ] SAF 目录桥：复用现有 `WorkspacePrefs`/`OpenDocumentTree` 选择目录，经运行时卷映射 `-b <uri>:/work` 暴露给引擎（Android 无法直接 bind content://，需经 FUSE/NoBackup 缓存，标记为探索性）。

### Task 6：构建与交付
- [ ] JDK17 构建：`export JAVA_HOME=/root/.local/share/mise/installs/java/17.0.2; ./gradlew :app:assembleRelease --no-daemon`。
- [ ] 签名核对：`apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`，SHA-256 前缀 `5696…25ff`。
- [ ] APK 复制项目根 + `python3 -m http.server <port>` 提供下载。

---

## 3. 自审（against 需求）
- 「保留原生壳」→ Task 4 仅新增导航页，不动现有页 ✅
- 「新增会话页(参考推荐)」→ ConversationScreen + SessionActivity ✅
- 「先做运行时骨架/二进制后续本地编译适配安卓」→ Task 0 规划编译、Task 1–4 骨架先用“未就绪探活”跑通 ✅
- 「仅 arm64」→ Task 0/3 明确 ABI 断言与前台类型分版本 ✅
- 「适配安卓」→ minSdk/targetSdk、前台上限、WebView、权限都已落到具体 Task ✅

**功能/越权边界**：本骨架不改变现有设备命令执行（DeviceExecutor 权限沙箱）逻辑；引擎进程属于新独立子系统，只触碰新 `engine/*` 文件 + MainActivity 导航 + manifest + 资源。回归自检点：现有终端/插件/日志/设置/权限模式页与导航动画、`showScreen` 缓存、APK 签名均不受影响。