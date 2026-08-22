# CheckList

- [x] `UpdateManager.ReleaseInfo` 含 `publishedAt`（默认空，旧调用不受影响）。
- [x] `fetchHistoryReleases()` 返回历史版本列表（含 tag/apk/published_at，过滤最新版，失败空）。
- [x] `ConversationScreen` 已删除 `showStartDialog/StartUi/showStartNotReady`，`doStart()` 无旧引用。
- [x] `showStartDialogSimple()`：纯转圈、不可取消、无按钮。
- [x] 启动失败/超时弹 `showStartErrorDialog`（错误信息+最近30行日志+重试/关闭）；rootfs 未就绪弹 `showUpdateRequiredDialog`（去更新）。
- [x] `UpdateScreen` 版本卡片有「刷新」，点后同步刷新最新版与历史列表。
- [x] 进度条高度≥8dp 且圆角；下载中显示「下载中…」+ 已下载/总大小；完成后按钮「安装/已下载」。
- [x] 「往期版本」可折叠列表，条目含版本号/日期/下载，点击下载并安装；空/失败显示「暂无历史版本」。
- [x] `strings.xml` 已新增全部 10 个 key。
- [x] `:app:assembleRelease` 编译通过、无新增警告；签名 `5696…25ff`；给出下载链接。
- [x] 未越界修改其它模块（引擎后端、RuntimeUpdater、rootfs 更新核心链路）。