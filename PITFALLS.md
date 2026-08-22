# PITFALLS 踩过的坑（dsh-mobile-apk-yoyo）

> 每次执行任务前必须先读取本文件，避免重复踩坑。
> 沿用旧项目 `/workspace/dsh-mobile-apk/PITFALLS.md` 的环境坑，并记录本重构系列新增的坑与约定。

## 1. JDK 版本（构建环境）
- 默认 JDK 25 与 AGP 不兼容，构建会失败。
- 必须使用 JDK 17：
  ```bash
  export JAVA_HOME=/root/.local/share/mise/installs/java/17.0.2
  export PATH=$JAVA_HOME/bin:$PATH
  ```

## 2. Gradle 代理
- Gradle 发行版/依赖下载超时，需配置本地代理：
  `~/.gradle/gradle.properties` 写 systemProp 代理（`systemProp.http.proxyHost=127.0.0.1` / `systemProp.http.proxyPort=18080`，https 同理）。
- **坑（2026-08-20）**：本沙箱 `/root` 不可写，无法把代理写进 `~/.gradle/gradle.properties`；且 Gradle 默认不读环境变量 `HTTP_PROXY`。**解法**：用 `JAVA_TOOL_OPTIONS` 向 daemon JVM 注入系统属性：
  ```bash
  export JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=18080 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=18080"
  ```
- **新增依赖注意**：`org.tukaani:xz`、`org.apache.commons:commons-compress` 在 **Maven Central**（不在 google maven），`dependencyResolutionManagement` 已含 `mavenCentral()`，配好代理即可下载。
- 沙箱外网访问走 egress 代理 `127.0.0.1:18080`（curl 自动读 env 代理，Gradle/JVM 需手动注入如上）。

## 3. Android SDK 路径
- `local.properties` 中 `sdk.dir` 指向不存在路径会导致构建失败。
- 当前 SDK 路径：`/workspace/android-sdk`；`local.properties` 已被 .gitignore 忽略。

## 4. APK 签名验证
- 命令：`/workspace/android-sdk/build-tools/35.0.0/apksigner verify --print-certs <apk>`
- 历史签名 SHA-256 前缀：`5696…25ff`（同 APK 必须同签名，见 USER_HABITS §1）。

## 5. Shizuku 闪退守卫（本次踩坑/约定）
- **坑**：Shizuku 服务未运行时 `Shizuku.requestPermission()` 抛 `IllegalStateException` → 应用闪退。
- **修复**：
  - `ShizukuHelper.requestPermission()` 改为**返回 Boolean**（服务未运行返回 `false`，不抛异常）；
  - ViewModel 层先 `refreshShizukuStatus()`，状态为 `UNAVAILABLE` 时**不调用 requestPermission**，而是提示「请先启动 Shizuku 服务」；
  - `runCommand` 在服务未运行/未授权时返回**明确错误文案**，不崩溃。
- **注意**：`ShizukuHelper.requestPermission` 返回值类型变更后，需**同步所有调用点**（见 §8）。

## 6. 存储权限分版本（本次约定）
- Android 13+：用 `READ_MEDIA_*`；
- SDK ≤ 32：用 `READ_EXTERNAL_STORAGE`（manifest `maxSdkVersion="32"`）；
- SDK ≤ 28：需 `WRITE_EXTERNAL_STORAGE`。
- 权限状态读取需按 SDK 版本区分逻辑，不能一刀切。

## 7. 远程协议回退（本次约定）
- `RemotePolicyLoader` 从 GitHub raw 拉取用户协议，**超时/失败回退内置占位文本**，不阻断引导流程。
- 协议 URL **集中配置常量**，便于后期替换。

## 8. 统一主题单一来源（本次约定）
- 颜色/样式全部从 `colors.xml`/`themes.xml`/`styles.xml` 读取，**禁止页面硬编码**，防蓝白割裂。
- 改主题只改这几处；改颜色只改 `colors.xml`。

## 9. Kotlin 易错点
- viewModel 在原生 View 绑定要用 `ViewModelProvider` + `LifecycleOwner`（`ViewModelProvider(activity)[OnboardingViewModel::class.java]`），不要直接 `new`。
- `ShizukuHelper.requestPermission()` 返回值类型变更（void → Boolean）时，**必须同步调用点**，否则编译报类型不匹配。
- 注释里别写 `/**` 或 `/*`（Kotlin 块注释可嵌套，会被当作注释起点导致解析错乱）。

## 10. 测试版/正式版双渠道发布约定
- 参考旧项目 `/workspace/dsh-mobile-apk/PITFALLS.md §23`：
  - beta 发版必须标记 **Prerelease**，否则会变成 latest；
  - beta 同样附带 MANIFEST.txt + snapshot；
  - versionCode 单调递增不可回退；
  - 应用内「检查测试版更新」读 `releases?per_page=20` 首个 prerelease，版本比较需剥离 `-betaN` 后缀。

## 11. Root 检测 initial CHECKING 死锁（本次 New）
- `OnboardingViewModel` 的 `_rootState` 默认值是 `CHECKING`，但**不会自动触发检测**。
- 若把「检测 Root」按钮的 loading/禁用直接绑到 `rootState == CHECKING`，按钮会**默认就一直 loading 且被禁用**，用户永远点不了 → 功能死锁。
- **约定**：用 View 层标志 `rootCheckRunning` 表示「已触发检测」，点击时置 true，状态进入终态（AVAILABLE/UNAVAILABLE）时复位；初始的 CHECKING 不渲染 loading。

## 12. 一次性动画（shimmer）注意（本次 New）
- 自定义 `ShimmerView` 动画结束回调触发点：只在 `AnimatorListener.onAnimationEnd`（非 cancel），detach 时 `cancel()` 不会回调，避免切页后仍操作已移除视图。
- `start()` 需保证视图已完成测量（`content.post { start() }`）；`onSizeChanged` 里兜底补启动。

## 13. 打包签名（本次修复，规则 8.8）
- **坑**：yoyo 项目 `app/build.gradle.kts` 曾没有 `signingConfigs`，产出的 APK 用默认 debug 密钥（`3cd33caf…`），既不同于旧交付也不同于规则要求的发布签名，违反 8.8。
- **修复**：`keystore.properties` + `keystore/release.jks`（沿用旧发布签名 `5696…25ff`），`debug`/`release` 统一用项目签名。
- **约定**：打包前先 `apksigner verify --print-certs` 核对 SHA-256 前缀 `5696…25ff`；同一 APK 严禁换签名。
- 构建后在项目根目录生成 `deepseek-harness-{版本号}-{debug|release}.apk`，可用 `python3 -m http.server 8123` + 预览隧道导下载链接。

## 14. 内置日志 LogFox（本次 New）
- 原因：无 Shizuku/root 设备一开引导页即闪退，需私有目录日志分析。
- 方案：`DshApp`（Application）在 `attachBaseContext` 提前挂 `LogFox.installCrashHandler`，`onCreate` 起抓本进程 logcat。
- 日志落 `filesDir/logs/`：`user-actions.log`（逐步埋点定位崩溃阶段）、`logcat.log`、`crash-snapshot.txt`（崩溃快照）、`exceptions.log`。
- 不提供导出按钮；root 直接读私有目录即可。

## 15. Shizuku 应用识别（本次 New）
- **坑**：误以为需要在 `DshApp.onCreate` 调用 `Shizuku.init(context)`，实际 `dev.rikka.shizuku:api` 公共 API **没有** `init(Context)` 方法 → 编译报 `Unresolved reference 'init'`。
- **修正（2026-08-19）**：Shizuku 管理器识别应用靠 **AndroidManifest 中的 `ShizukuProvider`**，其 `authorities` 必须**严格等于 `${applicationId}`**（不是 `${applicationId}.shizuku`）。此前写成 `authorities="${applicationId}.shizuku"` 导致 Shizuku 管理器无法识别本应用、无法弹授权，已改回 `${applicationId}`。
- **约定**：识别能力=声明 Provider 且 `authorities="${applicationId}"`；代码里只调用 `ShizukuHelper` 封装的服务/授权检测，勿调用不存在的 `Shizuku.init`。

## 16. 统一弹窗 themedDialog 是 Context 扩展（本次 New）
- `themedDialog(title, message, ...)` 是 `fun Context.themedDialog(...)`，必须用 `context.themedDialog(...)` 调用；在成员函数里直接写 `themedDialog(...)` 会因 receiver 不匹配编译失败。

## 17. 引导页宽屏结构 + 去阴影 + loading 动画约定
- **宽屏「内容独动」结构**：左侧品牌列（`buildBrandPanel`）固定在 `ViewPager2` 之外，`ViewPager2` 只承载右侧内容页（每页用竖向 `ScrollView` 包裹实现独立纵向滚动）。→ 翻页时只有右内容滑动、logo 静止。窄屏单栏保持 `ViewPager2` 直接承载整页。
- **去四边阴影**：透明圆角 drawable + `elevation` 会产生四边方框阴影，观感差。卡片/按钮一律**不设 elevation**（或置 0）。
- **loading 动画约定**：内容加载（如 P2 协议）用 `ProgressBar` spinner；按钮异步期间用 `LoadingButton`（内置按钮内 spinner，`setPrimaryStyle()` 切主色实心 CTA）。
- **P3 按钮联动**：存储 + 通知权限齐全时 `permAction` 变「下一步」（可直接进 P4），否则为「获取权限」；`onPermissionsResultProcessed()` 在权限回调后复位 loading 并刷新按钮。滑动仍全局禁用（只点按钮翻页）。

## 18. 构建 daemon OOM 崩溃（本次 New）
- **坑**：沙箱内存紧张时 `assembleRelease` 的 Gradle daemon 会「disappeared unexpectedly」（可能被杀）。`gradle.properties` 的 `org.gradle.jvmargs=-Xmx2g` + 默认多 worker + Kotlin daemon 叠加，在 ~2GB 空闲下极易 OOM。
- **解法**：合并使用减小堆 + 单 worker：
  ```bash
  ./gradlew --stop
  export JAVA_TOOL_OPTIONS="-Xmx1g -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=18080 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=18080"
  export KOTLIN_DAEMON_JVM_OPTS="-Xmx900m"
  ./gradlew :app:assembleRelease --no-daemon --max-workers=1 -PenableNative=true
  ```
- 先跑 `./gradlew --stop` 杀掉残留 daemon，再单 worker 重跑最稳。

## 19. 引擎可观察性 + 下载源（本次 New）
- 骨架阶段 `:engine` 的 `assets/rootfs/` 只有 `.gitkeep`（无真实 rootfs），`RuntimeUpdater.MANIFEST_URL` 曾是占位 `example.com` → 必然 404（`update-manifest-http=404`），引擎起不来，属预期缺资产。
- **已接入真实下载源（2026-08-20）**：`RuntimeUpdater` 改为拉取 GitHub Release 的 `MANIFEST.txt`（**每行文本**：`sha256  path  size`，path 可带子目录），按设备 ABI（`abiName()`）匹配 `snapshot-{arm64|x86_64}.tar.xz`；下载 URL = 清单 URL `substringBeforeLast('/') + "/" + path`。
  - 主源 `YOYOFeelings/DeepSeek-Harness-Android` latest 仅含 **x86_64**；arm64 设备匹配不到自动落**回退源** `kelai141/dsh-mobile-apk`（双 ABI 全量）。目标设备多为 arm64，通常走回退源。
  - **下载 URL 用 basename 拼接（2026-08-20 踩坑）**：manifest 命中行的 path 可能带子目录前缀（如 `snapshot/snapshot-arm64.tar.xz`），但快照文件实际位于 release 根目录。**必须**取 `path.substringAfterLast('/')`（`snapshot-arm64.tar.xz`）再 `清单基址 + "/" + basename`；直接拼完整 path 会 404（实测 `.../v0.10.8/snapshot/snapshot-arm64.tar.xz` → 404，`.../v0.10.8/snapshot-arm64.tar.xz` → 200/75MB）。
  - 真实源无 JSON `version`；`Manifest.version` 置空串 + `ConversationScreen` 的 `current.isNotEmpty()` 门禁 → **干净安装（无 rootfs）始终尝试下载**。
- `ConversationScreen` 已内嵌：引擎日志区（轮询读 `app-events.log` 尾部，含下载进度/失败原因/引擎 proc 输出）+ 下载横向进度条（字节→百分比）。**日志流向**：更新/引擎/异常统一走 `Logs.logEvent → app-events.log`，读 `Logs.tail(...)` 展示。
- **注意**：`RuntimeUpdater.download` 的 `onProgress(done,total)` 回调在 IO 线程，更新 UI 必须先 `Handler(mainLooper).post`（见 `ConversationScreen.postUi/showProgress`），不能直接碰 View。
- **注意**：`RuntimeUpdater.checkForUpdate` 返回的 Manifest `url` 已是 basename 拼接的完整可下载地址（如 `.../v0.10.8/snapshot-arm64.tar.xz`），`download` 直接用该完整 URL，不可再拼接。

## 20. 引擎镜像/代理下载 + MD3 更新进度弹窗（本次 New）
- **坑**：国内直连 GitHub 拉 75MB rootfs 会 `SocketTimeoutException: timeout`（日志 `update-download-fail -> SocketTimeoutException`）。直连不可靠，必须走镜像/代理。
- **方案（2026-08-20）**：新增 `engine/.../EngineMirrors.kt`：
  - `Mirror(id,name,prefix)`：`resolve(url)` 仅当 url host ∈ `EngineMirrors.GH_HOSTS` 才前置镜像前缀，否则原样返回（official 空前缀=直连）。
  - **镜像表仅代码内置（25 项），无用户自定义入口**——添加镜像是开发者侧（yoyo）的事，终端用户只能「选择」，符合「代理只有我能添加」的约定。
  - `speedTest(url, mirror, timeoutMs)`：对 `mirror.resolve(url)` 做 GET 计时返回延迟 ms，失败 null。
- **`RuntimeUpdater.download` 加 `mirror` 入参**（默认 official）：用 `mirror.resolve(manifest.url)` 替换直连 URL；失败仍记 `update-download-fail` 返回 null。
- **`RuntimeUpdater.apply` 加 `onPhase` 回调**：校验→`(label,null)`、解压→随已读 XZ 字节/包长 0..100、停引擎/切换/重启→`(label,null)`。
- **ConversationScreen 更新流程（MD3）**：
  1. 弹**镜像选择弹窗**：并发 `speedTest` 测各镜像延迟并逐行刷新；**点某行立即用该镜像更新**（不强制等全部测完）；上次选择存 `SharedPreferences("engine_prefs")["engine_mirror_id"]` 并置顶标注「上次使用」。
  2. 选定后弹 **MD3 进度弹窗**：醒目横向 `ProgressBar`（主色 accent）+ 阶段文案 + 百分比/字节；下载/解压为确定进度，校验/切换为不确定（indeterminate）；成功「更新完成」自动关闭，失败显示原因可重试/关闭。
- **注意**：`RuntimeUpdater.apply` 的 `onPhase`/`onProgress` 回调在 IO 线程，更新弹窗 UI 必须先 `postUi`（Handler(mainLooper)）回主线程。
- **注意**：镜像前缀适用于 `github.com`/`objects.githubusercontent.com` 等 GitHub 域；若镜像本身不可达则 `speedTest` 返回 null（显示「连接失败」），但不阻止用户点该行尝试。

## 21. 引擎启动闪退 / 日志分享 / 解压缺陷 / 自动启动（本次 New）
- **坑 1：启动引擎闪退（proot 不存在）**：日志 `crash-snapshot.txt` 显示 `IOException: Cannot run program ".../rootfs/usr/bin/proot": error=2, No such file or directory`——rootfs 快照（snapshot-{arm64|x86_64}.tar.xz）**根本不含 proot**，且引擎入口实际为 `usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js`（旧代码写死 `/opt/dsh/web` 也不存在）。`EngineService` 主线程直接冒泡 IOException → 整个应用闪退。
  - **修复**：`EngineProcess` **去 proot**，直接启动 `<rootfs>/usr/bin/node --expose-internals <rootfs>/usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web --port 3080`；注入 `LD_LIBRARY_PATH`/`LD_PRELOAD`(termux-exec)/`PATH`/`HOME`/`DSH_HOME`/`TMPDIR`/`TERMUX_*`；工作目录=`rootfs/home`；直接 exec 被拒（Android 15+）回退 `/system/bin/linker64`。启动前 `RuntimePermissions.ensureExecutable`。
- **坑 2：日志分享抛 StringIndexOutOfBoundsException**：`app-events.log` 显示 `share-logs-fail -> StringIndexOutOfBoundsException: length=57; index=58`（`FileProvider$SimplePathStrategy.getUriForFile`）——`file_paths.xml` 把 `logs_export.zip` 当成**目录根**（root path 指向 zip 文件本身），FileProvider 要求目标文件必须位于所配置根目录**之下**。
  - **修复**：zip 输出改为 `cacheDir/logs_export/logs_export.zip`，`file_paths.xml` 的 `path="logs_export.zip"` → `path="logs_export/"`（**FileProvider 根目录必须是目录**，不能是文件）。
- **坑 3：extractTarXz 跳过符号链接 / 未补 exec 位**：旧解压直接跳过 `entry.isSymbolicLink`，且未按 mode 补 exec 位、未打 `security.android.exec` 属性 → 解压出的 rootfs 缺符号链接、Android 15+ 上 node 无法执行（error=13）。
  - **修复**：`RuntimeUpdater.extractTarXz` 保留符号链接（`Files.createSymbolicLink`，先 `deleteIfExists` 防悬空重解压冲突）；`usr/bin`、`usr/lib` 下文件按 `(mode and 0x49)!=0 || name 前缀` 补 exec 位；解压后 `RuntimePermissions.ensureExecutable(usr)`（内部含 `setfattr -n security.android.exec -v 1` 批量打标，每批 ≤64）；并**断言关键文件**（node / bin.js / termux-exec preload）存在，缺失抛异常走既有回滚。
- **坑 4：启动失败闪退（主线程）**：`EngineService.onStartCommand` 直接 `EngineProcess.start`，抛异常即闪退。
  - **修复**：`runCatching` 包裹；失败写日志 `engine-start-fail` + 通知「引擎启动失败」，**不起看门狗**（避免反复拉起坏引擎）；`restartEngine` 同样 runCatching。
- **新功能：自动启动引擎开关（默认关闭）**：设置页新增「自动启动引擎」开关行（SwitchCompat，主题色 `dh_primary`），状态持久化于 `SharedPreferences("engine_prefs")["auto_start"]`（默认 false）；`DshApp.onCreate` 读取开关，开启且 `EngineRootfs.isExtracted()` 时 `EngineService.start(this)`（全程 runCatching，不闪退）。
- **注意**：`RuntimePermissions.kt` 为 engine 模块**新增文件**（移植旧项目同名文件），`ensureExecutable(usrDir)` 接收 `rootfs/usr`；termux-exec preload 解析优先固定路径 `lib/libtermux-exec-ld-preload.so`，不存在时通配复制。

## 22. 主页加载慢 / 引擎状态判断 / 会话页按钮两列（本次 New）
- **坑 1：主页加载慢（主线程递归遍历 rootfs）**：`HomeScreen.refreshValues()` → `refreshMemStorage()` → `appStorageBytes()` 在 **init 主线程**递归遍历整个应用私有目录，含 `files/rootfs` 引擎运行时（node_modules 数千文件/符号链接），首帧渲染被阻塞（实测 MainActivity onCreate ≈1865ms）。
  - **修复**：测量全部改 `withContext(Dispatchers.IO)` 收集、主线程回填（首帧立即渲染，数值异步回填）；`refreshValues()` 只留 uptime 同步填充。
  - **口径变更（2026-08-20，见 §23）**：存储占用展示**含 rootfs**（用户要求统计上引擎数据，此前跳过 rootfs 导致「占用没统计上」）。因测量在 IO 协程，不影响首帧速度。
- **坑 2：引擎「是否启动」判断不统一**：主页 `engineReady()` 曾用 `ShizukuHelper.isRunning && isGranted`（Shizuku 授权状态 ≠ 引擎运行状态），主页「引擎状态」卡显示错误；会话页 `ConversationScreen` 与看门狗一直用 `EngineProcess.probe(127.0.0.1:3080)` 端口探活。
  - **修复**：主页「引擎状态」卡改用 `EngineProcess.probe(context,1500)`（协程 + IO，主线程不探活），初始显示「检测中…」（新增 `home_status_engine_checking` 文案），异步回填「运行中/未运行」；删除同步 `engineReady()`，保留 `engineReadyAsync()`（快捷操作「重启引擎」仍用）。
  - **约定**：**引擎运行状态 = 3080 端口探活（EngineProcess.probe）为唯一判定**，三处（主页/会话页/看门狗）一致；Shizuku 授权只用于权限模式/命令执行，不作为引擎运行依据。
- **坑 3：会话页按钮一排一个观感别扭**：启动/停止/打开会话/检查更新 4 个全宽按钮竖排太占空间。
  - **修复**：改两排两列——第 1 排「启动|停止」、第 2 排「打开会话|检查更新」（水平 LinearLayout + `weight=1f` + 左格 `rightMargin=dp(8)`，新增 `lpBtnCell(endGap)` 辅助方法）；`LoadingButton` 内嵌水平行保留 loading 行为；点击/启停/状态刷新逻辑不变。
- **注意**：本沙箱端口 8123/8124 常被占（已有 http.server 在服务），起下载服务时用其他空闲端口（如 8150），并先 `curl -sI` 验证 200 再给用户链接。

## 23. 无法更新（空版本误判）/ 更新覆盖确认 / 启动 MD3 状态弹窗 / 主页占用统计口径（本次 New）
- **坑 1：无法更新引擎（空版本误判「已是最新」）**：真实下载源 `MANIFEST.txt` 无 JSON `version`，`Manifest.version` 恒为空串 `""`；旧 `runUpdate` 判断 `current >= manifest.version` → `current >= ""` 恒真 → 永远提示「已是最新」，点更新不下载。
  - **修复**：门槛改为 `manifest.version.isNotEmpty() && current.isNotEmpty() && current >= manifest.version` 才跳过；**空版本一律放行继续下载**（干净安装/强制更新）。见 `ConversationScreen.runUpdate`。
- **坑 2：更新无覆盖确认**：已有 rootfs / 残留待安装包时点更新直接进入下载，会静默覆盖已有引擎数据。
  - **修复**：`doUpdate` 先判 `File(cacheDir,"rootfs-new.tar.xz").exists() || EngineRootfs.isExtracted(context)`，任一为真 → 先弹 MD3 覆盖确认弹窗（「更新将覆盖现有引擎数据（重新下载并安装），确定继续吗？」确定/取消），确定才进 `proceedUpdate()`（镜像选择→下载→进度弹窗）；否则直接进。新增 `proceedUpdate()` 供确认回调复用。
- **坑 3：启动引擎无过程反馈**：点「启动」无弹窗，不知道引擎在加载什么、失败也无原因。
  - **修复**：`EngineService` companion 新增 `@Volatile lastStartSeq/lastStartFailed/lastStartError`，每次 `EngineProcess.start` 尝试后更新；`ConversationScreen.doStart` 弹 **MD3 状态弹窗**（状态行 + 滚动日志区，轮询刷 `Logs.tail(appEventsLog,40)`）：
    1. rootfs 未就绪 → 「运行时未就绪，请先检查并更新引擎」+「去更新/关闭」；
    2. 启动中 → 轮询 `lastStartSeq` 变化，失败显示「启动失败: <原因>」+「重试/关闭」；
    3. `EngineProcess.probe(1500)` 探活 true → 「运行中」约 1s 后自动关闭并刷新状态；
    4. 20s 超时无结果 → 「仍在启动中，请查看日志」+「关闭」。
  - `strings.xml` 新增：`dh_ok`、`engine_update_overwrite_title/msg`、`engine_start_dialog_*` 系列（标题/启动中/探活中/运行中/失败/未就绪/卡住/重试/去更新/关闭）。
- **坑 4：主页占用统计没统计上**：§22 曾让 `appStorageBytes` 跳过 `files/rootfs`（为保首帧速度），导致「占用」恒偏小、用户看不到引擎数据占用。
  - **修复（口径变更）**：`appStorageBytes()` 改为统计 `filesDir` **全部子项（含 rootfs）** + cache/code_cache/databases/shared_prefs/no_backup；因测量仍在 `startSystemRefresh` 的 IO 协程（30s 低频），首帧不阻塞。`startSystemRefresh` 同时每周期刷新「插件数量」`pluginCount()`（此前仅构建时取一次静态值，不动态更新）。
- **注意**：§22 关于「存储跳过 rootfs」的表述已被本条 §23 覆盖，以**含 rootfs** 为准（用户明确要求统计上引擎数据）。

## 24. 引擎仍无法启动（libz.so.1 not found）/ 启动动画用不确定事件（本次 New）
- **坑 1：node 依赖库缺失/符号链接不可读（libz.so.1 not found）**：即使 §21 已 materializeSymlinks 实体化符号链接，仍可能因「链接创建后 readback 读不到（FUSE）」或「链接/文件直接缺失」导致 bionic linker 通过 `LD_LIBRARY_PATH` 找不到 `libz.so.1` 等精确 DT_NEEDED 库名。
  - **修复**：`RuntimePermissions` 新增 `ensureNodeLibsReal(usrDir)`：硬编码 node 非系统 DT_NEEDED 库清单（libz.so.1/libcares.so/libsqlite3.so/libcrypto.so.3/libssl.so.3/libicui18n.so.78/libicuuc.so.78/libc++_shared.so），逐库确保 `usr/lib/<name>` 为**真实且非空文件**——是链接则删后以同前缀实体复制覆盖；缺失则从同前缀实体补出。幂等、失败静默，返回「库名→是否到位」供诊断。
  - **调用点**：`RuntimeUpdater.extractTarXz`（解压新 rootfs 后）＋ `EngineProcess.start`（启动已装旧 rootfs 时自愈）；启动日志打 `node-deps real=n/m ...`，`missing` 为空即依赖就绪。
- **坑 2：启动弹窗要「不确定事件」动画而非日志卡片**：用户要求点「启动」用转圈（indeterminate）做过程反馈，**不要中间日志卡片**；且「未真正启动就不要弹结果弹窗」。
  - **修复（ConversationScreen）**：`showStartDialog` 改为「状态行 + 不确定转圈 +『查看日志』开关」，日志区默认 `GONE`（点开关才展开）；`doStart` 成功探活才 `dismiss`，失败/超时**不再弹第二个结果弹窗**，而是关掉启动弹窗并把真实原因内联到主卡 `statusText`（`engine_start_failed_inline` / `engine_start_stuck_inline`）。未就绪（无 rootfs）仍弹「去更新/关闭」动作弹窗（无转圈，属终态引导）。

## 25. 引擎仍报 libz.so.1 not found 的诊断与启动链路补齐（本次 New）
- **诊断栅栏（重要）**：`EngineProcess.start` 每次启动都会打印 `node-deps real=n/m`。若日志里**只有** `CANNOT LINK EXECUTABLE .../node: library "libz.so.1" not found` 而**没有**对应 `node-deps` 行 → 说明运行的 APK 是**修复前版本**（未部署），不是修复无效。先确认安装的 APK 是否含最新代码再排查其他。
- **根因确认**：快照里 `usr/lib/libz.so.1 -> libz.so.1.3.2`（符号链接），真实库 `libz.so.1.3.2` 存在；node ELF DT_NEEDED（readelf -d）为 8 个 usr 库（libz/libcares/libsqlite3/libcrypto.3/libssl.3/libicui18n.78/libicuuc.78/libc++_shared）。`ensureNodeLibsReal` 的逻辑（`candidates(name) or candidates(base) or candidates(prefix)` → 删链接→复制实体）经沙箱复刻验证能把 8 库全部实体化为真实文件。
- **补齐启动链路（EngineProcess.start 新增，顺序固定）**：
  1. `ensureExecutable` → `materializeSymlinks` → `ensureNodeLibsReal`（node-deps 诊断）；仍必须先于 spawn，保证 linker 经 LD_LIBRARY_PATH 读到真实库；
  2. `verifyCriticalFiles(usrDir)`：校验 node/bin.js/termux-exec preload 的存在+非空+exec 位；非空则 `ensureExecutable` + `resolveTermuxExecPreload` 自愈并记 `start-verify before=… after=…`；
  3. `cleanupStaleEngine()`：`pkill -f lib/bin.js`（runCatching 静默）清残留，防 3080 端口 EADDRINUSE 与探活误判「已运行」。
- **回归验证（沙箱）**：`tar -xJf snapshot-x86_64.tar.xz usr/lib` 后跑 ensureNodeLibsReal 同款逻辑 → 8 库均变 regular file 且非空；与 `readelf -d node` 的 NEEDED 一一对应。运行 node 需 bionic 环境（普通 Linux glibc 无法 exec），以「NEEDED soname 均有真实文件」为准。

## 26. 引擎启动模块代码审查缺陷修复（本轮）
基于对 EngineProcess/RuntimePermissions 的审查，修复如下（含对审阅项的校正）：
- **探活改 TCP**：`probe()` 由「GET / 判 200」改为「Socket connect 127.0.0.1:3080」，连接成功即视为启动，规避根路径未实现健康检查导致的误判。语义仍是判 3080 可达，不影响调用方（会话页 doStart、EngineWatchdog、主页探活）。
- **缺库阻断启动**：`ensureNodeLibsReal` 返回存在 false（如 libz 仍缺）时，`start()` 抛 `IllegalStateException("engine libs missing: …")`，不再无声超时；由 EngineService.runCatching 捕获置 lastStartFailed/lastStartError → 会话页内联展示。
- **实体化原子化**：`materializeOne()` 与 `ensureNodeLibsReal` 改为「先写同目录 `.tmp`+nanoTime 临时文件 → `renameTo` 原子替换」，失败删 tmp 且保留原链接/原文件，避免先删后拷断档。
- **残留清理不依赖 pkill**：`cleanupStaleEngine()` 先 pkill，再兜底扫描 `/proc/<pid>/cmdline` 含 `lib/bin.js` 的进程逐个 `kill`（多数 Android 无 pkill）。
- **库版本精确匹配**：`candidates()` 精确名优先；新增 `bestOf(base)` 用正则 `^base\.(\d+(\.\d+)*)$` 取版本号最大者的真实库（每段补零拼接作可比较键排序——`List<Int>` 不实现 Comparable，不能直接 `maxByOrNull{it.second}`）。规避 `libssl.so.3` 误选 `libssl.so.1`。
- **LD_PRELOAD 校验改可读**：termux-exec preload 用 `canRead()` 而非 `canExecute()`（动态库加载只需可读）。
- **其它**：`TERMUX_APP__DATA_DIR` 后备改 `applicationInfo.dataDir`；`stop()` 先 destroy 再中断读线程（消 InterruptedIOException 噪音）；`startWithArgs` 回退按 `Build.SUPPORTED_64_BIT_ABIS` 动态选 linker64/linker；home/tmp `mkdirs()` 失败抛异常；启动顺序改「materializeSymlinks→ensureNodeLibsReal→…→ensureExecutable(spawn前)」。
- **校正项**：minSdk=26 → java.nio.file 无需兼容；应用仅 arm64/x86_64（64 位）→ linker64 固有，动态选为防御；materializeSymlinks 平铺结构无需递归。
- **明确不改**（另行评估）：#10 setfattr 移除、#13 probe 子线程文档、#14 递归。

## 28. 打开会话 WebView 加载不了网页：明文 HTTP 被 system 拦（本轮 New）
- **坑**：`targetSdk=34`（≥28）默认禁明文流量，而会话页 `WebView` 加载 `http://127.0.0.1:3080` 是纯 HTTP；旧 manifest 无 `usesCleartextTraffic`/`networkSecurityConfig` ⇒ 打开会话必失败（`onReceivedError`，重试无效）。
- **修复（2026-08-20）**：新增 [`app/src/main/res/xml/network_security_config.xml`](../app/src/main/res/xml/network_security_config.xml)（`<base-config cleartextTrafficPermitted="true"/>`），并在 `<application>` 上 `android:networkSecurityConfig="@xml/network_security_config"`。WebView 仅访问 127.0.0.1（外链已交系统浏览器、镜像为 HTTPS），全量放行安全足够。
- **注意**：若日后要收紧，把 base-config 改成 targetSdk 34+ 仅对 127.0.0.1/localhost 放行的 `<domain-config>`；不要直接在属性里硬编码视觉值（走网络配置单一入口）。

## 27. 无法更新：manifest 检查不走镜像（本轮 New）
- **坑**：`RuntimeUpdater.checkForUpdate` 硬编码直连 `github.com` 的 DEFAULT/FALLBACK manifest，未使用用户所选镜像；`download()` 已用 `mirror.resolve()`，但 **manifest 检查从未走镜像**。国内直连 github 拉 manifest 会 `SocketTimeoutException`/404（`app-events.log` 的 `update-manifest-http=404` / `check-update-fail` → `update-check-null` → 会话页「更新失败」）。即「镜像只生效于下载、不生效于 manifest 检查」。
- **修复（2026-08-20）**：`RuntimeUpdater.checkForUpdate(context, mirror = <官方>)` 增加默认镜像参数；对每个候选 URL 先 `mirror.resolve(url)` 再请求；下载 URL 拼接改用 `resolvedUrl` 基址 + basename（与既有规则一致）。`ConversationScreen.runUpdate` 把选定 `mirror` 传入 `checkForUpdate(context, mirror)`，与 `download(…, mirror)` 对称。
- **回归验证（沙箱）**：经 `cdn.akaere.online` 前缀实测 DEFAULT manifest 200（仅 x86_64）、FALLBACK manifest 200（含 `snapshot-arm64.tar.xz` sha `18648745…`），且 `…/v0.10.8/snapshot-arm64.tar.xz`（basename 拼接）HEAD 200 / 75841724 字节——arm64 设备走回退源可正常下载。

## 29. shell-termux 报 “bash is not executable”：应用私有目录自愈 exec 位（本轮 New）
- **坑**：`shell-termux` 插件指向 `…/files/usr/bin/bash`，在部分 Android 设备上该 bash 缺可执行位，报 `BashError: .../usr/bin/bash is not executable; run 'pkg install bash' ... or fix bashPath/prefix`。
- **边界澄清**：`/data/user/0/<pkg>/files/usr` 是**应用私有数据目录**，云沙箱物理上够不到，无法远端 chmod；正确做法是**应用侧自愈**（装进去/启动时自动补 exec 位），让用户零操作。
- **修复（本轮）**：`EngineProcess.start` 在 spawn 前统一补设 exec 位处，新增对 `filesDir/usr` 这层独立 shell 树的幂等自愈——`ensureExecutable(File(context.filesDir,"usr"))`（内部为 `usr/bin/*` 补 `setExecutable` + stamp `security.android.exec`），目录不存在则静默跳过；成功则打 `shell-usr-selfheal exec-perm set` 日志。与引擎 `filesDir/rootfs/usr` 是**两条不同路径**，故需分别处理。
- **注意**：`RuntimePermissions.ensureExecutable(usrDir)` 只扫传入 `usrDir/bin` 与关键 `usr/lib`；shell bash 不在引擎 rootfs 下，**不能被现有引擎路径覆盖**，必须显式对该树再调一次。
- **仍可能需设备端兜底**：若 bash 属于独立安装的 Termux（非 App 打包），本 App 自愈不影响它；此时在 Termux 里 `pkg install bash` 或 `chmod +x …/usr/bin/bash` 依旧有效。
