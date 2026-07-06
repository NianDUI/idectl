**idectl（IDE Control）** —— 内嵌 MCP 服务器的 IntelliJ IDEA 插件，让 AI 编码 Agent（Claude Code / Codex）直接驱动 IDE：运行与调试配置（原地切换 运行↔调试）、`grep` 实时控制台、构建、热重载、断点调试、项目/运行配置/SDK 管理、按角色受限的多 token 治理。

## 安装
1. 下载下方的 `idectl-<版本>.zip`（**不要解压**）。
2. IDEA → **Settings → Plugins → ⚙ → Install Plugin from Disk…** → 选中该 zip。
3. **重启 IDEA** 生效。

## 接入 Agent
打开第一个项目后，会弹气泡提示端口 + 可直接粘贴的连接命令；或到 **Settings → Tools → IDE Control → 一键接入 Agent**，为 Claude Code / Codex 点「一键配置」自动写入 MCP 配置。

## 要求
- 仅支持 **IntelliJ IDEA 2026.x**（Ultimate 或 Community）。
- 服务器仅绑定 `127.0.0.1`，由 Bearer token 保护。

---
文档、源码与许可：https://github.com/NianDUI/idectl （[README](https://github.com/NianDUI/idectl/blob/main/README.md)）
