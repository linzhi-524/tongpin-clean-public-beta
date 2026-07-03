# 公开前检查清单

- [ ] 仓库可见性改为 Public 前，再确认 Git 历史中没有密钥或签名文件。
- [ ] 仓库地址若不是 `https://github.com/linzhi-524/tongpin-clean-public-beta`，更新 README 的 Deploy to Render 按钮。
- [ ] GitHub Actions 的 server 与 android 两个任务均通过。
- [ ] 从 Actions 下载并安装 APK，确认默认服务器显示为 `https://your-service.onrender.com`。
- [ ] 使用一只新的 Render 账号或 Workspace 测试 Deploy to Render 按钮。
- [ ] 部署后检查 `/health`、`/control` 与 `/mcp`。
- [ ] Issue 模板或置顶说明提醒测试者遮挡房间密钥。
