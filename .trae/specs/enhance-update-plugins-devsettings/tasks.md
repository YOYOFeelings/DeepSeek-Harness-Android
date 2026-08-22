# Tasks

- [x] Task 1: 内置插件 assets 复制与插件页展示（PluginStore.kt / PluginsScreen.kt）
  - [x] `assets/plugins/*.json` 打包进 APK；`loadPlugins(context)` 改为：assets 存在且未复制过则先复制到 `filesDir/plugins`，再扫描 `filesDir/plugins/*.json`；合并后读取（内置/已安装同一份 JSON，`enabled` 默认 true，`sourceDir` 记录）。
  - [x] 内置插件来源标记：写入 JSON 的 `source` 或按文件来源判定，插件卡片显示「内置/已安装」徽标。
  - [x] 插件页列表同时展示已安装与内置；空态仅当完全为空时显示。
  - [x] `pluginCount` 语义保持（合并后计数）。
- [x] Task 2: 往期版本可见性与更新日志（UpdateManager.kt / UpdateScreen.kt）
  - [x] `ReleaseInfo` 增加 `publishedAt` 已有 + `body: String = ""`。
  - [x] `fetchHistoryReleases` 逐条回填 `obj.optString("body")`。
  - [x] 往期版本折叠入口更显眼（整块卡片式 header + 明显箭头 + 计数）；每条可点击展开更新日志（正文）+ 保留下载按钮。
  - [x] 展开/下载交互复用主进度区，标注当前版本号。
- [x] Task 3: 开发者设置入口 + MD3 密码弹窗（SettingsScreen.kt / Ui.kt）
  - [x] 设置页最底部加「开发者设置」入口行（低调置灰样式）。
  - [x] 点击弹 MD3 对话框：`TextInputLayout`(OutlinedBox，圆角方框、浮动标签 hint「密码请输入作者QQ」) + 协议 `CheckBox` + 取消/确认；确认需密码 `3197614520` 且勾选协议，通过后 Toast「进入成功」并回调进入开发者页，失败 Toast 提示。
  - [x] `Ui.kt` 新增通用 `Context.outlinedEdit(context, hint)` 或 DeveloperSettings 内私有构造，保证 MD3 风格（material 组件可用）。
- [x] Task 4: 开发者设置页（新增 DeveloperSettingsScreen.kt + MainActivity.kt 承接 + SettingsScreen 回调）
  - [x] 新增 `DeveloperSettingsScreen(context, onBack)`：标题栏 + 返回。
  - [x] 「更新直接提示（不检查版本）」开关卡：dev 预存 `engine_prefs/dev_force_update`；开启时 UpdateScreen 旁路 `isNewer` 直接视为有新版（=测试模式，跳过必要检查）。
  - [x] 开关卡为圆角 MD3 卡片，点击整卡展开（ValueAnimator 高度动画），展开区：用途说明 + 「开启后可能功能故障，解决方式为关闭开发者」警示文案 + 关闭开关入口。
  - [x] 页顶（或悬浮）展示开发者模式已开启的警示横幅。
  - [x] MainActivity 新增 ID_DEVELOPER 页，SettingsScreen 回调 `onOpenDeveloper`。
- [x] Task 5: 字符串资源（strings.xml）
  - [x] 新增：`settings_developer`、`settings_developer_desc`、`dev_password_hint`(密码请输入作者QQ)、`dev_agreement`(协议勾选文案)、`dev_password_wrong`、`dev_password_ok`、`dev_warning_banner`、`dev_update_force`、`dev_update_force_desc`、`dev_danger_hint`、`plugin_bundled`、`plugin_installed`、`update_history_log_title` 等。
- [x] Task 6: 编译验证 + 签名 + 下载链接
  - [x] `./gradlew :app:assembleRelease`（JDK 17）编译通过、无新增警告。
  - [x] 签名 SHA-256 前缀 `5696…25ff`（同一 APK 固定签名）。
  - [x] 给出 APK 下载链接（http://localhost:8899/…）。

# Task Dependencies
- Task 3 依赖 Ui.kt 的 MD3 输入控件（可先行在 Task 3 内完成，无需独立）。
- Task 4 依赖 Task 3 的入口回调；dev_force_update 需 Task 5 字符串 + UpdateScreen 旁路逻辑配合。
- Task 2 依赖 Task 1 之外无耦合，可并行。
- Task 1 / Task 2 / Task 3 相互独立，可并行；Task 5 贯穿 1–4 引用的 key，尽量先落 strings。