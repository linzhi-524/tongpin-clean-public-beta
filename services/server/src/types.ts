export type PlaybackCommandType = 'play' | 'pause' | 'seek' | 'next' | 'previous' | 'search_play';
export type CommandStatus = 'queued' | 'received' | 'executed' | 'failed';

export interface PlaybackCommand {
  id: string;
  type: PlaybackCommandType;
  positionMs?: number;
  query?: string;
  title?: string;
  artist?: string;
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
  playerName?: string;
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

export const playerNameOf = (packageName?: string): string => {
  switch (packageName) {
    case 'com.tencent.qqmusic':
      return 'QQ 音乐';
    case 'com.kugou.android':
      return '酷狗音乐';
    case 'com.netease.cloudmusic':
      return '网易云音乐';
    case undefined:
    case '':
      return '尚未识别';
    default:
      return packageName;
  }
};

const withPlayerName = (playback: PlaybackSnapshot): PlaybackSnapshot => ({
  ...playback,
  playerName: playback.playerName || playerNameOf(playback.packageName)
});

const projectPlaybackPosition = (playback: PlaybackSnapshot): PlaybackSnapshot => {
  if (!playback.playing || !playback.publishedAt) return withPlayerName(playback);
  const elapsed = Math.max(0, Math.min(5_000, Date.now() - playback.publishedAt));
  const positionMs = playback.durationMs > 0
    ? Math.min(playback.durationMs, playback.positionMs + elapsed)
    : playback.positionMs + elapsed;
  return withPlayerName({ ...playback, positionMs });
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
