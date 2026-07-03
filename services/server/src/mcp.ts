import type { Request, Response } from 'express';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import * as z from 'zod/v4';
import type { RoomStore } from './store.js';
import { toPublicRoom } from './types.js';

const textResult = (value: unknown) => ({
  content: [{ type: 'text' as const, text: JSON.stringify(value, null, 2) }]
});

export function createMcpServer(store: RoomStore): McpServer {
  const server = new McpServer({ name: 'tongpin-clean', version: '1.1.2' });

  server.registerTool('create_room', {
    title: '创建同频房间',
    description: '创建新的共同听歌房间，返回房间码与私密密钥。',
    inputSchema: {}
  }, async () => {
    const room = await store.create();
    return textResult({ ok: true, code: room.code, roomSecret: room.secret });
  });

  server.registerTool('get_room', {
    title: '读取同频房间',
    description: '读取当前歌曲、进度、播放状态、当前与下一句同步歌词、命令执行结果和听歌笔记。',
    inputSchema: {
      code: z.string().min(6),
      roomSecret: z.string().min(10)
    }
  }, async ({ code, roomSecret }) => {
    try {
      return textResult({ ok: true, room: toPublicRoom(store.authenticate(code, roomSecret)) });
    } catch {
      return textResult({ ok: false, error: '房间不存在或密钥错误' });
    }
  });

  server.registerTool('set_playback_command', {
    title: '控制同频播放',
    description: '向手机发送播放、暂停、上一首、下一首或跳转进度命令。',
    inputSchema: {
      code: z.string().min(6),
      roomSecret: z.string().min(10),
      command: z.enum(['play', 'pause', 'seek', 'next', 'previous']),
      positionMs: z.number().int().nonnegative().optional()
    }
  }, async ({ code, roomSecret, command, positionMs }) => {
    if (command === 'seek' && positionMs === undefined) {
      return textResult({ ok: false, error: 'seek 命令必须提供 positionMs' });
    }
    try {
      const room = await store.setCommand(code, roomSecret, { type: command, positionMs });
      return textResult({ ok: true, command: room.pendingCommand, result: room.lastCommandResult });
    } catch {
      return textResult({ ok: false, error: '房间不存在或密钥错误' });
    }
  });

  server.registerTool('add_listening_note', {
    title: '添加听歌笔记',
    description: '在当前歌曲与进度附近记录一句话。',
    inputSchema: {
      code: z.string().min(6),
      roomSecret: z.string().min(10),
      text: z.string().min(1).max(500),
      positionMs: z.number().int().nonnegative().optional()
    }
  }, async ({ code, roomSecret, text, positionMs }) => {
    try {
      const room = await store.addNote(code, roomSecret, text, positionMs);
      return textResult({ ok: true, note: room.notes.at(-1) });
    } catch {
      return textResult({ ok: false, error: '房间不存在或密钥错误' });
    }
  });

  return server;
}

export async function handleMcpRequest(store: RoomStore, req: Request, res: Response): Promise<void> {
  const server = createMcpServer(store);
  const transport = new StreamableHTTPServerTransport({
    sessionIdGenerator: undefined,
    enableJsonResponse: true
  });
  let closed = false;
  const close = async () => {
    if (closed) return;
    closed = true;
    await transport.close();
    await server.close();
  };
  res.once('finish', () => void close());
  res.once('close', () => void close());
  try {
    await server.connect(transport);
    await transport.handleRequest(req, res, req.body);
  } catch (error) {
    console.error('MCP request failed', error);
    if (!res.headersSent) {
      res.status(500).json({ jsonrpc: '2.0', error: { code: -32603, message: 'Internal server error' }, id: null });
    }
  }
}
