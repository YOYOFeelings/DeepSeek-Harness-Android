# DeepSeek Harness Android — 公告

> 本文件为应用「主页」公告展示的独立配置文件（NOTICE）。
> 更新内容请查看 ANNOUNCEMENT.md，二者相互独立。

## 📢 项目介绍

**中文**

DeepSeek Harness Android 是一个运行在 Android 上的 DeepSeek 工具集（RAG 智能体 + Web 面板），
基于 WebView + 本地 Node 运行时，支持快照解压、一键启动/停止引擎、镜像更新、APK 自更新、插件管理等能力。

- 开源地址：https://github.com/kcln243107/DeepSeek-Harness-Android
- 采用 Flat Minimalist 扁平设计，纯原生 View 实现，轻量流畅。
- 支持中英文切换。

**English**

DeepSeek Harness Android is a DeepSeek toolkit (RAG agent + Web panel) running on Android,
built on WebView + local Node runtime. It supports snapshot extraction, one-tap engine start/stop,
mirror updates, APK self-update, and plugin management.

- Open source: https://github.com/kcln243107/DeepSeek-Harness-Android
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

## 🆕 新闻 / 动态

- 2026-08（v0.11.7）：修复公告弹窗/更新检查（URL 硬编码指向 YOYOFeelings 仓库）、Web 端插件配置无法保存（localStorage shim 注入失效）、引擎启动并发竞态（profiles 目录无同步锁）三项问题。workflow 新增自动版本递增，每次推送自动计算并写回 build.gradle.kts。
- 2026-08（v0.11.6）：修复代码审查发现的 4 项 Bug——通知栏残留、主线程阻塞（.commit→.apply）、日志无限增长、Activity 销毁后后台线程泄漏。
- 2026-08（v0.11.5）：修复 MainActivity 编译错误（缺少 kotlinx.coroutines 导入、可空类型处理）。
- 2026-08（v0.11.4）：修复更新源误报不可达（manifest 404 回退）、localStorage 宿主化代理全页面注入、终端日志截断。
- 2026-08（v0.11.1）：小版本修复更新——弹窗统一美化并可正常关闭，发布更新包版本策略明确。
- 2026-08（v0.11.0）：新增「主页」公告查询页，公告配置独立于更新说明（NOTICE.md）。
- 2026-08（v0.11.0）：终端页改为纯终端列表，横屏下滑不再被裁剪。
