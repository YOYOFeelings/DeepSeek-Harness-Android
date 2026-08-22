# Checklist — 引擎运行时骨架落地 + 终端只读

## 范围B · 终端只读
- [x] 终端页移除输入框、输入提示、无「运行」按钮
- [x] 终端页保留权限模式标识、只读输出区、顶部「复制」按钮
- [x] 移除 `DeviceExecutor.run` 执行调用路径（`runCommand`/`setRunning`/`appendPromptLine`），但 `DeviceExecutor`/`ShizukuHelper`/`RootHelper` 文件保留未删
- [x] 「复制」点击写剪贴板并 Toast「已复制」

## 范围A · 引擎运行时骨架
- [x] `EngineRootfs` 实现幂等解压（done 标记）、SHA-256 校验、拒绝 `..` 路径、`engineVersion`/`isExtracted`；rootfs 未就绪返回语义状态不抛主线程
- [x] `EngineProcess` 组装 proot 命令、启动返回可取消句柄、`127.0.0.1:3080` 探活、优雅停止
- [x] `EngineService` 为前台 Service，`START_STICKY`，前台类型分版本，通知走 `dh_primary`，`stopWithTask=false`
- [x] `EngineWatchdog` 每 5s 探活，失败重启，超上限停止并写 `Logs.logEvent`
- [x] `SessionActivity` WebView 加载 3080，开 JS，外链转系统浏览器，返回键可后退
- [x] `ConversationScreen` 显示引擎状态 + 启动/停止/打开会话 + 「检查并更新引擎」；未解压按钮置灰「先安装运行时」；更新按钮显示进度
- [x] `RuntimeUpdater` 完整实现在线更新：manifest 拉取超时回退、下载到 cacheDir 带进度、SHA-256 校验、阶段解压 rootfs-new（xz/tar，拒绝 `..`/链接逃逸）、原子 rename 切换、失败回滚保留旧 rootfs、由看门狗重启
- [x] 更新不中断当前引擎：manifest 不可达/校验失败时提示错误且不影响运行
- [x] `MainActivity` 新增 `ID_CONVERSATION` Tab 与缓存分支，导航可见
- [x] 新增 `ic_conversation.xml`（`dh_primary`）+ `strings.xml` 文案（含 `engine_update_*`）
- [x] `AndroidManifest` 声明 EngineService + FGS 权限（分版本）
- [x] `build.gradle.kts` assets 含 rootfs 目录 + `abiFilters arm64-v8a` + xz/commons-compress 依赖

## 构建与交付
- [x] JDK17 `assembleRelease` 成功
- [x] APK 签名 SHA-256 前缀 `5696…25ff` 通过（apksigner verify）
- [x] APK 复制项目根，`http.server` 提供下载链接
- [ ] 自检：编译后进 App 不闪退；终端只读、会话 Tab 可见且未装 rootfs 时不崩溃；现有主页/关于/设置页正常