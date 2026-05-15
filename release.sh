#!/bin/bash
# Hermes Android 发版脚本
# 用法: ./release.sh <versionCode> <versionName> <releaseNotes>
# 示例: ./release.sh 14 "1.13" "新增聊天记录云同步功能"
#
# 前置条件:
#   - 已编译: ./gradlew assembleDebug
#   - 已 commit 所有更改
#   - GitHub CLI 已登录: gh auth status
#   - hermes-bridge 已安装并有版本写入权限

set -e

# 参数检查
if [ $# -lt 3 ]; then
    echo "用法: $0 <versionCode> <versionName> <releaseNotes>"
    echo "示例: $0 14 '1.13' '新增聊天记录云同步功能'"
    exit 1
fi

VERSION_CODE=$1
VERSION_NAME=$2
RELEASE_NOTES=$3
PROJECT_DIR="/home/tokce/android-hermes"
BRIDGE_DIR="/home/tokce/hermes-bridge"
APK_PATH="${PROJECT_DIR}/app/build/outputs/apk/debug/app-debug.apk"
GITHUB_REPO="tokced/hermes-android"
BRIDGE_VERSION_JSON="${BRIDGE_DIR}/version.json"

echo "============================================"
echo "Hermes Android 发版"
echo "versionCode: ${VERSION_CODE}"
echo "versionName: ${VERSION_NAME}"
echo "releaseNotes: ${RELEASE_NOTES}"
echo "============================================"

# 1. 检查 APK 是否存在
if [ ! -f "$APK_PATH" ]; then
    echo "[错误] APK 不存在: ${APK_PATH}"
    echo "请先运行: cd ${PROJECT_DIR} && ./gradlew assembleDebug"
    exit 1
fi
echo "[1/9] APK 存在: $(du -h $APK_PATH | cut -f1)"

# 2. 检查 git 状态
cd "$PROJECT_DIR"
UNCOMMITTED=$(git status --porcelain)
if [ -n "$UNCOMMITTED" ]; then
    echo "[错误] 有未提交的更改，请先 commit:"
    echo "$UNCOMMITTED"
    exit 1
fi
echo "[2/9] Git 工作区干净"

# 3. 更新 build.gradle.kts
GRADLE_FILE="${PROJECT_DIR}/app/build.gradle.kts"
# 备份并替换 versionCode 和 versionName
sed -i "s/versionCode = [0-9]*/versionCode = ${VERSION_CODE}/" "$GRADLE_FILE"
sed -i "s/versionName = \"[^\"]*\"/versionName = \"${VERSION_NAME}\"/" "$GRADLE_FILE"
echo "[3/9] build.gradle.kts 更新完成 (versionCode=${VERSION_CODE}, versionName=${VERSION_NAME})"

# 4. Git commit（仅版本号变更）
git add -A
git commit -m "v${VERSION_NAME}: ${RELEASE_NOTES}"
echo "[4/9] Git commit 完成"

# 5. Git tag
TAG_NAME="v${VERSION_NAME}"
git tag -a "$TAG_NAME" -m "v${VERSION_NAME}: ${RELEASE_NOTES}"
echo "[5/9] Git tag ${TAG_NAME} 创建完成"

# 6. Git push（commit + tag）
git push
git push origin "$TAG_NAME"
echo "[6/9] Git push 完成"

# 7. 创建 GitHub Release（非草稿）
gh release create "$TAG_NAME" \
    --title "Hermes Android ${VERSION_NAME}" \
    --notes "${RELEASE_NOTES}" \
    --repo "$GITHUB_REPO"
echo "[7/9] GitHub Release 创建完成"

# 8. 上传 APK
gh release upload "$TAG_NAME" "$APK_PATH" --clobber --repo "$GITHUB_REPO"
echo "[8/9] APK 上传完成"

# 9. 标记为 Latest
gh release edit "$TAG_NAME" --latest --repo "$GITHUB_REPO"
echo "[9/9] 标记为 Latest 完成"

# 10. 更新 Bridge version.json
DOWNLOAD_URL="https://github.com/${GITHUB_REPO}/releases/download/${TAG_NAME}/app-debug.apk"
cat > "$BRIDGE_VERSION_JSON" << EOF
{
  "version_code": ${VERSION_CODE},
  "version_name": "${VERSION_NAME}",
  "apk_url": "/v1/apk",
  "download_url": "${DOWNLOAD_URL}",
  "release_notes": "v${VERSION_NAME}: ${RELEASE_NOTES}",
  "min_version_code": 0
}
EOF
echo "[10/10] Bridge version.json 更新完成"

# 11. 重启 hermes-bridge
sudo systemctl restart hermes-bridge
sleep 2
echo "[11/11] hermes-bridge 重启完成"

# 12. 验证
RESP=$(curl -s "http://127.0.0.1:8000/v1/version?current_version_code=${VERSION_CODE}" \
    -H "x-api-key: hermes-bridge-secret-key-change-me")
IS_UPDATE=$(echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('is_update_available','?'))")
echo ""
echo "============================================"
echo "发版完成！"
echo "Release: https://github.com/${GITHUB_REPO}/releases/tag/${TAG_NAME}"
echo "Bridge 验证 (当前版本=${VERSION_CODE}): is_update_available=${IS_UPDATE}"
echo "============================================"
