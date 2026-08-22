# Checklist

- [x] `DownloadSource.kt` 集中镜像表/`resolve`/带进度 `download`/读写镜像 id；App 与引擎均调用它（不再各自内联下载）。
- [x] `UpdateManager` App APK 下载走统一源 + 所选镜像；镜像 id 持久化生效。
- [x] `RuntimeUpdater` 引擎 rootfs 下载与 manifest 走统一源；`ConversationScreen` 选源改走统一源，交互不变。
- [x] 主页检测到新版本：横幅仍显示，并自动弹 `MaterialAlertDialogBuilder` MD3 弹窗；「确定」进入更新页、「取消」关闭；同一次进入只弹一次。
- [x] 新增 `res/layout/dialog_rounded_input.xml`（OutlinedBox，圆角 35/35/35/50dp、singleLine、hint 粗体）；输入弹窗 inflate 它并符合用户示例外观。
- [x] 引擎环境/工具缺失（node/bin.js/termux-exec/关键 so）时弹 MD3 弹窗列缺失项 + Toast，提供「去更新引擎」引导下载补齐。
- [x] 新增文案 key 齐全（主页弹窗、环境缺失、确认/取消等）。
- [x] `:app:assembleRelease`（JDK 17）编译通过、无新增警告；签名 SHA-256 前缀 `5696…25ff`；提供下载链接。
- [x] 未越界修改引擎后端核心逻辑/`native`/`RuntimeUpdater` 下载以外的行为；App 更新页、引擎进度既有交互保持兼容。