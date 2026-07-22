# 同频 Clean · 局域网部署教程

局域网部署适合临时测试：手机和电脑在同一个 Wi-Fi 内，手机 App 直接连接电脑上的服务端。它不需要 Render，也不需要公网域名。

## 适合场景

- 想先在本地测试 Android App、网页遥控器、MCP/REST 接口。
- Render 免费实例休眠时，想用本地服务端快速排查。
- 宿舍、家里、办公室同一 Wi-Fi 内短时间使用。

不适合场景：手机离开当前 Wi-Fi 后继续远程使用。离开局域网后，请切回 Render 地址。

## 电脑端启动服务

先安装 Node.js 22 或更新版本。然后在项目根目录执行：

```bash
cd services/server
npm ci
npm run build
npm start
```

服务启动后会监听：

```text
http://0.0.0.0:3000
```

这表示同一局域网里的设备可以通过“电脑的局域网 IP + 3000 端口”访问。

## 找电脑局域网 IP

Windows 可在命令行执行：

```bash
ipconfig
```

查看当前 Wi-Fi 网卡的 IPv4 地址，例如：

```text
192.168.1.100
```

macOS / Linux 可执行：

```bash
ipconfig getifaddr en0
# 或
ip addr
```

## 手机 App 填写地址

手机和电脑连接同一个 Wi-Fi 后，在同频 Clean 的“服务器地址”填写：

```text
http://192.168.1.100:3000
```

把 `192.168.1.100` 换成你的电脑 IP。然后点击“保存并检测”。

检测成功后，可以继续创建房间、打开网页遥控器、连接 MCP 或 REST API。

## 自检地址

在手机浏览器或电脑浏览器打开：

```text
http://电脑IP:3000/health
http://电脑IP:3000/lan
http://电脑IP:3000/control
```

`/health` 返回 `ok: true` 表示服务端可用。

## 常见问题

### 手机检测失败

检查这几件事：

- 手机和电脑是否在同一个 Wi-Fi。
- 电脑 IP 是否填错。
- 电脑防火墙是否拦截 3000 端口。
- 服务端命令行是否还在运行。
- App 里是否误填了 `/mcp`、`/api` 或 `/control`。App 只填基础地址。

### 网页能打开，但 App 不能检测

确认 App 版本为 `1.3.0-public` 或更新版本。本版已允许局域网 HTTP 明文地址。

### MCP 地址怎么填

局域网 MCP 地址是：

```text
http://电脑IP:3000/mcp
```

如果你的 AI 客户端要求 HTTPS，局域网 HTTP 可能不能直接接入；这种情况请使用 Render 部署。
