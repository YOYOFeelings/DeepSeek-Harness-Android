# DeepSeek Harness Android — 公告

> 本文件为应用「主页」公告展示的独立配置文件（NOTICE）。
> 更新内容请查看 ANNOUNCEMENT.md，二者相互独立。

## 📢 项目介绍

**中文**

DeepSeek Harness Android 是一个运行在 Android 上的 DeepSeek 工具集（RAG 智能体 + Web 面板），
基于 WebView + 本地 Node 运行时，支持快照解压、一键启动/停止引擎、镜像更新、APK 自更新、插件管理等能力。

- 开源地址：https://github.com/YOYOFeelings/DeepSeek-Harness-Android
- 采用 Flat Minimalist 扁平设计，纯原生 View 实现，轻量流畅。
- 支持中英文切换。

**English**

DeepSeek Harness Android is a DeepSeek toolkit (RAG agent + Web panel) running on Android,
built on WebView + local Node runtime. It supports snapshot extraction, one-tap engine start/stop,
mirror updates, APK self-update, and plugin management.

- Open source: https://github.com/YOYOFeelings/DeepSeek-Harness-Android
- Flat Minimalist design, pure native Views, lightweight and smooth.
- Supports Chinese / English switching.

## 💬 官方交流群

- QQ 群 1：**200317338**
- QQ 群 2：**932593560**

欢迎加入反馈问题、交流使用心得与获取最新动态。

## 🧭 使用提示

- 首次使用请先进入「设置 → 更新」检查并应用最新运行时快照与更新源。
- 引擎启动后，点击「进入 Web」即可打开 Web 面板。
- 所有日志统一在「终端」页查看；遇到问题可导出调试日志反馈。

> ⚠️ 目前引擎无法使用，请使用之前的老版本，后续修复后会在公告上提示

## 🆕 新闻 / 动态

- 2026-08（v0.11.7）：**稳定性修复版**——修复不同设备下更新过快闪退 / 引擎无法启动：更新交互重构（新版本弹窗→点更新关闭→测速选源弹窗→并发测速→自动选最快、可手动改选），弹窗限高 60% 自适应且按钮始终可见，进度回调节流合并，关键等待循环 180s 超时防卡死。
- 2026-08（v0.11.6）：**体验修复版**——修复终端滚动异常与换行、测速弹窗可滑动、更新源下载不再反复换源、所有源并发测速、下载超时 120s、README 完善。
- 2026-08（v0.11.5）：**关键修复版**——arm64 设备（主流手机）引擎无法启动问题已修复：备用清单切换到上游双 ABI 发布，arm64 设备自动下载匹配架构快照并启动引擎；同时修复更新源误报「不可达」。
- 2026-08（v0.11.1）：小版本修复更新——弹窗统一美化并可正常关闭，发布更新包版本策略明确。
- 2026-08（v0.11.0）：新增「主页」公告查询页，公告配置独立于更新说明（NOTICE.md）。
- 2026-08（v0.11.0）：终端页改为纯终端列表，横屏下滑不再被裁剪。
