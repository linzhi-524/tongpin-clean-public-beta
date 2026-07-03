import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { customAlphabet, nanoid } from 'nanoid';
import type { CommandResult, CommandStatus, ListeningNote, PlaybackCommand, PlaybackSnapshot, Room } from './types.js';

const roomCode = customAlphabet('ABCDEFGHJKLMNPQRSTUVWXYZ23456789', 6);

export class RoomStore {
  private rooms = new Map<string, Room>();
  private writeQueue: Promise<void> = Promise.resolve();

  constructor(private readonly filePath: string) {}

  async load(): Promise<void> {
    try {
      const raw = await readFile(this.filePath, 'utf8');
      const parsed = JSON.parse(raw) as Room[];
      this.rooms = new Map(parsed.map(room => [room.code, room]));
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
    }
  }

  private persist(): Promise<void> {
    this.writeQueue = this.writeQueue.then(async () => {
      await mkdir(dirname(this.filePath), { recursive: true });
      const temp = `${this.filePath}.tmp`;
      await writeFile(temp, JSON.stringify([...this.rooms.values()], null, 2), 'utf8');
      await rename(temp, this.filePath);
    });
    return this.writeQueue;
  }

  async create(): Promise<Room> {
    let code = roomCode();
    while (this.rooms.has(code)) code = roomCode();
    const now = Date.now();
    const room: Room = {
      code,
      secret: nanoid(32),
      createdAt: now,
      updatedAt: now,
      revision: 0,
      playback: null,
      pendingCommand: null,
      lastCommandResult: null,
      notes: []
    };
    this.rooms.set(code, room);
    await this.persist();
    return room;
  }

  authenticate(code: string, secret: string): Room {
    const room = this.rooms.get(code.toUpperCase());
    if (!room || room.secret !== secret) throw new Error('ROOM_NOT_FOUND_OR_SECRET_INVALID');
    return room;
  }

  async publishPlayback(code: string, secret: string, snapshot: PlaybackSnapshot): Promise<Room> {
    const room = this.authenticate(code, secret);
    room.playback = snapshot;
    room.revision += 1;
    room.updatedAt = Date.now();
    await this.persist();
    return room;
  }

  async setCommand(code: string, secret: string, command: Omit<PlaybackCommand, 'id' | 'createdAt'>): Promise<Room> {
    const room = this.authenticate(code, secret);
    const createdAt = Date.now();
    room.pendingCommand = { ...command, id: nanoid(12), createdAt };
    room.lastCommandResult = {
      commandId: room.pendingCommand.id,
      status: 'queued',
      message: '命令已写入服务器，等待手机领取',
      updatedAt: createdAt
    };
    room.revision += 1;
    room.updatedAt = createdAt;
    await this.persist();
    return room;
  }

  async acknowledgeCommand(code: string, secret: string, commandId: string, status: CommandStatus, message: string): Promise<Room> {
    const room = this.authenticate(code, secret);
    const result: CommandResult = {
      commandId,
      status,
      message: message.slice(0, 300),
      updatedAt: Date.now()
    };
    room.lastCommandResult = result;
    if (room.pendingCommand?.id === commandId && (status === 'executed' || status === 'failed')) {
      room.pendingCommand = null;
    }
    room.revision += 1;
    room.updatedAt = result.updatedAt;
    await this.persist();
    return room;
  }

  async addNote(code: string, secret: string, text: string, positionMs?: number): Promise<Room> {
    const room = this.authenticate(code, secret);
    const note: ListeningNote = {
      id: nanoid(12),
      text: text.trim(),
      positionMs: positionMs ?? room.playback?.positionMs ?? 0,
      trackTitle: room.playback?.title ?? '未知歌曲',
      createdAt: Date.now()
    };
    room.notes.push(note);
    room.notes = room.notes.slice(-200);
    room.revision += 1;
    room.updatedAt = Date.now();
    await this.persist();
    return room;
  }
}
