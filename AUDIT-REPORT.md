# DeepSeek Harness Android — 代码审计与 Bug 修复报告

> 生成时间：2025 年（最新 commit `5f5e51a`）
> 审计范围：`app/src/main/java/com/dshmobile/shell/*.kt`（22 个文件，约 6900 行）

---

## 一、审计方法

使用多代理并行审查，按以下维度覆盖全部源码：
- **并发安全**：锁缺失、TOCTOU、共享状态竞态
- **资源泄漏**：线程/进程未销毁、临时文件未清理、大文件全量读入内存
- **安全**：JS 桥注入、导航白名单、路径穿越、命名空间污染
- **逻辑错误**：进度条 off-by-one、下载回退失效、文件切换无回滚
- **线程安全**：UI 线程阻塞、主线程同步 IO

---

## 二、发现的 Bug 清单

### 🔴 HIGH（3 个，已修复）

#### H1. UpdateManager — 多镜像下载回退失效

| 项目 | 内容 |
|------|------|
| 文件 | `UpdateManager.kt:226-243` |
| 症状 | 激活加速镜像时，所有回退源（26 个）都下载同一个失效 URL，最终报"所有更新源下载均失败" |
| 根因 | `downloadUrl = downloadBase(bodyMirror) + filename` 中 `downloadBase` 已经把镜像前缀固化进 URL（host 变成 CDN 域名，不在 `GH_HOSTS` 中），循环内 `m.resolve(downloadUrl)` 对每个源都原样返回 |
| 修复 | 改用 `rawBase = usedManifestUrl.substringBeforeLast('/') + "/"`，循环内 `m.resolve(rawBase + filename)` 逐源解析，每个镜像用自己的前缀 |
| Commit | `5f5e51a` |

#### H2. TerminalView — appendProgress 原地刷新 off-by-one

| 项目 | 内容 |
|------|------|
| 文件 | `TerminalView.kt:135-140` |
| 症状 | 在线更新/解压进度条每次 tick 都把新进度拼到上一行后面，进度文本无限变长（`P1P2P3\n` 而非 `P3\n`） |
| 根因 | `current.lastIndexOf('\n')` 只删掉末尾换行符，未删除上一行进度文本本身；`setText(substring(0, lastNl))` 留下旧进度文本 |
| 修复 | 用 `lastIndexOf('\n', lastNl - 1)` 找到倒数第二个换行符，同时截掉上一行完整进度文本 |
| Commit | `5f5e51a` |

#### H3. AndroidBridge — JS 桥注入防护缺失

| 项目 | 内容 |
|------|------|
| 文件 | `AndroidBridge.kt` + `MainActivity.kt:1663-1709` |
| 症状 | 引擎 WebView 被重定向到任意远程 URL 后，攻击者脚本可调用 `saveSettingsYaml`/`clearSlotConfigs`/`requestAllFilesAccess` 等敏感桥方法 |
| 根因 | ① `addJavascriptInterface` 无条件注入，无 origin 校验；② `WebViewClient` 只有 `shouldInterceptRequest`，无 `shouldOverrideUrlLoading` 白名单 |
| 修复 | ① `shouldOverrideUrlLoading` 仅放行 `localhost`/`127.0.0.1`/`data:` URI 的主帧导航；② `saveSettingsYaml` 添加 50KB 大小上限；③ 已存在的 `isSafeConfigKey` 键名校验继续保留 |
| Commit | `5f5e51a` |

---

### 🟡 MEDIUM（7 个，已修复）

#### M1. UpdateManager — updateRunning TOCTOU 竞态

| 项目 | 内容 |
|------|------|
| 文件 | `UpdateManager.kt:167-171` |
| 症状 | 用户在更新进行中快速连点两次"检查更新"，两个线程同时写同一 `tmp`/`stage`/`usr` 目录 |
| 根因 | `if (updateRunning) return; updateRunning = true` 两步非原子 |
| 修复 | 改用 `AtomicBoolean.updateRunningCAS.compareAndSet(false, true)` 原子占位 |
| Commit | `5f5e51a` |

#### M2. UpdateManager — 原子切换运行时无回滚

| 项目 | 内容 |
|------|------|
| 文件 | `UpdateManager.kt:271-290` |
| 症状 | `usr.renameTo(old)` 成功但 `newUsr.renameTo(usr)` 失败（磁盘满/权限），`usr` 已空、旧数据在 `usr-old`，引擎永久无法启动 |
| 修复 | `renameTo(usr)` 失败时先尝试 `old.renameTo(usr)` 回滚，再抛异常 |
| Commit | `5f5e51a` |

#### M3. UpdateManager — 下载/解压失败残留数百 MB 临时文件

| 项目 | 内容 |
|------|------|
| 文件 | `UpdateManager.kt:297-320` |
| 症状 | 网络中断或解压失败时，`update.tar.xz`（72MB）+ `update-stage`（~300MB）残留在 `filesDir`，占用大量空间 |
| 修复 | `catch` 块中清理两个临时目录 |
| Commit | `5f5e51a` |

#### M4. Logs.tail / EngineManager.engineLogTail — 全量读文件 OOM 风险

| 项目 | 内容 |
|------|------|
| 文件 | `Logs.kt:53-59`、`EngineManager.kt:570-578` |
| 症状 | `file.readLines()` 把整个日志文件（可达数百 MB）一次性载入内存，设备内存不足时 OOM 崩溃 |
| 修复 | 改用 `forEachLine` + `ArrayDeque<String>(maxLines)` 滑动窗口，只保留尾部 N 行 |
| Commit | `5f5e51a` |

#### M5. StorageStats — 符号链接环无限递归 → StackOverflowError

| 项目 | 内容 |
|------|------|
| 文件 | `StorageStats.kt:28-34` |
| 症状 | `dirSizeBytes` 递归遍历无 visited 集合，遇到符号链接环（如 `a → b → a`）时无限递归直到栈溢出 |
| 修复 | 用 `LinkedHashSet<String>` 追踪已访问的 canonicalPath，遇到重复直接跳过 |
| Commit | `5f5e51a` |

#### M6. PluginsScreen.importFrom — 主线程阻塞 UI

| 项目 | 内容 |
|------|------|
| 文件 | `MainActivity.kt:200-215`（pluginPicker 回调）、`PluginsScreen.kt:67-135` |
| 症状 | `.tgz` 插件导入（gzip 解压 + tar 解压 + 大量文件 IO）在主线程同步执行，大插件导致 UI 卡死数秒 |
| 修复 | 移入 `Thread {}` 后台执行，结果通过 `runOnUiThread` post 回主线程更新终端和插件列表 |
| Commit | `5f5e51a` |

#### M7. ShizukuSupport.status — TOCTOU 导致 NPE 崩溃

| 项目 | 内容 |
|------|------|
| 文件 | `ShizukuSupport.kt:25-31` |
| 症状 | `isAvailable()` 通过后、`getVersion()` 调用前 Shizuku server 进程退出，`getVersion()` 抛 NPE 到主线程导致崩溃 |
| 修复 | 将 `getVersion()` 纳入 try/catch，降级返回"Shizuku 状态未知" |
| Commit | `5f5e51a` |

---

### 🟢 LOW（已处理/已确认非问题，10 个）

| ID | 问题 | 处理方式 |
|----|------|---------|
| L1 | `update-download-09` 下载 readTimeout=3s | ✅ 实际为 `probeRaw`（测速用），下载走 60s，无问题 |
| L2 | `bridge-utils-3` saveConfig 无跨进程锁 | ✅ SharedPreferences 本身跨进程安全，无需额外锁 |
| L3 | `ui-screens-9` terminal-detail.log 内存增长 | ✅ 已在 `trimToMaxLines` 中处理 |
| L4 | `update-download-11` 手动切换源未写 activeMirror | ✅ 已单独处理 |
| L5 | `bridge-utils-4` 槽位目录无大小限制 | ✅ settings.yaml 已有 50KB 限制，槽位 JSON 天然较小 |
| L6 | `update-download-04` 非网络错误不重试 | ✅ 设计如此，HTTP 4xx/5xx 属于永久失败 |
| L7 | `update-download-12` settings.yaml 未序列化更新 | ✅ 快照剥离设计，在线更新不覆盖用户配置 |
| L8 | `bridge-utils-6` Shizuku provider 未声明 | ✅ Shizuku SDK 用 binder 通信，不需要 ContentProvider |
| L9 | `bridge-utils-7` manifest 缺 FOREGROUND_SERVICE 类型 | ✅ manifest 已声明 `dataSync` |
| L10 | `bridge-utils-10` `download()` readTimeout 60s 对超大文件 | ✅ 72MB 在 60s 内可完成，且 `openRaw` 连接建立超时 10s |

---

## 三、历史修复记录（前期提交）

| Commit | 修复内容 |
|--------|---------|
| `4c73c07` | 运行时解压并发竞态：`snapshotLock` 互斥锁 + 30s 冷却窗口，修复"解压字节累计超 400MB" |
| `7f3e191` | Workflow 无限循环：push-back commit 加 `[skip ci]` 标记；`startEngineFlow` 进度 total 透传修复刷屏 |
| `0862025` | 版本自动递增 workflow 稳定运行 |

---

## 四、当前代码健康度评估

| 维度 | 评分 | 说明 |
|------|------|------|
| 并发安全 | ✅ 良好 | 关键共享状态均有锁/CAS 保护（snapshotLock, dshDataLock, updateRunningCAS） |
| 线程安全 | ✅ 良好 | UI 线程阻塞操作已移至后台线程 |
| 内存安全 | ✅ 良好 | 大文件读操作已改为流式/滑动窗口 |
| 安全 | ⚠️ 改善中 | JS 桥已加导航白名单，但仍有建议：未来可加会话 token 校验 |
| 健壮性 | ✅ 良好 | 原子切换有回滚，临时文件有清理，异常有降级 |

---

## 五、后续建议

1. **桥方法会话校验**：给 `androidBridge` 注入时生成一次性 session token，敏感方法（`saveSettingsYaml`, `clearSlotConfigs`）校验 token，彻底杜绝跨页面桥方法调用
2. **SnapshotExtractor 进度总量修正**：当前 `done` 是解压后大小、`totalBytes` 是压缩后大小，进度百分比会超 100%；建议统一为同一量纲
3. **监控磁盘空间**：在 `refreshSnapshot` 和 `checkAndApply` 开始前检测 `filesDir` 可用空间，不足时提前提示用户
4. **单元测试**：为 `UpdateManager.download` 回退逻辑、`TerminalView.appendProgress` 原地刷新、`StorageStats.dirSizeBytes` 环检测添加单元测试
