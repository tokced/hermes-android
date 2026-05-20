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
#   - 本地归档 hermes-v{versionCode}.apk（Bridge 本地 + releases/ 目录）
#   - GitHub Release + Gitee 仓库同步
#   - Bridge 直接服务 APK（国内无需翻墙）
#
# 前置条件:
#   - GitHub CLI 已登录: gh auth status
#   - Gitee Token 已设置: export GITEE_TOKEN="xxx"

set -e

PROJECT_DIR="/home/tokce/android-hermes"
BRIDGE_DIR="/home/tokce/hermes-bridge"
BRIDGE_APK_DIR="${BRIDGE_DIR}/apk"      # Bridge APK 存储目录
LOCAL_ARCHIVE_DIR="${PROJECT_DIR}/releases"  # 本地归档备份
GITHUB_REPO="tokced/hermes-android"
GITHUB_REPO_PATH="https://github.com/${GITHUB_REPO}"
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

# APK 命名：versionCode 格式（hermes-v18.apk）
APK_NAME="hermes-v${NEXT_CODE}.apk"
APK_BRIDGE_PATH="${BRIDGE_APK_DIR}/${APK_NAME}"
APK_ARCHIVE_PATH="${LOCAL_ARCHIVE_DIR}/${APK_NAME}"
# GitHub Release 命名：versionName 格式（hermes-1.18.apk）
APK_GITHUB_NAME="hermes-${VERSION_NAME}.apk"

echo "============================================"
echo "Hermes Android 发版"
echo "最新 tag:       ${LATEST_TAG}"
echo "当前 versionCode: ${CURRENT_CODE}"
echo "新 versionCode:  ${NEXT_CODE}"
echo "新 versionName:  ${VERSION_NAME}"
echo "新 tag:          ${TAG_NAME}"
echo "APK (versionCode): ${APK_NAME}"
echo "GitHub Release:   ${APK_GITHUB_NAME}"
echo "本地归档:        ${APK_ARCHIVE_PATH}"
echo "Bridge 路径:     ${APK_BRIDGE_PATH}"
echo "releaseNotes:    ${RELEASE_NOTES}"
echo "============================================"

# 0. 确保目录存在
mkdir -p "$BRIDGE_APK_DIR"
mkdir -p "$LOCAL_ARCHIVE_DIR"

# 1. 先更新版本号
sed -i "s/versionCode = [0-9]*/versionCode = ${NEXT_CODE}/" "$GRADLE_FILE"
sed -i "s/versionName = \"[^\"]*\"/versionName = \"${VERSION_NAME}\"/" "$GRADLE_FILE"
echo "[1/12] build.gradle.kts 更新完成 (versionCode=${NEXT_CODE}, versionName=${VERSION_NAME})"

# 2. 编译
cd "${PROJECT_DIR}"
./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk "$APK_BRIDGE_PATH"
cp app/build/outputs/apk/debug/app-debug.apk "$APK_ARCHIVE_PATH"
echo "[2/12] 编译完成"
echo "       Bridge APK: ${APK_BRIDGE_PATH}"
echo "       本地归档:   ${APK_ARCHIVE_PATH}"

# 3. 检查 git 状态
UNCOMMITTED=$(git status --porcelain)
if [ -n "$UNCOMMITTED" ]; then
    echo "[错误] 有未提交的更改，请先 commit"
    echo "$UNCOMMITTED"
    exit 1
fi
echo "[3/12] Git 工作区干净"

# 4. Git add + commit
git add -A
git commit -m "v${VERSION_NAME}: ${RELEASE_NOTES}"
echo "[4/12] Git commit 完成"

# 5. Git tag
git tag -a "$TAG_NAME" -m "v${VERSION_NAME}: ${RELEASE_NOTES}"
echo "[5/12] Git tag ${TAG_NAME} 创建完成"

# 6. Git push（GitHub）
git push
git push origin "$TAG_NAME"
echo "[6/12] GitHub push 完成"

# 7. 创建 GitHub Release
gh release create "$TAG_NAME" \
    --title "Hermes Android ${VERSION_NAME}" \
    --notes "${RELEASE_NOTES}" \
    --repo "$GITHUB_REPO"
echo "[7/12] GitHub Release 创建完成"

# 8. 上传 APK 到 GitHub Release
cp "$APK_ARCHIVE_PATH" "${PROJECT_DIR}/${APK_GITHUB_NAME}"
gh release upload "$TAG_NAME" "${PROJECT_DIR}/${APK_GITHUB_NAME}" --clobber --repo "$GITHUB_REPO"
rm -f "${PROJECT_DIR}/${APK_GITHUB_NAME}"
echo "[8/12] GitHub Release APK 上传完成 (${APK_GITHUB_NAME})"

# 9. 标记为 Latest
gh release edit "$TAG_NAME" --latest --repo "$GITHUB_REPO"
echo "[9/12] GitHub Release 标记 Latest 完成"

# 10. Gitee 同步（推送 APK 到 Gitee 仓库 /apk/ 目录）
if [ -n "${GITEE_TOKEN:-}" ]; then
    "${PROJECT_DIR}/sync-gitee.sh" "$NEXT_CODE" "$VERSION_NAME" "$RELEASE_NOTES"
    echo "[10/12] Gitee 同步完成"
else
    echo "[10/12] 跳过 Gitee 同步（GITEE_TOKEN 未设置）"
fi

# 11. 更新 Bridge version.json
# download_url 指向 Bridge 本地 APK（无需翻墙）
DOWNLOAD_URL="/apk/${APK_NAME}"
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
echo "[11/12] Bridge version.json 更新完成"
echo "       download_url: ${DOWNLOAD_URL}"

# 12. 重启 hermes-bridge
sudo systemctl restart hermes-bridge
sleep 2
echo "[12/12] hermes-bridge 重启完成"

# 验证
RESP=$(curl -s "http://127.0.0.1:8000/v1/version?current_version_code=${NEXT_CODE}" \
    -H "x-api-key: hermes-bridge-secret-key-change-me")
IS_UPDATE=$(echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('is_update_available','?'))")

# 验证 Bridge APK 可访问
APK_CODE=$(curl -sI --max-time 10 "http://127.0.0.1:8000${DOWNLOAD_URL}" | head -1 | grep -o "200\|404\|500" || echo "error")

echo ""
echo "============================================"
echo "发版完成！"
echo "GitHub Release: ${GITHUB_REPO_PATH}/releases/tag/${TAG_NAME}"
echo "本地归档: ${APK_ARCHIVE_PATH}"
echo "Bridge APK:    ${APK_BRIDGE_PATH}"
echo "Bridge 验证 (当前版本=${NEXT_CODE}): is_update_available=${IS_UPDATE}"
echo "APK 可访问验证 (local): ${DOWNLOAD_URL} -> ${APK_CODE}"
echo "============================================"
