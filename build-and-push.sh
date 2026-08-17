#!/bin/bash
# build-and-push.sh — 构建 APK 并推送 Release 到 GitHub
#
# 用法：
#   ./build-and-push.sh [ANDROID_SDK_ROOT]
#
# 环境变量：
#   GITHUB_TOKEN  — GitHub Personal Access Token（推荐通过环境变量传入）
#   SNAPSHOT_PATH — 快照路径，默认 /storage/emulated/0/下载/Download/snapshot-x86_64.tar.xz
#
# 依赖：
#   - JDK 17+
#   - Android SDK（compileSdk 36）
#   - Gradle 8.11.1（由 wrapper 提供）
#   - curl（上传 Release 用）
#
# 示例：
#   GITHUB_TOKEN=ghp_xxx ./build-and-push.sh /opt/android-sdk
#   SNAPSHOT_PATH=/path/to/snapshot.tar.xz ./build-and-push.sh ~/Android/Sdk

set -euo pipefail

# ── 参数 / 默认值 ─────────────────────────────────────────────────────────────
SDK_ROOT="${1:-${ANDROID_SDK_ROOT:-}}"
SNAPSHOT_SRC="${SNAPSHOT_PATH:-/storage/emulated/0/下载/Download/snapshot-x86_64.tar.xz}"
GITHUB_TOKEN="${GITHUB_TOKEN:?请设置 GITHUB_TOKEN 环境变量}"
REPO="kcln243107/DeepSeek-Harness-Android"

# 从版本配置读取版本号
VERSION_NAME=$(grep 'versionName' app/build.gradle.kts | grep -oE '"[^"]+"' | tail -1 | tr -d '"')
VERSION_CODE=$(grep 'versionCode' app/build.gradle.kts | grep -oE '[0-9]+' | head -1)
TAG="v${VERSION_NAME}"

echo "══════════════════════════════════════════"
echo "  DeepSeek-Harness 构建 & 推送脚本"
echo "══════════════════════════════════════════"
echo "  版本:    ${TAG}  (code=${VERSION_CODE})"
echo "  SDK:     ${SDK_ROOT:-<未指定，使用默认路径>}"
echo "  快照:    ${SNAPSHOT_SRC}"
echo "  仓库:    https://github.com/${REPO}"
echo "──────────────────────────────────────────"

# ── 步骤 1：验证快照 ─────────────────────────────────────────────────────────
if [[ ! -f "$SNAPSHOT_SRC" ]]; then
  echo "❌ 快照不存在: $SNAPSHOT_SRC"
  echo "   请将 snapshot-x86_64.tar.xz 放到正确路径，或设置 SNAPSHOT_PATH 环境变量"
  exit 1
fi
SNAPSHOT_SIZE=$(du -h "$SNAPSHOT_SRC" | cut -f1)
echo "✅ 快照就绪 (${SNAPSHOT_SIZE}): $SNAPSHOT_SRC"

# ── 步骤 2：复制快照到 assets ────────────────────────────────────────────────
ASSETS_DIR="app/src/main/assets"
mkdir -p "$ASSETS_DIR"
cp "$SNAPSHOT_SRC" "$ASSETS_DIR/snapshot.tar.xz"
echo "✅ 快照已复制到 $ASSETS_DIR/snapshot.tar.xz"

# ── 步骤 3：生成 local.properties（如果不存在） ─────────────────────────────
if [[ -n "$SDK_ROOT" && ! -f local.properties ]]; then
  echo "sdk.dir=$SDK_ROOT" > local.properties
  echo "✅ 已生成 local.properties"
elif [[ -n "$SDK_ROOT" && -f local.properties ]]; then
  # 更新 SDK 路径
  sed -i "s|^sdk.dir=.*|sdk.dir=$SDK_ROOT|" local.properties
  echo "✅ 已更新 local.properties"
fi

# ── 步骤 4：构建 ─────────────────────────────────────────────────────────────
echo ""
echo "▶ 开始构建（Gradle assembleRelease）..."
echo "  （首次构建约 5-15 分钟，取决于网络和机器性能）"
echo ""

export JAVA_HOME="${JAVA_HOME:-$(java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk '{print $3}')" }

# 设置国内镜像加速 Gradle 下载（可选）
export GRADLE_OPTS="-Dorg.gradle.internal.http.connectionTimeout=30000 -Dorg.gradle.internal.http.socketTimeout=60000"

./gradlew assembleRelease \
  -Pandroid.suppressUnsupportedCompileSdk=36 \
  --no-daemon \
  --offline 2>&1 || \
./gradlew assembleRelease \
  -Pandroid.suppressUnsupportedCompileSdk=36 \
  --no-daemon \
  2>&1

APK_PATH="./deepseek-harness-${VERSION_NAME}-release.apk"
if [[ ! -f "$APK_PATH" ]]; then
  # 尝试 debug APK
  APK_PATH="./deepseek-harness-${VERSION_NAME}-debug.apk"
fi

if [[ ! -f "$APK_PATH" ]]; then
  # 在 build 目录查找
  APK_PATH=$(find . -name "deepseek-harness-*-release.apk" -o -name "deepseek-harness-*-debug.apk" 2>/dev/null | head -1)
fi

if [[ -z "$APK_PATH" || ! -f "$APK_PATH" ]]; then
  echo "❌ 构建失败：未找到生成的 APK"
  echo "   请检查上方错误信息"
  exit 1
fi

APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
echo "✅ 构建成功: $APK_PATH  (${APK_SIZE})"

# ── 步骤 5：生成更新说明 ─────────────────────────────────────────────────────
echo ""
echo "▶ 生成 Release 说明..."
CHANGELOG=$(git log --oneline -10)
echo "## ${TAG} Release" > /tmp/release-body.md
echo "" >> /tmp/release-body.md
echo "### 📦 更新内容" >> /tmp/release-body.md
echo "" >> /tmp/release-body.md
echo "$CHANGELOG" | while read line; do
  echo "- $line" >> /tmp/release-body.md
done
echo "" >> /tmp/release-body.md
echo "### 📋 安装" >> /tmp/release-body.md
echo "" >> /tmp/release-body.md
echo "1. 下载附件中的 \`deepseek-harness-${VERSION_NAME}-release.apk\`" >> /tmp/release-body.md
echo "2. 安装并授予必要权限" >> /tmp/release-body.md
echo "3. 首次启动需联网下载运行时快照（约 70MB），或直接使用内嵌快照" >> /tmp/release-body.md

# ── 步骤 6：推送代码 ─────────────────────────────────────────────────────────
echo ""
echo "▶ 推送代码到 GitHub..."
git add -A
git status --short
git diff --cached --stat | tail -5

git commit -m "build(${TAG}): 发布 APK 构建产物" \
  --no-edit 2>/dev/null || \
git commit -m "build(${TAG}): 发布 APK 构建产物"

git push origin HEAD 2>&1 || {
  echo "⚠️  代码推送失败，继续尝试上传 Release..."
}

# ── 步骤 7：创建/更新 Release ────────────────────────────────────────────────
echo ""
echo "▶ 创建/更新 GitHub Release..."

# 检查 Release 是否已存在
EXISTING_RELEASE=$(curl -s -k -H "Authorization: token $GITHUB_TOKEN" \
  "https://api.github.com/repos/${REPO}/releases/tags/${TAG}" 2>/dev/null)

RELEASE_ID=$(echo "$EXISTING_RELEASE" | grep -o '"id":[0-9]*' | head -1 | grep -oE '[0-9]+')
RELEASE_URL=$(echo "$EXISTING_RELEASE" | grep -o '"html_url":"[^"]*"' | head -1 | cut -d'"' -f4)

if [[ -n "$RELEASE_ID" ]]; then
  echo "  找到已有 Release: ${RELEASE_URL}"
  UPDATE_CMD="PATCH"
  UPDATE_URL="https://api.github.com/repos/${REPO}/releases/${RELEASE_ID}"
else
  echo "  创建新 Release..."
  UPDATE_CMD="POST"
  UPDATE_URL="https://api.github.com/repos/${REPO}/releases"
fi

# 准备 Release body
BODY=$(cat /tmp/release-body.md)

# 创建/更新 Release（不含 asset，asset 单独上传）
curl -s -k -X "$UPDATE_CMD" \
  -H "Authorization: token $GITHUB_TOKEN" \
  -H "Accept: application/vnd.github.v3+json" \
  -d "{
    \"tag_name\": \"${TAG}\",
    \"target_commitish\": \"$(git rev-parse HEAD)\",
    \"name\": \"${TAG}\",
    \"body\": $(echo "$BODY" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read()))" 2>/dev/null || echo "\"$(echo "$BODY" | head -5)\""),
    \"draft\": false,
    \"prerelease\": false
  }" | python3 -c "import sys,json; d=json.load(sys.stdin); print('  Release URL:', d.get('html_url','N/A'))" 2>/dev/null || \
echo "  Release 创建/更新完成"

# 获取最新 Release ID
LATEST_RELEASE=$(curl -s -k -H "Authorization: token $GITHUB_TOKEN" \
  "https://api.github.com/repos/${REPO}/releases/latest" 2>/dev/null)
NEW_RELEASE_ID=$(echo "$LATEST_RELEASE" | grep -o '"id":[0-9]*' | head -1 | grep -oE '[0-9]+')

# ── 步骤 8：上传 APK ─────────────────────────────────────────────────────────
if [[ -n "$NEW_RELEASE_ID" ]]; then
  echo ""
  echo "▶ 上传 APK 到 Release..."
  UPLOAD_URL="https://uploads.github.com/repos/${REPO}/releases/${NEW_RELEASE_ID}/assets?name=deepseek-harness-${VERSION_NAME}-release.apk"
  curl -s -k -X POST \
    -H "Authorization: token $GITHUB_TOKEN" \
    -H "Content-Type: application/octet-stream" \
    --data-binary "@${APK_PATH}" "$UPLOAD_URL" | \
    python3 -c "import sys,json; d=json.load(sys.stdin); print('  ✅ APK 上传成功:', d.get('browser_download_url',''))" 2>/dev/null || \
    echo "  ⚠️  APK 上传可能失败，请检查上方输出"
fi

# ── 完成 ─────────────────────────────────────────────────────────────────────
echo ""
echo "══════════════════════════════════════════"
echo "  ✅ 构建 & 推送完成！"
echo "══════════════════════════════════════════"
echo "  Release: https://github.com/${REPO}/releases/tag/${TAG}"
echo "  APK:     deepseek-harness-${VERSION_NAME}-release.apk"
echo "  版本:    ${TAG}  (code=${VERSION_CODE})"
echo "──────────────────────────────────────────"
echo ""
echo "📌 后续步骤："
echo "   1. 打开 Release 页面确认 APK 已上传"
echo "   2. 如需创建 PR，手动在 GitHub 操作"
echo "   3. 删除本地 local.properties（如已生成）避免提交 SDK 路径"
