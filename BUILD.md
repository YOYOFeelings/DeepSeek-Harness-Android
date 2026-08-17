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

### 方式一：推送触发（自动）
```bash
# 已将构建脚本推送到仓库
# 推送任何提交到 main 分支会自动触发构建
git push origin main
```

### 方式二：手动触发（推荐）
访问仓库页面手动触发：
```
https://github.com/kcln243107/DeepSeek-Harness-Android/actions/workflows/build.yml
```
点击 **"Run workflow"** → 可选填入：
- `snapshot_url`: 快照下载地址（留空则使用仓库内嵌快照）
- `version_override`: 版本号覆盖（如 `0.11.5`）

### 构建产物
- Release tag: `v0.11.4`（自动从 build.gradle.kts 读取）
- APK 附件: `deepseek-harness-0.11.4-release.apk`
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
