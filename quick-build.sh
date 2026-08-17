#!/bin/bash
# quick-build.sh — 快速构建脚本（适用于 Termux / Android 环境）
#
# 此脚本假设：
#   1. 已有 JDK 17+（可通过 apt install openjdk-17-jdk 安装）
#   2. 已有 Android SDK（sdkmanager + platform-tools）
#   3. gradlew 可执行
#
# 用法：
#   ./quick-build.sh
#   SNAPSHOT_PATH=/path/to/snapshot.tar.xz ./quick-build.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "══════════════════════════════════════════"
echo "  DeepSeek-Harness 快速构建脚本"
echo "══════════════════════════════════════════"

# ── 检测 Java ────────────────────────────────────────────────────────────────
if ! command -v java &>/dev/null; then
  echo "❌ 未找到 Java，请先安装 JDK 17+"
  echo "   Termux: pkg install openjdk-17"
  echo "   Ubuntu: apt install openjdk-17-jdk"
  exit 1
fi
JAVA_VERSION=$(java -version 2>&1 | head -1)
echo "✅ Java: $JAVA_VERSION"

# ── 检测 Gradle Wrapper ──────────────────────────────────────────────────────
if [[ ! -f "./gradlew" ]]; then
  echo "❌ 未找到 gradlew，请确认在正确的目录运行"
  exit 1
fi
echo "✅ Gradle Wrapper 就绪"

# ── 处理快照 ─────────────────────────────────────────────────────────────────
SNAPSHOT_SRC="${SNAPSHOT_PATH:-/storage/emulated/0/下载/Download/snapshot-x86_64.tar.xz}"
ASSETS_DIR="app/src/main/assets"
mkdir -p "$ASSETS_DIR"

if [[ -f "$SNAPSHOT_SRC" ]]; then
  cp "$SNAPSHOT_SRC" "$ASSETS_DIR/snapshot.tar.xz"
  echo "✅ 快照已就位: $SNAPSHOT_SRC"
else
  echo "⚠️  快照不存在: $SNAPSHOT_SRC"
  echo "   请确保快照文件存在，或设置 SNAPSHOT_PATH 环境变量"
  exit 1
fi

# ── 读取版本号 ───────────────────────────────────────────────────────────────
VERSION_NAME=$(grep 'versionName' app/build.gradle.kts | grep -oE '"[^"]+"' | tail -1 | tr -d '"')
echo "📦 构建版本: ${VERSION_NAME}"

# ── 构建 ─────────────────────────────────────────────────────────────────────
echo ""
echo "▶ 开始构建（可能需要 5-15 分钟）..."
echo ""

if command -v sdkmanager &>/dev/null; then
  # 有 Android SDK，设置环境变量
  export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
  export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
  echo "  ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
fi

./gradlew assembleRelease \
  -Pandroid.suppressUnsupportedCompileSdk=36 \
  --no-daemon \
  2>&1 | tee /tmp/build.log

# ── 查找 APK ─────────────────────────────────────────────────────────────────
APK_PATH=""
for f in \
  "./deepseek-harness-${VERSION_NAME}-release.apk" \
  "./deepseek-harness-${VERSION_NAME}-debug.apk" \
; do
  if [[ -f "$f" ]]; then
    APK_PATH="$f"
    break
  fi
done

# 兜底：在 build 目录查找
if [[ -z "$APK_PATH" ]]; then
  APK_PATH=$(find . -name "*.apk" -newer /tmp/build.log 2>/dev/null | head -1)
fi

if [[ -z "$APK_PATH" || ! -f "$APK_PATH" ]]; then
  echo ""
  echo "❌ 构建失败，未找到 APK"
  echo "   查看构建日志: cat /tmp/build.log | tail -50"
  exit 1
fi

APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
echo ""
echo "══════════════════════════════════════════"
echo "  ✅ 构建成功！"
echo "══════════════════════════════════════════"
echo "  APK:  ${APK_PATH}"
echo "  大小: ${APK_SIZE}"
echo "  版本: ${VERSION_NAME}"
echo "──────────────────────────────────────────"
echo ""
echo "📌 下一步："
echo "  1. 安装到设备: adb install ${APK_PATH}"
echo "  2. 或复制到手机: cp ${APK_PATH} /sdcard/Download/"
echo "  3. 推送 GitHub: bash build-and-push.sh"
