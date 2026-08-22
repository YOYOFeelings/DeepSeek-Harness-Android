# 更新页增强 + 插件页修复 + 开发者设置 Spec

## Why
- 「往期版本」当前折叠入口不显眼、每条只给一个下载按钮，无法查看该历史版本对应的更新日志（release body）。
- 「插件」页只读取 `filesDir/plugins/*.json`，App 自带的插件从未展示，用户看不到「已安装」与「内置」边界。
- 缺少一个供作者自测的入口：需要开发者设置（密码保护）+ MD3 密码输入弹窗 + 勾选协议后才进入，内置「更新直接提示（跳过版本检查）」开关供测试更新链路。

## What Changes
- **UpdateScreen / UpdateManager**：让「往期版本」区块更显眼；`ReleaseInfo` 增加 `body` 字段，`fetchHistoryReleases` 写入各 release 的更新日志；每条历史版本可点击展开查看更新日志并下载。
- **PluginStore / PluginsScreen**：实现 APK 内置插件（`assets/plugins/*.json`）首次启动复制到 `filesDir/plugins`，插件页同时展示「内置/已安装」信息。
- **SettingsScreen / MainActivity / 新 DeveloperSettingsScreen**：设置页最底部新增「开发者设置」入口（置灰不显眼风格）；点击弹 MD3 密码输入框（`TextInputLayout` 描边浮动标签、圆角方框；hint「密码请输入作者QQ」），输对 + 勾选协议后才能进入；密码错误弹提示。
- **DeveloperSettingsScreen**：MD3 卡片列表；含「更新直接提示（不检查版本）」开关（dev 模式：跳过必要检查以测试功能）；每张开关卡为圆角卡片，点击带高度展开动画，展开区说明用途与开启后的可能故障。
- **字符串资源**：新增开发者设置、密码、协议、插件内置/已安装、历史版本展开等 key。

## Impact
- Affected specs: update-history（往期版本交互增强）、plugins（内置/已安装展示）、settings（新增开发者设置子页）。
- Affected code:
  - `app/src/main/java/com/yoyo/dshmobile/shell/ui/screen/UpdateManager.kt`
  - `app/src/main/java/com/yoyo/dshmobile/shell/ui/screen/UpdateScreen.kt`
  - `app/src/main/java/com/yoyo/dshmobile/shell/ui/PluginStore.kt`
  - `app/src/main/java/com/yoyo/dshmobile/shell/ui/screen/PluginsScreen.kt`
  - `app/src/main/java/com/yoyo/dshmobile/shell/ui/screen/SettingsScreen.kt`
  - `app/src/main/java/com/yoyo/dshmobile/shell/MainActivity.kt`
  - `app/src/main/java/com/yoyo/dshmobile/shell/ui/Ui.kt`（如需新增通用浮层控件）
  - `app/src/main/res/values/strings.xml`
  - 新增 `app/src/main/java/com/yoyo/dshmobile/shell/ui/screen/DeveloperSettingsScreen.kt`
  - 新增内置插件资源 `app/src/main/assets/plugins/*.json`
- 不越界：不改引擎后端/`RuntimeUpdater`/`RuntimePermissions`/`EngineProcess`。

## ADDED Requirements

### Requirement: 内置插件复制与展示
App SHALL 将 `assets/plugins/*.json` 在首次启动/首次进入插件页时复制到 `filesDir/plugins`，任一侧存在即写入（合并），内置插件打「内置」标记，插件页同时展示已安装与内置插件；空态仅当完全为空时显示。

#### Scenario: 内置插件可见
- **WHEN** 首次安装后进入插件页，且 `assets/plugins` 有内置插件
- **THEN** 插件页列出内置插件（带「内置」标记），不会因为 `filesDir/plugins` 初始为空而只显示「暂无插件」

### Requirement: 往期版本条目可查看更新日志
`ReleaseInfo` SHALL 增加 `body` 字段；`fetchHistoryReleases` SHALL 逐条回填各 release 的 `body`。每个历史条目 SHALL 可点击展开显示更新日志，并提供「下载」按钮。

#### Scenario: 查看旧版更新日志
- **WHEN** 用户展开某一历史版本条目
- **THEN** 显示该 release 的更新日志正文，并保持「下载」按钮可用

### Requirement: 开发者设置入口与密码门（MD3）
设置页最底部新增「开发者设置」入口；点击后弹出 MD3 对话框：MD3 描边输入框（`TextInputLayout.OutlinedBox`，圆角方框 + 浮动标签，hint「密码请输入作者QQ」）、协议勾选框、确认/取消按钮；密码需等于 `3197614520` 且勾选协议后才能进入，否则 Toast 提示。

#### Scenario: 密码正确且勾选协议
- **WHEN** 输入 `3197614520`、勾选协议并点确定
- **THEN** Toast 提示进入成功，跳转开发者设置页

#### Scenario: 密码错误
- **WHEN** 输入错误密码
- **THEN** 弹提示（开发者设置不开启）；若未勾选协议同样拦截

### Requirement: 开发者设置页内容
开发者设置页 SHALL 包含「更新直接提示（不检查版本）」开关：开启后 dev 模式生效，更新链路跳过版本比较（`UpdateManager.isNewer` 被旁路），任何情况都视为有新版可直接下载测试。每张设置项为圆角卡片，点击带高度缩放/位移动画展开，展开区描述用途与「开启后可能的功能故障，解决方式为关闭开发者」。

#### Scenario: 开启更新直接提示
- **WHEN** 开发者开启「更新直接提示」并进入/刷新「版本更新」页
- **THEN** 不再比较当前与远程版本，直接显示「有新版」+ 可用的下载按钮

## REMOVED Requirements
_无删除项。_