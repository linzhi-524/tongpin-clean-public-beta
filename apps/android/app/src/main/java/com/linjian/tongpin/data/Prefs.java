package com.linjian.tongpin.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

public final class Prefs {
    private static final String NAME = "tongpin_clean";
    private static final String DEFAULT_SERVER = "https://your-service.onrender.com";

    private Prefs() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String normalizeServer(String value) {
        String server = value == null ? "" : value.trim();
        while (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        if (server.endsWith("/mcp")) server = server.substring(0, server.length() - 4);
        if (server.endsWith("/api")) server = server.substring(0, server.length() - 4);
        while (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        return server.isEmpty() ? DEFAULT_SERVER : server;
    }

    public static String server(Context context) {
        return prefs(context).getString("server", DEFAULT_SERVER);
    }

    public static void saveServer(Context context, String value) {
        prefs(context).edit().putString("server", normalizeServer(value)).apply();
    }


    public static String theme(Context context) {
        return prefs(context).getString("theme", "cream");
    }

    public static void saveTheme(Context context, String value) {
        String clean = value == null ? "cream" : value.trim();
        prefs(context).edit().putString("theme", clean.isEmpty() ? "cream" : clean).apply();
    }

    public static RoomCredentials room(Context context) {
        return new RoomCredentials(
                prefs(context).getString("room_code", ""),
                prefs(context).getString("room_secret", "")
        );
    }

    public static void saveRoom(Context context, RoomCredentials room) {
        prefs(context).edit()
                .putString("room_code", room.code.toUpperCase(Locale.ROOT))
                .putString("room_secret", room.secret)
                .apply();
    }

    public static void clearRoom(Context context) {
        prefs(context).edit()
                .remove("room_code")
                .remove("room_secret")
                .remove("last_command_id")
                .remove("last_command_status")
                .apply();
    }


    public static boolean backgroundSyncEnabled(Context context) {
        return prefs(context).getBoolean("background_sync_enabled", true);
    }

    public static void saveBackgroundSyncEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("background_sync_enabled", enabled).apply();
    }


    public static boolean qqLyricsEnabled(Context context) {
        return prefs(context).getBoolean("qq_lyrics_enabled", false);
    }

    public static void saveQqLyricsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("qq_lyrics_enabled", enabled).apply();
    }

    public static boolean ocrLyricsEnabled(Context context) {
        return prefs(context).getBoolean("ocr_lyrics_enabled", false);
    }

    public static void saveOcrLyricsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("ocr_lyrics_enabled", enabled).apply();
    }

    public static LiveLyricsSnapshot liveLyrics(Context context) {
        SharedPreferences p = prefs(context);
        return new LiveLyricsSnapshot(
                p.getString("live_lyrics_track_key", ""),
                p.getString("live_lyrics_current", ""),
                p.getString("live_lyrics_next", ""),
                p.getString("live_lyrics_source", ""),
                p.getLong("live_lyrics_observed", 0L)
        );
    }

    public static void saveLiveLyrics(
            Context context,
            String trackKey,
            String current,
            String next,
            String source,
            long observedAt
    ) {
        prefs(context).edit()
                .putString("live_lyrics_track_key", trackKey == null ? "" : trackKey)
                .putString("live_lyrics_current", current == null ? "" : current)
                .putString("live_lyrics_next", next == null ? "" : next)
                .putString("live_lyrics_source", source == null ? "" : source)
                .putLong("live_lyrics_observed", observedAt)
                .apply();
    }

    public static void clearLiveLyrics(Context context) {
        prefs(context).edit()
                .remove("live_lyrics_track_key")
                .remove("live_lyrics_current")
                .remove("live_lyrics_next")
                .remove("live_lyrics_source")
                .remove("live_lyrics_observed")
                .apply();
    }

    public static String sourceUrl(Context context) {
        return prefs(context).getString("source_url", "");
    }

    public static void saveSourceUrl(Context context, String value) {
        String clean = value == null ? "" : value.trim();
        SharedPreferences.Editor editor = prefs(context).edit().putString("source_url", clean);
        if (clean.isEmpty()) editor.remove("source_track_key");
        editor.apply();
    }

    public static String sourceTrackKey(Context context) {
        return prefs(context).getString("source_track_key", "");
    }

    public static void bindSourceToCurrentTrack(Context context) {
        PlaybackSnapshot playback = playback(context);
        prefs(context).edit().putString("source_track_key", playback.sourceKey()).apply();
    }

    public static String sourceUrlForTrack(Context context, String sourceKey) {
        String url = sourceUrl(context);
        if (url.isEmpty()) return "";
        String bound = sourceTrackKey(context);
        return !bound.isEmpty() && bound.equals(sourceKey) ? url : "";
    }

    public static String status(Context context) {
        return prefs(context).getString("status", "尚未连接");
    }

    public static void saveStatus(Context context, String value) {
        prefs(context).edit().putString("status", value).apply();
    }

    public static long lastSync(Context context) {
        return prefs(context).getLong("last_sync", 0L);
    }

    public static void saveLastSync(Context context, long value) {
        prefs(context).edit().putLong("last_sync", value).apply();
    }

    public static long lastPlaybackPublish(Context context) {
        return prefs(context).getLong("last_playback_publish", 0L);
    }

    public static void saveLastPlaybackPublish(Context context, long value) {
        prefs(context).edit().putLong("last_playback_publish", value).apply();
    }

    public static String lastCommandId(Context context) {
        return prefs(context).getString("last_command_id", "");
    }

    public static void saveLastCommandId(Context context, String value) {
        prefs(context).edit().putString("last_command_id", value).apply();
    }

    public static String lastCommandStatus(Context context) {
        return prefs(context).getString("last_command_status", "");
    }

    public static void saveLastCommandStatus(Context context, String value) {
        prefs(context).edit().putString("last_command_status", value).apply();
    }

    public static String lastCommandResult(Context context) {
        return prefs(context).getString("last_command_result", "尚无命令");
    }

    public static void saveLastCommandResult(Context context, String value) {
        prefs(context).edit().putString("last_command_result", value).apply();
    }

    public static PlaybackSnapshot playback(Context context) {
        SharedPreferences p = prefs(context);
        return new PlaybackSnapshot(
                p.getString("pb_title", "等待播放器"),
                p.getString("pb_artist", "请先在 QQ 音乐、酷狗音乐或网易云音乐播放一首歌"),
                p.getString("pb_album", ""),
                p.getLong("pb_duration", 0L),
                p.getLong("pb_position", 0L),
                p.getBoolean("pb_playing", false),
                p.getString("pb_package", ""),
                p.getString("pb_source", ""),
                p.getLong("pb_observed", 0L),
                p.getString("pb_lyric", ""),
                p.getString("pb_next_lyric", ""),
                p.getString("pb_lyrics_source", ""),
                p.getBoolean("pb_lyrics_synced", false)
        );
    }

    public static void savePlayback(Context context, PlaybackSnapshot value) {
        prefs(context).edit()
                .putString("pb_title", value.title)
                .putString("pb_artist", value.artist)
                .putString("pb_album", value.album)
                .putLong("pb_duration", value.durationMs)
                .putLong("pb_position", value.positionMs)
                .putBoolean("pb_playing", value.playing)
                .putString("pb_package", value.packageName)
                .putString("pb_source", value.sourceUrl)
                .putLong("pb_observed", value.observedAt)
                .putString("pb_lyric", value.lyric)
                .putString("pb_next_lyric", value.nextLyric)
                .putString("pb_lyrics_source", value.lyricsSource)
                .putBoolean("pb_lyrics_synced", value.lyricsSynced)
                .apply();
    }
}
