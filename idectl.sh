#!/usr/bin/env bash
# idectl 辅助脚本：构建 / 部署到日常 IDEA / 沙箱调试。
# 用 `./idectl.sh -h` 查看用法。
set -eo pipefail

# 始终以脚本所在目录(工程根)为工作目录，随处调用都行。
cd "$(dirname "$0")"

usage() {
  cat <<'EOF'
idectl —— 构建 / 部署 / 调试 辅助脚本

用法：
  ./idectl.sh <命令> [选项]

命令：
  build                构建插件 zip → build/distributions/idectl-<版本>.zip
  deploy               构建并安装 / 替换到日常 IntelliJ IDEA（装完需【重启 IDEA】生效）
  run                  启动沙箱 IDE 调试（gradle runIde，不影响日常 IDEA）
  clean                清理构建产物
  -h, --help           显示本帮助

deploy 选项：
  --ide-dir <路径>     目标 IDE 配置目录（含 plugins/ 的那层）
                       默认：~/Library/Application Support/JetBrains/IntelliJIdea2026.1

run 选项：
  --token <令牌>       用固定访问令牌启动，便于直接连 MCP（如 smoketoken123）
  --project <路径>     启动时打开的项目路径

示例：
  ./idectl.sh build
  ./idectl.sh deploy
  ./idectl.sh deploy --ide-dir "$HOME/Library/Application Support/JetBrains/IntelliJIdea2025.3"
  ./idectl.sh run --token smoketoken123 --project /Users/me/proj

说明：
  · deploy 会删掉旧的 plugins/idectl 再解包新版；运行中的 IDE 只在【重启】后加载新版。
  · 首次安装也可用 GUI：设置 → 插件 → ⚙ → 从磁盘安装 → 选 zip → 重启。
EOF
}

cmd="${1:-}"
shift || true

case "$cmd" in
  build)
    ./gradlew buildPlugin
    ;;

  deploy)
    ide_dir=""
    while [ $# -gt 0 ]; do
      case "$1" in
        --ide-dir) ide_dir="${2:-}"; shift 2 ;;
        *) echo "deploy: 未知选项 '$1'（用 -h 看用法）" >&2; exit 1 ;;
      esac
    done
    if [ -n "$ide_dir" ]; then
      ./gradlew deployToIde -PideConfigDir="$ide_dir"
    else
      ./gradlew deployToIde
    fi
    echo ">>> 部署完成，重启 IntelliJ IDEA 后生效。 <<<"
    ;;

  run)
    token=""; project=""
    while [ $# -gt 0 ]; do
      case "$1" in
        --token)   token="${2:-}";   shift 2 ;;
        --project) project="${2:-}"; shift 2 ;;
        *) echo "run: 未知选项 '$1'（用 -h 看用法）" >&2; exit 1 ;;
      esac
    done
    gargs=""
    [ -n "$token" ]   && gargs="$gargs -PbridgeToken=$token"
    [ -n "$project" ] && gargs="$gargs -PopenProject=$project"
    # shellcheck disable=SC2086
    ./gradlew runIde $gargs
    ;;

  clean)
    ./gradlew clean
    ;;

  -h|--help|"")
    usage
    ;;

  *)
    echo "未知命令：'$cmd'" >&2
    echo >&2
    usage >&2
    exit 1
    ;;
esac
