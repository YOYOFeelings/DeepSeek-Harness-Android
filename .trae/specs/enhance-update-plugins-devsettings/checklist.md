# checklist

- [x] 内置插件从 `assets/plugins` 复制到 `filesDir/plugins`，插件页同时展示内置与已安装插件，内置带徽标；空态仅完全为空时显示。
- [x] `ReleaseInfo` 含 `publishedAt` 与 `body`；`fetchHistoryReleases` 回填 body。
- [x] 「往期版本」区块更显眼（卡片式 header + 计数/箭头），每条可点击展开查看更新日志并带「下载」按钮。
- [x] 设置页最底部有「开发者设置」入口。
- [x] 点进入弹 MD3 密码对话框：`TextInputLayout` 描边浮动标签（圆角方框、hint「密码请输入作者QQ」）+ 协议勾选框；密码 `3197614520` 且勾选协议才放行，失败 Toast。
- [x] 开发者设置页：含「更新直接提示（不检查版本）」开关；开启后 dev 模式旁路 `isNewer`，UpdateScreen 直接视为有新版可下载（测试更新链路）。
- [x] 开关卡为圆角 MD3 卡片，点击带展开动画，展开区说明用途 + 「开启后可能故障，关闭开发者即可恢复」警示。
- [x] 开发者开启时页面有警示横幅/提示。
- [x] 字符串 key 齐全（开发者设置/密码/协议/插件内置/更新日志等）。
- [x] `:app:assembleRelease` 编译通过、无新增警告；签名 SHA-256 `5696…25ff`；提供下载链接。
- [x] 未越界修改引擎后端/`RuntimeUpdater`/`RuntimePermissions`/`EngineProcess`。