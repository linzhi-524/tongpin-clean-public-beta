# 同频 Clean 1.2.0 Public Beta

同频 Clean 是一个 Android + Node.js 的共同听歌桥接工具。手机端读取当前媒体状态并上传到用户自己的房间服务器；兼容远程 MCP、REST API 的 AI 客户端或网页遥控器可以读取歌曲、当前播放器、进度和歌词，并发送播放控制命令。

> **公开测试版提示**：后台领取指令、播放器进度控制和歌词增强仍可能受不同手机系统、播放器版本与省电策略影响。请勿把房间密钥发到公开区域。

## 1.2.0 更新重点

- 新增多播放器基础适配：**QQ 音乐、酷狗音乐、网易云音乐**。
- 手机端会优先选择正在播放的受支持播放器，并在状态与诊断信息里显示当前播放器。
- 房间 API、网页遥控器和 MCP 返回里增加 `playerName`，便于 AI 或网页直接说明“当前来自哪个播放器”。
- 播放、暂停、上一首、下一首、跳转进度继续走 Android MediaSession / 媒体按键；不同播放器是否完整支持 `seek` 需要真机验证。
- QQ 音乐界面歌词读取和 OCR 仍保留为 QQ 音乐专用；酷狗 / 网易云当前先使用系统媒体会话 + LRCLIB 时间轴歌词。

[![Deploy to Render](https://render.com/images/deploy-to-render-button.svg)](https://render.com/deploy?repo=https://github.com/linzhi-524/tongpin-clean-public-beta)

## 最快开始

### 1. 部署自己的服务器

点击上方 **Deploy to Render**：

1. 登录 Render。
2. 检查将要创建的 Web Service，并按提示修改成一个未被占用的服务名称。
3. 确认使用 Free 方案并开始部署。
4. 部署完成后复制 Render 地址，例如：

   ```text
   https://your-tongpin.onrender.com
   ```

5. 打开 `https://your-tongpin.onrender.com/health`，看到 `ok: true` 即表示服务器正常。

每位使用者都应填写自己部署后获得的 Render 地址；公开源码不会预填作者的服务器。

App 中填写基础地址，不要添加 `/mcp`；连接支持远程 MCP 的 AI 客户端时使用：

```text
https://your-tongpin.onrender.com/mcp
```

如果使用的不是 ChatGPT，也可以继续使用同频 Clean。公开测试版提供三种入口：

| 入口 | 适合谁 | 地址 |
| --- | --- | --- |
| MCP | 支持远程 MCP / 自定义连接器的 AI 客户端 | `/mcp` |
| REST API | Dify、Coze/扣子、n8n、自建 Bot、脚本和工作流 | `/api/rooms...` |
| 网页遥控器 | 暂时不支持工具连接的普通聊天 AI 用户 | `/control` |

详细教程见：[docs/AI连接教程.md](docs/AI连接教程.md)。

### 2. 获取 Android APK

打开仓库的 **Actions → Build Tongpin Clean → 最新成功运行 → Artifacts**，下载 `tongpin-clean-1.2.0-apk`，解压并安装其中的 `app-debug.apk`。

这是公开测试用 Debug APK。Android 可能提示来源未知，请只从本仓库的 Actions 构建产物下载安装。

### 3. 连接手机

1. 在 App 中把示例服务器地址替换成你自己的 Render 地址，点击“保存并检测”。
2. 开启通知使用权。
3. 在 QQ 音乐、酷狗音乐或网易云音乐播放歌曲。
4. 创建房间并妥善保存房间码与房间密钥。
5. 需要后台接收命令时开启“后台待命”，并在手机系统中允许后台运行。
6. 需要增强歌词时，可主动开启 QQ 音乐歌词读取；OCR 只作为 QQ 音乐歌词读取的可选兜底。

## 支持的播放器

| 播放器 | 包名 | 当前支持 |
| --- | --- | --- |
| QQ 音乐 | `com.tencent.qqmusic` | 状态读取、播放控制、进度同步/跳转、QQ 界面歌词读取、OCR 兜底 |
| 酷狗音乐 | `com.kugou.android` | 状态读取、播放控制、进度同步/跳转；歌词走 LRCLIB 匹配 |
| 网易云音乐 | `com.netease.cloudmusic` | 状态读取、播放控制、进度同步/跳转；歌词走 LRCLIB 匹配 |

> 说明：播放/暂停/切歌通常比较稳；进度跳转取决于播放器是否向 Android 媒体会话暴露 `seek` 能力。自动搜索点歌尚未在酷狗和网易云开启，后续可以另做 beta 适配。

## MCP 工具

- `create_room`：创建房间。
- `get_room`：读取歌曲、当前播放器、进度、歌词、播放状态和命令结果。
- `set_playback_command`：发送播放、暂停、上一首、下一首和跳转进度命令。
- `add_listening_note`：在当前歌曲与进度附近添加听歌笔记。

每次调用房间工具都需要对应的房间码和房间密钥。

## 其他 AI 怎么接

- **ChatGPT**：使用支持 MCP / Apps 的入口时，填 `https://你的服务地址/mcp`。
- **Claude**：使用自定义连接器 / 远程 MCP 时，填同一个 `/mcp` 地址。
- **Gemini / 自建 Agent**：优先使用 MCP 客户端代码或 REST API；普通聊天 App 暂时可配合网页遥控器。
- **豆包、通义、Kimi、DeepSeek、扣子、Dify、n8n 等**：如果支持 HTTP 工具，就按 REST API 配置；如果暂时不支持，就先使用 `/control` 网页遥控器。

完整步骤和示例请求见：[docs/AI连接教程.md](docs/AI连接教程.md)。

## 目录

- `apps/android`：Android 客户端。
- `services/server`：房间 API、MCP 与网页遥控器。
- `render.yaml`：Render 一键部署配置。
- `.github/workflows/build.yml`：服务器测试与 Debug APK 构建。
- `docs`：版本说明。

## 歌词策略

```text
当前歌曲内存缓存
  ↓
LRCLIB 智能时间轴匹配（QQ / 酷狗 / 网易云都可尝试）
  ↓ 没有或超时
QQ 音乐界面文字节点（用户主动授权）
  ↓ 仍读不到
本地中文 OCR（用户主动开启，Android 11+，QQ 音乐专用兜底）
```

QQ 音乐界面读取与 OCR 仍只针对 QQ 音乐。服务端只接收当前句、下一句、来源与识别时间，不接收屏幕截图。

## Render 免费方案须知

- 免费 Web Service 连续 15 分钟没有收到流量后可能休眠，下一次请求需要等待服务重新启动。
- 默认文件系统是临时的；服务重启或重新部署后，房间和笔记可能消失。
- 一键部署关闭了自动部署。上游仓库更新时，其他人的实例不会被强制同步更新。

## 已知问题

- 部分 Android 厂商会冻结后台服务，可能出现回到 App 后才领取指令。
- QQ 音乐不同版本的歌词界面结构不同，歌词读取和 OCR 不保证始终可用。
- 酷狗和网易云的自动搜索点歌暂未开放；本版先做基础媒体会话适配。
- 免费 Render 服务刚唤醒时，第一次检测或创建房间可能较慢。

## 隐私与测试许可

请阅读：

- [PRIVACY.md](PRIVACY.md)
- [SECURITY.md](SECURITY.md)
- [TESTING_PERMISSION.md](TESTING_PERMISSION.md)

提交 Issue 时请附上手机品牌、Android 版本、播放器名称与版本、App 版本、复现步骤和已遮挡敏感信息的截图。
