# 壳 APK 设计（DeepSeek-Harness-Android）

> 版本 v1.0 ｜ 2026-08-14 ｜ 前置：M1-PLAN §M1.3；决策 D：M1 只留通道，Foreground Service 留 M2（与 Shizuku 合并）

---

## 1. 形态与边界

- **纯壳**：WebView 只消费 `http://127.0.0.1:3080`（Termux 内 dsh web 服务）；APK 与引擎版本彻底解耦（桥协议版本化 `androidBridge.version`）；
- **Termux 未运行时**：引导页（提示启动命令 + 一键跳转 Termux + 重试）；
- **零 fork、零侵入**：页面侧不做任何改动；桥能力全部经 `@JavascriptInterface` 注入。

## 2. 桥协议 v1（window.androidBridge）

| 方法 | 签名 | 说明 |
|---|---|---|
| version | getter String | 桥协议版本 `"1.0"`（feature-detect 用） |
| checkEngine | () → String | 探测 127.0.0.1:3080 可达性，返回 `{running:bool, latencyMs:int}` JSON |
| keepScreenOn | (enable: Boolean) | 屏幕常亮开关 |
| showNotification | (title, text) | 通知测试通道（POST_NOTIFICATIONS 运行时请求） |
| pickDirectory | (callbackId: String) | SAF 目录选择（ACTION_OPEN_DOCUMENT_TREE）；结果异步经 `onDirectoryPicked(callbackId, path)` 回 JS（JS 侧注册回调表） |
| onDirectoryPicked | (callbackId, path) | Kotlin→JS 回传：content:// → 真实路径映射（/storage/emulated/0 前缀时 DocumentsContract 解码；否则原样 content://） |
| saveConfig / readConfig | (key, value) / (key) | 键值配置桥（dsh_shell SharedPreferences），页面可持久化小配置 |
| saveSettingsYaml | (yaml) → Boolean | 把 settings.yaml 全文写入 DSH_HOME |
| saveSlotConfig | (slot, content) → Boolean | 槽位配置宿主化落盘：DSH_HOME/.dsh/web-slots/<slot>.json（localStorage 代理写路径） |
| readSlotConfig | (slot) → String? | 读槽位文件；缺失返回 null |
| removeSlotConfig | (slot) → Boolean | 删除槽位文件（localStorage.removeItem） |
| clearSlotConfigs | () → Boolean | 清空全部槽位文件（localStorage.clear） |

**版本化**：页面侧 `androidBridge.version >= "1.0"` 才启用桥功能；APK 与 dsh 版本独立演进（UPSTREAM-ITERATION §8）。

> **localStorage 宿主化**：dsh 插件设置保存依赖浏览器 localStorage，在 WebView 中可能静默失败（存储被禁/配额超限），导致「打开配置文件显示无法打开 / 保存不生效」。APK 通过 `shouldInterceptRequest` 在主页 index HTML 注入 `storageShimScript`，把页面 `window.localStorage` 代理到上面四个槽位桥方法（文件落盘，天然免配额、大值自动分块）；桥不可用时代理静默回退原生 localStorage，不破坏页面。

## 3. 权限集（最小）

| 权限 | 用途 | 时机 |
|---|---|---|
| INTERNET | WebView + checkEngine | 声明 |
| POST_NOTIFICATIONS | 通知测试通道 | API 33+ 运行时请求（M1 仅测试按钮触发） |

SAF 目录选择无需权限（用户经系统文件管理器授权 tree URI）；M2 保活/自启走 Foreground Service + Shizuku 时再扩。

## 4. 页面结构

```text
MainActivity
 ├─ onResume: checkEngine() 后台线程探测
 │   ├─ 可达 → WebView 加载 http://127.0.0.1:3080
 │   └─ 不可达 → GuideLayout（提示 + 一键跳 Termux + 重试按钮）
 ├─ WebView 配置: JS 启用 / DOM storage / file chooser（OpenDocumentTree → 目录选择）
 ├─ 返回键: 先 WebView.canGoBack，否则 finish
 └─ AndroidBridge 注入: version/checkEngine/keepScreenOn/showNotification/pickDirectory
```

## 5. 工程骨架

```text
DeepSeek-Harness-Android/        ← 独立 git 仓库（YOYOFeelings/DeepSeek-Harness-Android）
├── settings.gradle.kts / build.gradle.kts / gradle.properties / local.properties
├── gradle/wrapper/             ← Gradle 8.11.1 wrapper
└── app/
    ├── build.gradle.kts        ← AGP 8.9.x, Kotlin 2.0.x, minSdk 26, targetSdk 36, compileSdk 36
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/dshmobile/shell/
        │   ├── MainActivity.kt
        │   ├── AndroidBridge.kt
        │   └── EngineProbe.kt
        └── res/                ← 最小资源（launcher icon 用 adaptive + 矢量）
```

## 6. 验证矩阵（设备）

| # | 步骤 | 预期 |
|---|---|---|
| V1 | assembleDebug + adb install | 安装成功、启动无崩溃 |
| V2 | Termux 服务在跑时打开 | WebView 显示 dsh 移动 UI（com.android.chromium 已验证可渲染） |
| V3 | SAF pickDirectory | 选中目录 → JS 回调收到映射路径 |
| V4 | 通知测试 | 状态栏通知出现 |
| V5 | 停止 Termux 服务后打开 | 引导页显示 + 跳转 Termux 可用 |
| V6 | 返回键 | 先回退页面历史，不误退 |

## 7. 已知限制（M1 范围外）

- 无 Foreground Service 保活（M2 + Shizuku）；
- 无引擎自动更新（M1 引导式：版本探测 → 跳 Termux/复制命令；M2 Shizuku 全自动）；
- 页面内抽屉手势（边缘滑动关闭）未接 back 事件（M1.5 UI 手势项）。
