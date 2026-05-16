#!/bin/bash
# Hermes Android 发版脚本
#
# 用法:
#   ./release.sh                    # 自动从最新 tag 递增
#   ./release.sh <versionName>       # 指定版本名，versionCode 自动 +1
#   ./release.sh <versionCode> <versionName> <releaseNotes>  # 完整参数（兼容旧用法）
#
# 自动规则:
#   - versionCode = 上一个 tag 的 versionCode + 1（无法手动指定）
#   - versionName 从 git tag 或参数获取
#   - build.gradle.kts / Bridge version.json / GitHub Release 三方同步
#
# 前置条件:
#   - 已编译: ./gradlew assembleDebug
#   - 已 commit 所有更改
#   - GitHub CLI 已登录: gh auth status
#   - hermes-bridge 已安装并有版本写入权限

set -e

PROJECT_DIR="/home/tokce/android-hermes"
BRIDGE_DIR="/home/tokce/hermes-bridge"
APK_PATH="${PROJECT_DIR}/app/build/outputs/apk/debug/app-debug.apk"
GITHUB_REPO="tokced/hermes-android"
BRIDGE_VERSION_JSON="${BRIDGE_DIR}/version.json"
GRADLE_FILE="${PROJECT_DIR}/app/build.gradle.kts"

cd "$PROJECT_DIR"
git fetch --tags

# 解析参数
LATEST_TAG=$(git tag --sort=-v:refname | head -1)
LATEST_CODE=$(echo "$LATEST_TAG" | sed 's/v//')
# 从 build.gradle.kts 读取当前 versionCode
CURRENT_CODE=$(grep 'versionCode' "$GRADLE_FILE" | sed 's/.*= *//')

if [ $# -eq 0 ]; then
    # 自动递增
    VERSION_NAME="${LATEST_TAG#v}"
    NEXT_CODE=$((CURRENT_CODE + 1))
    RELEASE_NOTES="版本更新"
elif [ $# -eq 1 ]; then
    VERSION_NAME="$1"
    NEXT_CODE=$((CURRENT_CODE + 1))
    RELEASE_NOTES="版本更新"
elif [ $# -eq 3 ]; then
    NEXT_CODE="$1"
    VERSION_NAME="$2"
    RELEASE_NOTES="$3"
else
    echo "用法:"
    echo "  $0                       # 自动递增 versionCode，versionName 从最新 tag 获取"
    echo "  $0 <versionName>         # 指定 versionName，versionCode 自动 +1"
    echo "  $0 <versionCode> <versionName> <releaseNotes>  # 完整参数"
    exit 1
fi

TAG_NAME="v${VERSION_NAME}"

echo "============================================"
echo "Hermes Android 发版"
echo "最新 tag:       ${LATEST_TAG}"
echo "当前 versionCode: ${CURRENT_CODE}"
echo "新 versionCode:  ${NEXT_CODE}"
echo "新 versionName:  ${VERSION_NAME}"
echo "新 tag:          ${TAG_NAME}"
echo "releaseNotes:   ${RELEASE_NOTES}"
echo "============================================"

# 1. 检查 APK
if [ ! -f "$APK_PATH" ]; then
    echo "[错误] APK 不存在: ${APK_PATH}"
    echo "请先运行: cd ${PROJECT_DIR} && ./gradlew assembleDebug"
    exit 1
fi
echo "[1/11] APK 存在: $(du -h $APK_PATH | cut -f1)"

# 2. 检查 git 状态
UNCOMMITTED=$(git status --porcelain)
if [ -n "$UNCOMMITTED" ]; then
    echo "[错误] 有未提交的更改，请先 commit"
    echo "$UNCOMMITTED"
    exit 1
fi
echo "[2/11] Git 工作区干净"

# 3. 更新 build.gradle.kts
sed -i "s/versionCode = [0-9]*/versionCode = ${NEXT_CODE}/" "$GRADLE_FILE"
sed -i "s/versionName = \"[^\"]*\"/versionName = \"${VERSION_NAME}\"/" "$GRADLE_FILE"
echo "[3/11] build.gradle.kts 更新完成 (versionCode=${NEXT_CODE}, versionName=${VERSION_NAME})"

# 4. Git add + commit
git add -A
git commit -m "v${VERSION_NAME}: ${RELEASE_NOTES}"
echo "[4/11] Git commit 完成"

# 5. Git tag
git tag -a "$TAG_NAME" -m "v${VERSION_NAME}: ${RELEASE_NOTES}"
echo "[5/11] Git tag ${TAG_NAME} 创建完成"

# 6. Git push
git push
git push origin "$TAG_NAME"
echo "[6/11] Git push 完成"

# 7. 创建 GitHub Release
gh release create "$TAG_NAME" \
    --title "Hermes Android ${VERSION_NAME}" \
    --notes "${RELEASE_NOTES}" \
    --repo "$GITHUB_REPO"
echo "[7/11] GitHub Release 创建完成"

# 8. 上传 APK
gh release upload "$TAG_NAME" "$APK_PATH" --clobber --repo "$GITHUB_REPO"
echo "[8/11] APK 上传完成"

# 9. 标记为 Latest
gh release edit "$TAG_NAME" --latest --repo "$GITHUB_REPO"
echo "[9/11] 标记为 Latest 完成"

# 10. 更新 Bridge version.json
DOWNLOAD_URL="https://github.com/${GITHUB_REPO}/releases/download/${TAG_NAME}/app-debug.apk"
cat > "$BRIDGE_VERSION_JSON" << EOF
{
  "version_code": ${NEXT_CODE},
  "version_name": "${VERSION_NAME}",
  "apk_url": "/v1/apk",
  "download_url": "${DOWNLOAD_URL}",
  "release_notes": "v${VERSION_NAME}: ${RELEASE_NOTES}",
  "min_version_code": 0
}
EOF
echo "[10/11] Bridge version.json 更新完成"

# 11. 重启 hermes-bridge
sudo systemctl restart hermes-bridge
sleep 2
echo "[11/11] hermes-bridge 重启完成"

# 验证
RESP=$(curl -s "http://127.0.0.1:8000/v1/version?current_version_code=${NEXT_CODE}" \
    -H "x-api-key: hermes-bridge-secret-key-change-me")
IS_UPDATE=$(echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('is_update_available','?'))")

echo ""
echo "============================================"
echo "发版完成！"
echo "Release: https://github.com/${GITHUB_REPO}/releases/tag/${TAG_NAME}"
echo "Bridge 验证 (当前版本=${NEXT_CODE}): is_update_available=${IS_UPDATE}"
echo "============================================"