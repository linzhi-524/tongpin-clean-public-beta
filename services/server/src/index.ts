import { createServer } from 'node:http';
import cors from 'cors';
import express from 'express';
import { handleMcpRequest } from './mcp.js';
import { RoomStore } from './store.js';
import { toPublicRoom, type CommandStatus, type PlaybackCommandType, type PlaybackSnapshot } from './types.js';

const port = Number(process.env.PORT ?? 3000);
const dataFile = process.env.DATA_FILE ?? './data/rooms.json';
const store = new RoomStore(dataFile);
await store.load();

const app = express();
app.disable('x-powered-by');
app.use(cors());
app.use(express.json({ limit: '128kb' }));
app.use(express.static(new URL('../public', import.meta.url).pathname));

const secretOf = (req: express.Request): string => {
  const auth = req.header('authorization') ?? '';
  return auth.startsWith('Bearer ') ? auth.slice(7) : '';
};

const text = (value: unknown, limit: number): string | undefined => {
  if (value === undefined || value === null) return undefined;
  return String(value).slice(0, limit);
};

app.get('/', (_req, res) => res.redirect('/control'));
app.get('/control', (_req, res) => res.sendFile(new URL('../public/control.html', import.meta.url).pathname));
app.get('/health', (_req, res) => res.json({ ok: true, service: 'tongpin-clean', version: '1.1.2' }));

app.post('/api/rooms', async (_req, res) => {
  const room = await store.create();
  res.status(201).json({ code: room.code, roomSecret: room.secret, room: toPublicRoom(room) });
});

app.get('/api/rooms/:code', (req, res) => {
  try {
    res.json(toPublicRoom(store.authenticate(req.params.code, secretOf(req))));
  } catch {
    res.status(404).json({ error: 'ROOM_NOT_FOUND_OR_SECRET_INVALID' });
  }
});

app.post('/api/rooms/:code/playback', async (req, res) => {
  try {
    const body = req.body as Partial<PlaybackSnapshot>;
    if (!body.title || !body.artist || !Number.isFinite(body.positionMs) || !Number.isFinite(body.durationMs)) {
      res.status(400).json({ error: 'INVALID_PLAYBACK' });
      return;
    }
    const room = await store.publishPlayback(req.params.code, secretOf(req), {
      title: String(body.title).slice(0, 200),
      artist: String(body.artist).slice(0, 200),
      album: text(body.album, 200),
      durationMs: Math.max(0, Math.trunc(body.durationMs!)),
      positionMs: Math.max(0, Math.trunc(body.positionMs!)),
      playing: Boolean(body.playing),
      packageName: text(body.packageName, 120),
      sourceUrl: text(body.sourceUrl, 2_000),
      observedAt: Number.isFinite(body.observedAt) ? Number(body.observedAt) : Date.now(),
      publishedAt: Date.now(),
      lyric: text(body.lyric, 500) ?? '',
      nextLyric: text(body.nextLyric, 500) ?? '',
      lyricsSource: text(body.lyricsSource, 120) ?? '',
      lyricsSynced: Boolean(body.lyricsSynced)
    });
    res.json(toPublicRoom(room));
  } catch {
    res.status(404).json({ error: 'ROOM_NOT_FOUND_OR_SECRET_INVALID' });
  }
});

app.post('/api/rooms/:code/commands', async (req, res) => {
  try {
    const type = req.body?.type as PlaybackCommandType;
    if (!['play', 'pause', 'seek', 'next', 'previous'].includes(type)) {
      res.status(400).json({ error: 'INVALID_COMMAND' });
      return;
    }
    const positionMs = type === 'seek' ? Math.max(0, Math.trunc(Number(req.body.positionMs))) : undefined;
    if (type === 'seek' && !Number.isFinite(positionMs)) {
      res.status(400).json({ error: 'INVALID_POSITION' });
      return;
    }
    const room = await store.setCommand(req.params.code, secretOf(req), { type, positionMs });
    res.json(toPublicRoom(room));
  } catch {
    res.status(404).json({ error: 'ROOM_NOT_FOUND_OR_SECRET_INVALID' });
  }
});

app.post('/api/rooms/:code/commands/:id/ack', async (req, res) => {
  try {
    const status = req.body?.status as CommandStatus;
    if (!['received', 'executed', 'failed'].includes(status)) {
      res.status(400).json({ error: 'INVALID_STATUS' });
      return;
    }
    const message = String(req.body?.message ?? '').trim() || status;
    const room = await store.acknowledgeCommand(req.params.code, secretOf(req), req.params.id, status, message);
    res.json(toPublicRoom(room));
  } catch {
    res.status(404).json({ error: 'ROOM_NOT_FOUND_OR_SECRET_INVALID' });
  }
});

app.post('/api/rooms/:code/notes', async (req, res) => {
  try {
    const noteText = String(req.body?.text ?? '').trim();
    if (!noteText || noteText.length > 500) {
      res.status(400).json({ error: 'INVALID_NOTE' });
      return;
    }
    const positionMs = req.body?.positionMs === undefined ? undefined : Math.max(0, Math.trunc(Number(req.body.positionMs)));
    const room = await store.addNote(req.params.code, secretOf(req), noteText, positionMs);
    res.json(toPublicRoom(room));
  } catch {
    res.status(404).json({ error: 'ROOM_NOT_FOUND_OR_SECRET_INVALID' });
  }
});

app.post('/mcp', (req, res) => void handleMcpRequest(store, req, res));
app.get('/mcp', (_req, res) => res.status(405).json({ error: 'POST_ONLY' }));
app.delete('/mcp', (_req, res) => res.status(405).json({ error: 'POST_ONLY' }));

createServer(app).listen(port, '0.0.0.0', () => {
  console.log(`Tongpin Clean listening on http://0.0.0.0:${port}`);
});
