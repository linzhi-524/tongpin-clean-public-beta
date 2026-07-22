# 公开前检查清单

- [ ] 仓库可见性改为 Public 前，再确认 Git 历史中没有密钥或签名文件。
- [ ] 仓库地址若不是 `https://github.com/linzhi-524/tongpin-clean-public-beta`，更新 README 的 Deploy to Render 按钮。
- [ ] GitHub Actions 的 server 与 android 两个任务均通过。
- [ ] 从 Actions 下载并安装 APK，确认默认服务器显示为 `https://your-service.onrender.com`。
- [ ] 使用一只新的 Render 账号或 Workspace 测试 Deploy to Render 按钮。
- [ ] 部署后检查 `/health`、`/lan`、`/control` 与 `/mcp`。
- [ ] 检查 README 与 `docs/AI连接教程.md` 中的示例地址没有泄露真实房间密钥。
- [ ] 发布说明写清楚：本版不限定 ChatGPT，可通过 MCP、REST API 或网页遥控器连接；播放器基础适配覆盖 QQ 音乐、酷狗音乐、网易云音乐，并可尝试多播放器界面歌词/OCR。
- [ ] Issue 模板或置顶说明提醒测试者遮挡房间密钥。
- [ ] 发布帖写清楚：酷狗 / 网易云已支持基础同步、媒体控制与歌词读取/OCR 兜底尝试，但不包含自动搜索点歌。

- [ ] 用一台电脑和一台手机测试局域网地址 `http://电脑IP:3000` 的保存检测、创建房间和网页遥控器。
