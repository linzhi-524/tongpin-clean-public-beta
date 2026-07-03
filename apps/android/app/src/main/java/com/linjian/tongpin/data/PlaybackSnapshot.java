package com.linjian.tongpin.data;

public final class PlaybackSnapshot {
    public final String title;
    public final String artist;
    public final String album;
    public final long durationMs;
    public final long positionMs;
    public final boolean playing;
    public final String packageName;
    public final String sourceUrl;
    public final long observedAt;
    public final String lyric;
    public final String nextLyric;
    public final String lyricsSource;
    public final boolean lyricsSynced;

    public PlaybackSnapshot(
            String title,
            String artist,
            String album,
            long durationMs,
            long positionMs,
            boolean playing,
            String packageName,
            String sourceUrl,
            long observedAt,
            String lyric,
            String nextLyric,
            String lyricsSource,
            boolean lyricsSynced
    ) {
        this.title = title == null ? "等待播放器" : title;
        this.artist = artist == null ? "请先在 QQ 音乐、酷狗音乐或网易云音乐播放一首歌" : artist;
        this.album = album == null ? "" : album;
        this.durationMs = Math.max(0L, durationMs);
        this.positionMs = Math.max(0L, positionMs);
        this.playing = playing;
        this.packageName = packageName == null ? "" : packageName;
        this.sourceUrl = sourceUrl == null ? "" : sourceUrl;
        this.observedAt = observedAt;
        this.lyric = lyric == null ? "" : lyric;
        this.nextLyric = nextLyric == null ? "" : nextLyric;
        this.lyricsSource = lyricsSource == null ? "" : lyricsSource;
        this.lyricsSynced = lyricsSynced;
    }

    public static PlaybackSnapshot empty() {
        return new PlaybackSnapshot(
                "等待播放器",
                "请先在 QQ 音乐、酷狗音乐或网易云音乐播放一首歌",
                "",
                0L,
                0L,
                false,
                "",
                "",
                0L,
                "",
                "",
                "",
                false
        );
    }

    public String trackKey() {
        return sourceKey() + "\u0000" + durationMs;
    }

    public String sourceKey() {
        return title.trim() + "\u0000" + artist.trim() + "\u0000" + album.trim();
    }
}
