export type PlaybackCommandType = 'play' | 'pause' | 'seek' | 'next' | 'previous';
export type CommandStatus = 'queued' | 'received' | 'executed' | 'failed';

export interface PlaybackCommand {
  id: string;
  type: PlaybackCommandType;
  positionMs?: number;
  createdAt: number;
}

export interface CommandResult {
  commandId: string;
  status: CommandStatus;
  message: string;
  updatedAt: number;
}

export interface PlaybackSnapshot {
  title: string;
  artist: string;
  album?: string;
  durationMs: number;
  positionMs: number;
  playing: boolean;
  packageName?: string;
  sourceUrl?: string;
  observedAt: number;
  publishedAt?: number;
  lyric?: string;
  nextLyric?: string;
  lyricsSource?: string;
  lyricsSynced?: boolean;
}

export interface ListeningNote {
  id: string;
  text: string;
  positionMs: number;
  trackTitle: string;
  createdAt: number;
}

export interface Room {
  code: string;
  secret: string;
  createdAt: number;
  updatedAt: number;
  revision: number;
  playback: PlaybackSnapshot | null;
  pendingCommand: PlaybackCommand | null;
  lastCommandResult: CommandResult | null;
  notes: ListeningNote[];
}

export type PublicRoom = Omit<Room, 'secret'>;

const projectPlaybackPosition = (playback: PlaybackSnapshot): PlaybackSnapshot => {
  if (!playback.playing || !playback.publishedAt) return { ...playback };
  const elapsed = Math.max(0, Math.min(5_000, Date.now() - playback.publishedAt));
  const positionMs = playback.durationMs > 0
    ? Math.min(playback.durationMs, playback.positionMs + elapsed)
    : playback.positionMs + elapsed;
  return { ...playback, positionMs };
};

export const toPublicRoom = (room: Room): PublicRoom => ({
  code: room.code,
  createdAt: room.createdAt,
  updatedAt: room.updatedAt,
  revision: room.revision,
  playback: room.playback ? projectPlaybackPosition(room.playback) : null,
  pendingCommand: room.pendingCommand,
  lastCommandResult: room.lastCommandResult,
  notes: room.notes
});
