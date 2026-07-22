# 同频 Clean · 多 AI 连接教程

同频 Clean 不绑定某一个 AI。它对外提供三种入口：

1. **MCP 入口**：适合支持远程 MCP / 自定义连接器的 AI 客户端。
2. **REST API 入口**：适合 Dify、Coze/扣子、n8n、自建 Bot、脚本、工作流等能发 HTTP 请求的平台。
3. **网页遥控器入口**：适合暂时不能接 MCP 或 API 的用户，任何 AI 都可以先用这页配合你排查和操作。

> 房间密钥等同于房间密码。公开发帖、截图、Issue、群聊和教程演示里都要打码。

## 0. 连接前准备

先完成这几件事：

1. 把服务端部署到 Render 或自己的 Node.js 服务器。
2. 打开 `https://你的服务地址/health`，确认返回 `ok: true`。
3. 手机 App 里填写基础地址，例如：

```text
https://your-tongpin.onrender.com
```

不要在 App 里填 `/mcp`。

4. 开启通知使用权，在 QQ 音乐、酷狗音乐或网易云音乐播放一首歌。
5. 在 App 里创建房间，保存：
   - 房间码 `code`
   - 房间密钥 `roomSecret`

## 1. MCP 入口

MCP 地址固定是：

```text
https://your-tongpin.onrender.com/mcp
```

MCP 工具包括：

- `create_room`：创建房间。
- `get_room`：读取歌曲、进度、播放状态、歌词、命令执行结果和听歌笔记。
- `set_playback_command`：发送播放、暂停、上一首、下一首和跳转进度命令。
- `add_listening_note`：添加听歌笔记。

每个房间工具都需要 `code` 和 `roomSecret`。

### ChatGPT

适合有自定义 MCP / App 开发入口的账号或工作区。

大致步骤：

1. 打开 ChatGPT 的开发者模式 / Apps / MCP 相关入口。
2. 新建一个自定义连接。
3. 名称可填：`同频 Clean`。
4. MCP URL 填：

```text
https://your-tongpin.onrender.com/mcp
```

5. 保存后，在对话里让 ChatGPT 使用 `同频 Clean` 工具。
6. 如果已经在 App 创建过房间，就把房间码和房间密钥发给可信的对话；也可以让 AI 调用 `create_room` 新建房间。

官方说明：

- https://help.openai.com/en/articles/12584461-developer-mode-and-mcp-apps-in-chatgpt
- https://help.openai.com/en/articles/11487775-connectors-in-chatgpt

### Claude

Claude 支持远程 MCP / 自定义连接器的账号，可添加同频 Clean 作为自定义连接。

大致步骤：

1. 进入 Claude 的 Connectors / Custom connectors 相关设置。
2. 添加新的自定义连接器。
3. 名称可填：`同频 Clean`。
4. 远程 MCP 地址填：

```text
https://your-tongpin.onrender.com/mcp
```

5. 保存后，在 Claude 对话里要求它读取房间或控制播放。

官方说明：

- https://support.anthropic.com/en/articles/11175166-getting-started-with-custom-connectors-using-remote-mcp
- https://support.anthropic.com/en/articles/11176164-pre-built-web-connectors-using-remote-mcp

### Gemini / Gemini API

Gemini 的网页或 App 是否能直接粘贴任意远程 MCP 地址，取决于当前产品入口；但 Gemini API / SDK 可以通过工具调用或 MCP 相关能力接入外部工具。

推荐做法：

- 如果你使用的是 Gemini API / 自建 Agent：优先接 REST API 或 MCP 客户端代码。
- 如果你使用的是普通 Gemini 聊天 App：先用网页遥控器 `/control`，或让 Gemini 帮你生成 REST 请求示例。

官方说明：

- https://ai.google.dev/gemini-api/docs/function-calling
- https://ai.google.dev/gemini-api/docs/interactions

### 其他支持 MCP 的客户端

只要客户端支持远程 Streamable HTTP MCP，通常都填：

```text
https://your-tongpin.onrender.com/mcp
```

如果客户端要求区分传输方式，选择：

```text
Streamable HTTP
```

MCP 官方说明：

- https://modelcontextprotocol.io/docs/getting-started/intro
- https://modelcontextprotocol.io/docs/develop/connect-remote-servers
- https://modelcontextprotocol.io/specification/2025-03-26/basic/transports

## 2. REST API 入口

适合：

- Dify / Coze / 扣子 / n8n / Zapier 类工作流
- 自己写的网页、小程序、Bot
- 还没有 MCP 入口、但能发 HTTP 请求的平台

### 创建房间

```bash
curl -X POST https://your-tongpin.onrender.com/api/rooms
```

返回里会有：

```json
{
  "code": "ABC123",
  "roomSecret": "一长串密钥"
}
```

### 读取房间

```bash
curl https://your-tongpin.onrender.com/api/rooms/ABC123 \
  -H "Authorization: Bearer 一长串密钥"
```

### 暂停播放

```bash
curl -X POST https://your-tongpin.onrender.com/api/rooms/ABC123/commands \
  -H "Authorization: Bearer 一长串密钥" \
  -H "Content-Type: application/json" \
  -d '{"type":"pause"}'
```

### 继续播放

```bash
curl -X POST https://your-tongpin.onrender.com/api/rooms/ABC123/commands \
  -H "Authorization: Bearer 一长串密钥" \
  -H "Content-Type: application/json" \
  -d '{"type":"play"}'
```

### 上一首 / 下一首

```bash
curl -X POST https://your-tongpin.onrender.com/api/rooms/ABC123/commands \
  -H "Authorization: Bearer 一长串密钥" \
  -H "Content-Type: application/json" \
  -d '{"type":"previous"}'
```

```bash
curl -X POST https://your-tongpin.onrender.com/api/rooms/ABC123/commands \
  -H "Authorization: Bearer 一长串密钥" \
  -H "Content-Type: application/json" \
  -d '{"type":"next"}'
```

### 跳转进度

`positionMs` 单位是毫秒。下面表示跳到第 60 秒：

```bash
curl -X POST https://your-tongpin.onrender.com/api/rooms/ABC123/commands \
  -H "Authorization: Bearer 一长串密钥" \
  -H "Content-Type: application/json" \
  -d '{"type":"seek","positionMs":60000}'
```

### 添加听歌笔记

```bash
curl -X POST https://your-tongpin.onrender.com/api/rooms/ABC123/notes \
  -H "Authorization: Bearer 一长串密钥" \
  -H "Content-Type: application/json" \
  -d '{"text":"这一句歌词刚好卡在这里。"}'
```

## 3. 网页遥控器入口

打开：

```text
https://your-tongpin.onrender.com/control
```

填入房间码和房间密钥，就能读取当前歌曲、当前播放器并发送播放控制。

这个方式最适合公开测试：

- 不要求用户正在使用哪一个 AI。
- 不要求账号支持 MCP。
- 方便排查：先确认网页能控制，再确认 AI 连接是否成功。

如果别人用的是豆包、通义、Kimi、DeepSeek、普通 Gemini、普通 Claude 或其他聊天产品，又没有 MCP 入口，可以先让他们用网页遥控器。

## 4. 可以直接发给测试者的说明

```text
同频 Clean 不限定 ChatGPT。

你可以用三种方式连接：
1. 支持远程 MCP 的 AI：填 https://你的服务地址/mcp
2. 支持 HTTP 工具/工作流的平台：按 REST API 教程配置
3. 不支持工具连接的 AI：先用 https://你的服务地址/control 网页遥控器

注意：房间密钥就是密码，不要公开发。截图和反馈前请打码。
```

## 5. 常见问题

### 为什么 AI 说连不上？

先打开：

```text
https://your-tongpin.onrender.com/health
```

如果服务没醒，免费 Render 可能需要等一会儿。

再打开：

```text
https://your-tongpin.onrender.com/control
```

如果网页遥控器能连，说明手机和服务器链路正常，问题多半出在 AI 客户端的 MCP 配置。

### 为什么 App 能播放但 AI 控制没反应？

检查：

1. 手机 App 是否开了通知使用权。
2. 是否已经创建房间。
3. 是否开了后台待命。
4. 房间码和房间密钥是否对应。
5. AI 调用工具后，App 诊断区有没有显示收到命令。

### 可以把作者的服务器地址直接发给别人吗？

不建议。公开测试更推荐每个人部署自己的 Render 服务，避免房间混用、数据丢失和密钥泄露。

### MCP、REST 和网页遥控器应该选哪个？

- 能接 MCP：优先 MCP。
- 没 MCP 但能发 HTTP：用 REST。
- 只是想先测试或不懂配置：用网页遥控器。
