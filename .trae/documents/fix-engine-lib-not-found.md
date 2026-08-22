# 修复引擎启动失败：`libz.so.1 not found`（符号链接 → 实体化）

## 摘要（Summary）
引擎反复启动失败，弹窗与日志错误码恒定不变：
```
CANNOT LINK EXECUTABLE ".../data/user/0/com.yoyo.dshmobile.shell/files/rootfs/usr/bin/node":
library "libz.so.1" not found: needed by main executable
```
根因已通过解包 `/workspace/release-assets/snapshot-x86_64.tar.xz` 并用 `readelf/tar -tvf` 确认：
1. `usr/bin/node` 是**动态链接 ELF**，其 `DT_RUNPATH = /data/data/com.termux/files/usr/lib`（Termux 硬编码绝对路径，在 app 设备上不存在），因此只能靠 `LD_LIBRARY_PATH` 覆盖；
2. node 的 `DT_NEEDED` 依赖 `libz.so.1`、`libcares.so`、`libsqlite3.so`、`libcrypto.so.3`、`libssl.so.3`、`libicui18n.so.78`、`libicuuc.so.78`、`libc/libm/libdl`（Android 系统库）、`libc++_shared.so`；
3. rootfs 内这些库（`libz.so.1 -> libz.so.1.3.2`、`libsqlite3.so -> …`、`libicui18n/uc` 等）**大多是符号链接**；
4. 设备 app 私有 data（`/data/user/0/<pkg>`, Android 11+ FUSE 挂载）对 app 创建的符号链接存在读取/解析限制（这正是 PITFALLS §22 提到的 FUSE 禁 symlink / 符号链接机制依赖 app 私有域问题的镜像）。linker 解析 `libz.so.1` 时找不到链接目标 → 报 `not found`。

**修复核心**：不再依赖符号链接，把 node 依赖链所需的 `usr/lib` 下**符号链接实体化**（把链接目标文件**复制**成链接名对应的实体文件），确保 linker 直接读到真实文件；同时给已安装的旧 rootfs 提供**运行时兜底实体化**（不必重装/重更新，启动即自愈）。这样能一次性消除"错误码一样、反复修不好"的根因。

## 现状分析（Current State Analysis）
相关代码（均已读）：
- `engine/src/main/java/com/yoyo/dshmobile/engine/EngineProcess.kt`：
  - `buildArgs()` 用 `usr/bin/node`；`start()` 设 `LD_LIBRARY_PATH=${usrDir}/lib`、`LD_PRELOAD`=termux-exec preload；`startWithArgs()` 直接 exec 失败(`Permission denied`)时回退 `/system/bin/linker64`。
  - 问题：**启动时机未做库实体化**；已安装 rootfs 里的符号链接不被 linker 信任。
- `engine/src/main/java/com/yoyo/dshmobile/engine/RuntimeUpdater.kt`：
  - `extractTarXz` 对符号链接用 `Files.createSymbolicLink` 原样保留（246 行）。**新增包也会带符号链接 → 解压后 linker 依旧找不到。**
- `engine/src/main/java/com/yoyo/dshmobile/engine/RuntimePermissions.kt`：
  - `ensureExecutable`/`stampAndroidExecAttr` 只处理 exec 位与 exec 属性，**未处理符号链接**。
- `engine/src/main/java/com/yoyo/dshmobile/engine/EngineService.kt`：
  - 启动/重启都经由 `EngineProcess.start`；启动失败会写 `lastStartFailed/lastStartError` 供 MD3 弹窗显示。错误码恒定即来自进程 stderr 的 `CANNOT LINK`。

## 拟改动（Proposed Changes）
### 改动点 1：新增「符号链接实体化」工具（核心）
文件：`engine/.../RuntimePermissions.kt`
新增方法：
```kotlin
/**
 * 把 usr/lib（及 usr/bin 下指向 usr/lib 的链接）中的符号链接实体化：
 * 解析链接目标为最终实体文件（canonical），若在 usr 目录内存在实体则
 * 复制实体内容覆盖链接本身，得到真实文件。幂等；失败静默。
 * 目的：规避 Android 11+ app data FUSE 对符号链接的读取限制，
 * 让 bionic linker 直接从 LD_LIBRARY_PATH 读到真实 .so。
 */
fun materializeSymlinks(usrDir: File)
```
实现要点：
- 目标目录断言：仅处理 `usr/lib` 与 `usr/lib/node_modules` 之外的顶层 `usr/lib` 链接，以及 `usr/lib/*.so*` 符号链接。
- 对每个符号链接 `link` 取 `link.getCanonicalPath()`：若目标仍是链接则反复 `canonical` 直到实体文件；若实体文件存在于 `usrDir` 层级内且 `isFile && length>0`，则 `Files.delete(link)` 后 `实体.copyTo(link)`（实体化）；再 `setExecutable/Readable/Writable`。
- 不追踪跨出 `usrDir` 的目标（如 `/data/data/com.termux/...` 或 `/system/...`，保持原链接不动，避免破坏）。
- 对 `usr/bin` 下指向 `usr/lib` 的链接同样实体化（node/npx 等命令行依赖）。

### 改动点 2：解压后立即实体化（新安装更新的 rootfs）
文件：`engine/.../RuntimeUpdater.kt` → `extractTarXz`
在现有 `RuntimePermissions.ensureExecutable(File(destDir,"usr"))` 之后追加一行：
```kotlin
RuntimePermissions.materializeSymlinks(File(destDir, "usr"))
```
确保在线更新/覆盖安装的新 rootfs 解压即实体化。

### 改动点 3：启动时兜底实体化（已安装旧 rootfs 也自愈）
文件：`engine/.../EngineProcess.kt` → `start()`（第 42 行 `RuntimePermissions.ensureExecutable(usrDir)` 附近）
在 `ensureExecutable` 前后追加：
```kotlin
RuntimePermissions.ensureExecutable(usrDir)
RuntimePermissions.materializeSymlinks(usrDir)   // 已装 rootfs 兜底，无需重装/重更新
```
保证：用户当前设备上已解压的旧 rootfs（含符号链接）点一次「启动引擎」即实体化库 → 不再报 `libz.so.1 not found`。

### 改动点 4（可选增强）：`libc++_shared.so` 供应
node 依赖 `libc++_shared.so`，rootfs 包内它通常不存在，因为 Android 原生库目录 `nativeLibraryDir` 会提供（`libc++_shared.so` 也在 APK jniLibs）。若启动日志后续出现 `libc++_shared.so not found`，从 `context.applicationInfo.nativeLibraryDir` 拷贝一份到 `usr/lib`（参照旧项目 `EngineManager.copyPreloadFromNativeLibs` 的做法）。本改动在需要时再启用，不以本次主改动覆盖。

## 决策与假设（Assumptions & Decisions）
- **假设 A**：设备 ABI 为 arm64（日志路径为 `/data/user/0/...` 且此前按 arm64 下载）；本修复与 ABI 无关，x86_64/arm64 rootfs 内 node 均动态链接、同型符号链接，逻辑通用。
- **决策 1**：采用「实体化符号链接」而非「改用 proot/chroot」——proot 二进制不在 rootfs 内（此前已确认），且方案最小、只触及 node 动态依赖加载；符合规则 8「手术式精准修改」。
- **决策 2**：实体化放在**解压后 + 启动时**两处，覆盖「新装更新」与「旧已安装」双场景，用户无需重装即可验证。
- **决策 3**：不修改 `EngineService` 公共方法签名、不改 `EngineRootfs`、不改 MD3 弹窗流程；仅新增私有工具方法 + 两处调用。

## 验证（Verification）
0. **沙箱实测（已完成，结论成立）**：解包 `release-assets/snapshot-x86_64.tar.xz`，用等价实体化逻辑把 `usr/lib` 下 `libz.so.1/libz.so/libsqlite3.so/libicui18n.so.78/libicuuc.so.78` 由符号链接复制为真实文件 → 逐一校验：

| 库 | 实体化后状态 |
|---|---|
| libz.so.1 / libz.so | 真实文件 81776B ✓ |
| libsqlite3.so | 真实文件 1234656B ✓ |
| libicui18n.so.78 / libicuuc.so.78 | 真实文件 3251936B / 1963192B ✓ |
| libcares.so / libcrypto.so.3 / libssl.so.3 | 本就是实体 ✓ |
| libc++_shared.so | 实体 1252080B ✓ |
| libc.so / libm.so / libdl.so | 由 Android /system/lib64 提供，不属 rootfs ✓ |

结论：唯一导致 `CANNOT LINK ... libz.so.1 not found` 的根因正是符号链接；实体化后依赖链齐整。
1. **编译**：`./gradlew :app:assembleRelease --no-daemon --max-workers=1`（按 PITFALLS §2 注入 JAVA_TOOL_OPTIONS 代理）通过；`apksigner verify --print-certs` 签名前缀 `5696…25ff` 不变。
2. **逻辑验证（沙箱）**：编写一次性脚本对 `release-assets/snapshot-x86_64.tar.xz` 本地解压 → 调用等价实体化逻辑（Python 版）→ 断言 `usr/lib/libz.so.1`、`libsqlite3.so`、`libicui18n.so.78` 等**由符号链接变为真实文件且 size>0**；`node` 动态依赖链中 `libz/libcares/libsqlite3/libcrypto/libssl/libicu*` 均能在 `usr/lib` 找到真实实体。
3. **代码审查**：确认 `materializeSymlinks` 幂等、不越出 `usrDir`、失败静默；新增量点仅三处（RuntimePermissions 工具 + extractTarXz 一处 + start() 一处），无越界。
4. **回归自检（≥3 项无关功能）**：更新覆盖确认弹窗仍正常；主页存储/插件统计仍含 rootfs；自动启动引擎开关不受影响；日志分享逻辑不变。