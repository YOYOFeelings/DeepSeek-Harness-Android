# CheckList

- [x] `RuntimeUpdater.checkForUpdate` 增加 `mirror` 参数，候选 manifest URL 经 `mirror.resolve(...)` 后请求；默认官方直连行为不变。
- [x] `ConversationScreen.runUpdate` 把用户选定 `mirror` 传入 `checkForUpdate(context, mirror)`。
- [x] 通过镜像可正常拉取 manifest（沙箱实测 DEFAULT/FALLBACK 经 akaere 均 200，arm64 下载 URL 200/75841724B）。
- [x] 编译通过（`assembleRelease`），签名 SHA-256 前缀 `5696…25ff`，给出下载链接。
- [x] 未越界修改：未改 download/apply、EngineProcess/EngineRootfs/EngineService、解压/依赖库链路（scope 锁定）。
- [x] 文档三件套与索引同步（PITFALLS §27 / USER_HABITS 不变 / PROJECT_STRUCTURE 不变 / INDEX）。
- [x] 启动目录创建幂等化：rootfs 已解压（home/tmp 已存在）时不再抛 `cannot create dir`，点击「启动引擎」不再显示路径错误。