#!/usr/bin/env bash
# idectl 一键发版：构建插件 zip → 算 SHA256 → 读 annotated tag 的「本次更新」→ 组装 notes → gh release create。
# 参考 7zip 的 Mac/SevenZipFM/release.sh：同为「本地脚本 = CI 的本地等价物」——本仓库无 CI，发版是手动的。
#
# 用法：
#   # 1.（可选）改产品版本：编辑 gradle.properties 的 pluginVersion，并保持 tag 与之一致
#   git tag -a v1.0.0 -m "本次更新：修了 XXX、加了 YYY"   # annotated tag，-m 写更新内容（可多个 -m 分段）
#   git push origin v1.0.0
#   bash scripts/release.sh v1.0.0        # 或 ./idectl.sh release v1.0.0
# 忘了 -m（或打的是 lightweight tag）也能发，只是 body 里没有「本次更新」段，不会误取 commit message。
set -euo pipefail
TAG="${1:?用法: release.sh <tag，如 v1.0.0>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
cd "$REPO"
REPO_SLUG="NianDUI/idectl"

echo "==[1] 构建插件 zip =="
./gradlew buildPlugin --console=plain
ZIP="$(ls -t build/distributions/idectl-*.zip 2>/dev/null | head -1)"
[ -f "$ZIP" ] || { echo "✗ 没找到插件 zip（build/distributions/idectl-*.zip）"; exit 1; }
SHA="$(shasum -a 256 "$ZIP" | awk '{print $1}')"
echo "  zip = $ZIP"
echo "  sha = $SHA"

# 产品版本 —— 从 gradle.properties 读，零维护（改版本只改一处）
PLUGIN_VER="$(grep -m1 '^pluginVersion' gradle.properties | sed -E 's/.*=[[:space:]]*//')"
# 目标平台下限 —— 从 build.gradle.kts 的 sinceBuild 读（7zip 内核版本的对应物，跟随代码自动跟变）
SINCE="$(grep -m1 'sinceBuild' build.gradle.kts | sed -E 's/.*"([0-9]+)".*/\1/')"
echo "  产品版本 = ${PLUGIN_VER:-未知}   sinceBuild = ${SINCE:-未知}"

# 版本一致性软检查：zip 文件名里的产品版本 应 == tag 去掉 v 前缀
ZIP_VER="$(basename "$ZIP" | sed -E 's/^idectl-(.*)\.zip$/\1/')"
TAG_VER="${TAG#v}"
[ "$ZIP_VER" = "$TAG_VER" ] \
  || echo "  ⚠ zip 版本($ZIP_VER) ≠ tag 版本($TAG_VER)：记得改 gradle.properties 的 pluginVersion 让二者一致"

echo "==[2] 取 tag 的「本次更新」 =="
MSG=""
# 仅 annotated tag（cat-file 类型为 tag）才取 message；去掉可能的 PGP 签名段
if [ "$(git cat-file -t "$TAG" 2>/dev/null)" = "tag" ]; then
  MSG="$(git for-each-ref "refs/tags/$TAG" --format='%(contents)' | sed '/-----BEGIN PGP/,$d')"
fi
if [ -n "$(printf '%s' "$MSG" | tr -d '[:space:]')" ]; then echo "  ✓ 有更新说明"; else echo "  （无 annotated 更新说明，body 跳过该段）"; fi

echo "==[3] 组装 notes =="
NOTES="$(mktemp)"
{
  [ -n "$SINCE" ] && printf '> 目标平台：IntelliJ IDEA 2026.x（sinceBuild %s，无上限；Ultimate 或 Community）\n\n' "$SINCE"
  if [ -n "$(printf '%s' "$MSG" | tr -d '[:space:]')" ]; then
    printf '## 本次更新\n\n%s\n\n' "$MSG"
  fi
  cat "$HERE/release-body.md"
  printf '\n## 校验\nSHA-256 (`%s`):\n`%s`\n' "$(basename "$ZIP")" "$SHA"
} > "$NOTES"
echo "----- 预览 -----"; cat "$NOTES"; echo "----------------"

echo "==[4] gh release create $TAG =="
gh release create "$TAG" "$ZIP" \
  --title "idectl v${TAG_VER}" \
  --notes-file "$NOTES" --latest -R "$REPO_SLUG"
echo "✓ 发布完成：https://github.com/$REPO_SLUG/releases/tag/$TAG"
