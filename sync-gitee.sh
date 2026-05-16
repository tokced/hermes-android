#!/bin/bash
# 同步 Hermes Android 到 Gitee（国内访问）
# 用法: ./sync-gitee.sh <versionCode> <versionName> <releaseNotes>
# 示例: ./sync-gitee.sh 141 "1.14" "新增聊天记录云同步功能"
#
# 前置条件:
#   - Gitee 仓库已创建并设置为公开
#   - Gitee 私人令牌已写入环境变量 GITEE_TOKEN
#   - export GITEE_TOKEN="your-gitee-personal-access-token"

set -e

if [ $# -lt 3 ]; then
    echo "用法: $0 <versionCode> <versionName> <releaseNotes>"
    echo "示例: $0 141 '1.14' '新增聊天记录云同步功能'"
    exit 1
fi

VERSION_CODE=$1
VERSION_NAME=$2
RELEASE_NOTES=$3
PROJECT_DIR="/home/tokce/android-hermes"
GITEE_TOKEN="${GITEE_TOKEN:-}"
GITEE_OWNER="tokce"
GITEE_REPO="hermes-android"
APK_PATH="${PROJECT_DIR}/app/build/outputs/apk/debug/app-debug.apk"

echo "============================================"
echo "同步 Hermes Android 到 Gitee"
echo "Gitee 仓库: https://gitee.com/${GITEE_OWNER}/${GITEE_REPO}"
echo "============================================"

# 1. 检查 Gitee Token
if [ -z "$GITEE_TOKEN" ]; then
    echo "[错误] 未设置 GITEE_TOKEN 环境变量"
    echo "请先设置: export GITEE_TOKEN='your-gitee-personal-access-token'"
    echo "获取方式: Gitee -> 个人设置 -> 私人令牌 -> 添加"
    exit 1
fi

# 2. 检查 APK
if [ ! -f "$APK_PATH" ]; then
    echo "[错误] APK 不存在: $APK_PATH"
    exit 1
fi
APK_FILENAME="hermes-${VERSION_NAME}.apk"
echo "[1/4] APK: $(du -h $APK_PATH | cut -f1) -> ${APK_FILENAME}"

# 3. 克隆 Gitee 仓库并同步文件到 /apk/ 目录
GITEE_DIR="/tmp/gitee-sync-$$"
git clone --depth 1 "https://${GITEE_TOKEN}@gitee.com/${GITEE_OWNER}/${GITEE_REPO}.git" "$GITEE_DIR" 2>/dev/null || {
    echo "[错误] Gitee 仓库克隆失败，请确认仓库存在且 Token 有权限"
    exit 1
}

# 获取 Gitee 仓库的默认分支名
DEFAULT_BRANCH=$(curl -s "https://gitee.com/api/v5/repos/${GITEE_OWNER}/${GITEE_REPO}" -H "Authorization: token ${GITEE_TOKEN}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('default_branch') or 'master')")
echo "Gitee 默认分支: $DEFAULT_BRANCH"

# 重命名本地分支为 Gitee 默认分支
git -C "$GITEE_DIR" branch -m master "$DEFAULT_BRANCH" 2>/dev/null || true

# 创建 /apk/ 目录并复制 APK
mkdir -p "$GITEE_DIR/apk/"
cp "$APK_PATH" "$GITEE_DIR/apk/${APK_FILENAME}"
echo "[2/4] APK 已复制到 apk/${APK_FILENAME}"

# 4. 提交并推送
cd "$GITEE_DIR"
GITEE_URL="https://tokce:${GITEE_TOKEN}@gitee.com/${GITEE_OWNER}/${GITEE_REPO}.git"
git config user.email "tokce@example.com"
git config user.name "tokce"
git add -A
git commit -m "v${VERSION_NAME}: ${RELEASE_NOTES}"
git push "$GITEE_URL" "$DEFAULT_BRANCH"
echo "[3/4] Gitee 仓库已更新 (apk/${APK_FILENAME})"

# 5. 创建 Gitee Release
TAG_NAME="v${VERSION_NAME}"
git tag "$TAG_NAME" 2>/dev/null || true
git push "$GITEE_URL" "$TAG_NAME"

RESPONSE=$(curl -s -X POST \
    -H "Authorization: token ${GITEE_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{
        \"tag_name\": \"${TAG_NAME}\",
        \"name\": \"Hermes Android ${VERSION_NAME}\",
        \"body\": \"v${VERSION_NAME}: ${RELEASE_NOTES}\",
        \"target_commitish\": \"${DEFAULT_BRANCH}\"
    }" \
    "https://gitee.com/api/v5/repos/${GITEE_OWNER}/${GITEE_REPO}/releases")

RELEASE_ID=$(echo "$RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('id',''))" 2>/dev/null || echo "")
echo "[4/4] Gitee Release 创建: ${RELEASE_ID}"

# 清理
rm -rf "$GITEE_DIR"

echo ""
echo "============================================"
echo "Gitee 同步完成"
echo "APK 下载: https://gitee.com/tokce/hermes-android/raw/master/apk/${APK_FILENAME}"
echo "============================================"