# DeepSeek-Harness-Android 构建指南

## 环境诊断结果

| 组件 | 状态 | 说明 |
|------|------|------|
| JDK 17+ | ❌ 缺失 | 构建必需 |
| Android SDK | ❌ 缺失 | compileSdk 36 |
| Gradle | ❌ 缺失 | 可用 wrapper |
| git | ✅ 就绪 | HTTPS 已修复 |
| curl | ✅ 就绪 | 网络请求 |
| 内存 | ✅ 2.6GB | 足够 |

## 推荐方案：GitHub Actions（无需本地环境）

### 自动模式（推荐）

每次 push 到 `main` 分支，workflow 自动触发构建，版本号自动递增：

```bash
git push origin main
```

版本号计算规则：
- `BASE_VERSION` = `build.gradle.kts` 中的当前 `versionName`（保持不变）
- `BASE_CODE` = `build.gradle.kts` 中的当前 `versionCode`
- `N` = 最近同类 tag（如 `v0.11.4-fix`）以来的 commit 数
- **`versionCode = BASE_CODE + N`**（每次构建后自动写回并推送）
- **`versionName`** 保持不变，仅在明确升级时手动通过 `version_override` 覆盖

### 手动触发（workflow_dispatch）

访问仓库页面手动触发：
```
https://github.com/kcln243107/DeepSeek-Harness-Android/actions/workflows/build.yml
```

点击 **"Run workflow"** → 可选填入：
- `snapshot_url`: 快照下载地址（留空则从 v1.0 release 按架构自动下载）
- `version_override`: 强制指定版本（如 `1.0`，留空则自动递增）
- `version_code_override`: 强制指定 versionCode（如 `50`，仅配合 version_override 使用）
- `apk_arch`: 构建架构（`all` / `arm64` / `x86_64`，默认 `all`）

### 构建产物

| 触发方式 | 构建结果 |
|---------|---------|
| push 到 main | 双架构：arm64 + x86_64 |
| workflow_dispatch (apk_arch=all) | 双架构：arm64 + x86_64 |
| workflow_dispatch (apk_arch=arm64) | 单架构：arm64 |
| workflow_dispatch (apk_arch=x86_64) | 单架构：x86_64 |

- Release tag: `v{versionName}`（如 `v1.0`）
- APK 附件: `deepseek-harness-{version}-arm64-release.apk`
- APK 附件: `deepseek-harness-{version}-x86_64-release.apk`
- 工作流日志: Actions 标签页

## 本地构建（需要额外安装）

### 方案 A：Termux（推荐用于 Android）
```bash
# 1. 安装依赖
pkg install openjdk-17 gradle android-sdk

# 2. 接受 SDK 许可证
sdkmanager --licenses

# 3. 构建
SNAPSHOT_PATH=/storage/emulated/0/下载/Download/snapshot-x86_64.tar.xz \
ANDROID_SDK_ROOT=$PREFIX/share/android-sdk \
./quick-build.sh
```

### 方案 B：Ubuntu/WSL
```bash
# 1. 安装依赖
sudo apt install openjdk-17-jdk gradle android-sdk-build-tools

# 2. 设置 SDK 路径
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_SDK_ROOT=$HOME/Android/Sdk

# 3. 构建并推送
GITHUB_TOKEN=ghp_xxx ./build-and-push.sh $ANDROID_SDK_ROOT
```

## 快照说明

| 架构 | 快照文件 | 适用设备 |
|------|----------|----------|
| x86_64 | snapshot-x86_64.tar.xz | 模拟器、x86 设备 |
| arm64 | snapshot-arm64.tar.xz | ARM64 手机（主流） |

当前仓库内嵌 x86_64 快照，ARM64 设备首次启动需联网下载匹配架构快照。

## 常见问题

### Q: GitHub API 403 错误
A: 速率限制，等待 1 小时或添加 GITHUB_TOKEN 环境变量认证。

### Q: 构建超时
A: 首次构建需下载依赖，约 10-20 分钟，属正常。

### Q: 签名不一致无法覆盖安装
A: 确保 debug/release 使用同一 keystore（已配置在 keystore.properties）。

### Q: 版本号不递增
A: 检查 workflow 是否成功 push 回 `build.gradle.kts`；确认仓库 settings 中 Actions 有写入权限。

### Q: 如何指定特定版本（如 0.12.0）
A: 通过 workflow_dispatch 传入 `version_override=0.12.0`，workflow 将跳过自动计算，直接使用指定版本。
