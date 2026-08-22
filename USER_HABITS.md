# USER_HABITS 用户习惯记录（dsh-mobile-apk-yoyo）

> 每次执行任务前必须先读取本文件，遵循用户的既有习惯与偏好。
> 本文件引用旧项目 `/workspace/dsh-mobile-apk` 的既有习惯，并针对 yoyo 重构版补充说明。

## 1. 签名一致性（APK 打包）
- 每次打包签名必须与上一个**一模一样**（同 APK 必须同一个签名）。
- **不同的 APK 有不同的签名，但同一个 APK 必须是同一个签名**。
- 签名文件：`keystore/release.jks`（沿用旧项目 `/workspace/dsh-mobile-apk/keystore/`）。
- 签名验证命令：`apksigner verify --print-certs <apk>`，核对 SHA-256（历史签名 SHA-256 前缀：`5696…25ff`）。

## 2. 更新后审查（强制）
每次做完更新后，必须进行一系列审查：
- 功能性错误、遗忘的值/参数、无法正常运行的后遗症；
- 执行构建，查看终端输出；
- 界面审查：是否超出屏幕、按钮是否被裁剪、横屏是否正常、有无灰色空白。

## 3. 版本号与发布门禁（双渠道）
- 修复类/未验证构建一律**先发测试版** `vX.Y.Z-betaN`（GitHub Release 标记 **Prerelease**，**绝不进入 latest**）。
- 只有用户明确「**可以正式上传**」才发布正式版 `vX.Y.Z`（latest）。
- **versionCode 单调递增，不可回退**（beta 与正式版共用同一递增序列）。
- 测试版 release 同样需附带 APK + MANIFEST.txt + snapshot-{abi}.tar.xz，保持 App 内更新链可用。

## 4. 密钥记录
- GitHub PAT 指向旧项目：**见 `/workspace/dsh-mobile-apk/USER_HABITS.md §5`**（本文件不重复粘贴密钥明文，避免泄漏与多份副本不同步）。
- push 方式：`https://x-access-token:<PAT>@github.com/...`。

## 5. UI 偏好
- 纯原生 View 实现（LinearLayout/ScrollView/TextView），无第三方 UI 依赖。
- **白色为主题、简洁、不简陋**；Flat Minimalist 浅色扁平：bg `#F4F5F7` / surface `#FFFFFF` / accent `#2D5F9E`。
- 颜色/样式全部从 `colors.xml`/`themes.xml`/`styles.xml` 读取，禁止页面硬编码（防蓝白割裂）。
- **App 名称 = deepseek HARNESS，版本号 = 1.0**。
- 日志导出习惯：关于页「保存日志」= 经 SAF 让用户选择目录/文件名落盘 zip；「发送日志」= 系统分享选择接收 App。
- **引擎自动启动**：设置页提供「自动启动引擎」开关，**默认关闭**（用户开启后 App 启动且 rootfs 就绪才自动拉起引擎）；状态持久化于 `engine_prefs/auto_start`。
- **引擎运行状态判定**：统一用 `EngineProcess.probe(127.0.0.1:3080)` 端口探活（主页/会话页/看门狗一致），Shizuku 授权不作为引擎运行依据；主页引擎卡初始「检测中…」异步回填。
- **操作按钮布局**：页面操作按钮默认**一排两个**（等宽 + 间距），不整排竖排全宽。
- **更新覆盖确认**：已有引擎数据/待安装包时点「检查更新」必须**先弹 MD3 覆盖确认**（「更新将覆盖现有引擎数据，确定继续吗？」），确定才进镜像选择；不要静默覆盖。可强制更新（空版本判定已修复，不再误报「已是最新」）。
- **引擎启动要有过程反馈**：点「启动引擎」必须弹 **MD3 状态弹窗**（状态行 + 实时日志），失败要显示原因与日志、提供「重试/去更新」；不要只转圈或闪退。
- **主页存储占用口径**：主页「占用/存储」统计**含 files/rootfs 引擎数据**（用户要求统计上），测量走 IO 协程异步回填不阻塞首帧；插件数量随周期动态刷新。

## 6. 项目文档（三件套）
- 每个项目目录下创建：`PROJECT_STRUCTURE.md`（纯项目目录）、`USER_HABITS.md`（本文件，用户习惯）、`PITFALLS.md`（踩过的坑）。
- 每次执行任务前必须读取这些文件，了解项目目录和结构。

## 7. 其他
- 回复语言：**中文**。
- 临时文件放 `/tmp` 分类目录，不留项目内。
- 新文件/资源需登记进 `/workspace/INDEX.md`。
- **引擎运行时（rootfs）更新源偏好**：沿用旧项目 `dsh-mobile-apk`，GitHub Release `MANIFEST.txt`（行格式），主源 `YOYOFeelings/DeepSeek-Harness-Android`（仅 x86_64）+ 回退源 `kelai141/dsh-mobile-apk`（双 ABI），按设备 ABI 自动匹配（见 PITFALLS §19）。

## 8. 代理/镜像（本次约定）
- **代理只有开发者（yoyo）能添加**：镜像表仅代码内置（`EngineMirrors.BUILTIN_MIRRORS` 25 项），**无用户自定义源入口**，终端用户只能「选择」不能「添加」。
- 更新流程：先弹镜像选择弹窗（并发测延迟，逐行显示），**点某行立即用该镜像更新**（不必等全部测完），上次选择被记忆置顶。
- 更新过程必须弹 **MD3 醒目进度弹窗**（下载/校验/解压各阶段有进度条 + 阶段文案），失败要明确提示可重试，不能静默。
- 新增镜像 = 改 `EngineMirrors.BUILTIN_MIRRORS` 源码后重新发版（发版规范见 §3）。
