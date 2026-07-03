package com.linjian.tongpin.data;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RoomApi {
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    private RoomApi() {}

    public interface Callback<T> {
        void onSuccess(T value);
        void onError(Throwable error);
    }

    private static JSONObject request(String method, String url, String secret, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("Accept", "application/json");
        if (secret != null && !secret.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + secret);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }

        try {
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code <= 299
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String text = "";
            if (stream != null) {
                try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                    text = output.toString(StandardCharsets.UTF_8.name());
                }
            }
            if (code < 200 || code > 299) {
                throw new IOException("HTTP " + code + (text.isEmpty() ? "" : ": " + text));
            }
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } finally {
            connection.disconnect();
        }
    }

    public static void createRoom(Context context, String serverInput, Callback<RoomCredentials> callback) {
        EXECUTOR.execute(() -> {
            try {
                String server = Prefs.normalizeServer(serverInput);
                JSONObject json = request("POST", server + "/api/rooms", null, new JSONObject());
                RoomCredentials room = new RoomCredentials(json.getString("code"), json.getString("roomSecret"));
                Prefs.saveServer(context, server);
                Prefs.saveRoom(context, room);
                Prefs.saveStatus(context, "房间已创建，等待媒体服务连接");
                callback.onSuccess(room);
            } catch (Throwable error) {
                callback.onError(error);
            }
        });
    }

    public static void health(String serverInput, Callback<String> callback) {
        EXECUTOR.execute(() -> {
            try {
                String server = Prefs.normalizeServer(serverInput);
                JSONObject json = request("GET", server + "/health", null, null);
                callback.onSuccess(json.optString("version", "ok"));
            } catch (Throwable error) {
                callback.onError(error);
            }
        });
    }

    public static JSONObject getRoomSync(Context context) throws Exception {
        String server = Prefs.server(context);
        RoomCredentials room = Prefs.room(context);
        if (room.code.isEmpty() || room.secret.isEmpty()) throw new IOException("尚未创建房间");
        return request("GET", server + "/api/rooms/" + room.code, room.secret, null);
    }

    public static void publishPlaybackSync(Context context, PlaybackSnapshot snapshot) throws Exception {
        String server = Prefs.server(context);
        RoomCredentials room = Prefs.room(context);
        if (room.code.isEmpty() || room.secret.isEmpty()) return;
        JSONObject body = new JSONObject()
                .put("title", snapshot.title)
                .put("artist", snapshot.artist)
                .put("album", snapshot.album)
                .put("durationMs", snapshot.durationMs)
                .put("positionMs", snapshot.positionMs)
                .put("playing", snapshot.playing)
                .put("packageName", snapshot.packageName)
                .put("sourceUrl", snapshot.sourceUrl)
                .put("observedAt", snapshot.observedAt)
                .put("lyric", snapshot.lyric)
                .put("nextLyric", snapshot.nextLyric)
                .put("lyricsSource", snapshot.lyricsSource)
                .put("lyricsSynced", snapshot.lyricsSynced);
        request("POST", server + "/api/rooms/" + room.code + "/playback", room.secret, body);
    }

    public static void acknowledgeCommandSync(Context context, String commandId, String status, String message) throws Exception {
        String server = Prefs.server(context);
        RoomCredentials room = Prefs.room(context);
        if (room.code.isEmpty() || room.secret.isEmpty()) return;
        JSONObject body = new JSONObject().put("status", status).put("message", message);
        request(
                "POST",
                server + "/api/rooms/" + room.code + "/commands/" + commandId + "/ack",
                room.secret,
                body
        );
    }
}
