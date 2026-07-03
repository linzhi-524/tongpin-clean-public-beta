package com.linjian.tongpin.media;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.KeyEvent;

import com.linjian.tongpin.data.LiveLyricsSnapshot;
import com.linjian.tongpin.data.PlaybackSnapshot;
import com.linjian.tongpin.data.Prefs;
import com.linjian.tongpin.data.RemoteCommand;
import com.linjian.tongpin.data.RoomApi;
import com.linjian.tongpin.data.RoomCredentials;
import com.linjian.tongpin.lyrics.LyricsRepository;

import org.json.JSONObject;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class TongpinNotificationListener extends NotificationListenerService {
    private static final long POLL_INTERVAL_MS = 700L;
    private static final long POSITION_PUBLISH_STEP_MS = 1_200L;
    private static final long HEARTBEAT_PUBLISH_MS = 4_000L;
    private static final long[] VERIFY_DELAYS_MS = {320L, 520L, 780L, 1_150L, 1_700L, 2_400L, 3_200L};
    private static volatile TongpinNotificationListener instance;

    private MediaSessionManager sessionManager;
    private MediaController controller;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService pollNetwork = Executors.newSingleThreadExecutor();
    private final ExecutorService publishNetwork = Executors.newSingleThreadExecutor();
    private final ExecutorService commandNetwork = Executors.newSingleThreadExecutor();
    private final LyricsRepository lyricsRepository = new LyricsRepository();
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private final AtomicBoolean publishing = new AtomicBoolean(false);
    private final AtomicBoolean localCommandInFlight = new AtomicBoolean(false);
    private final AtomicReference<PlaybackSnapshot> latestToPublish = new AtomicReference<>();
    private final AtomicReference<PlaybackSnapshot> lastPublished = new AtomicReference<>();
    private final AtomicReference<String> lastPublishedRoomCode = new AtomicReference<>("");
    private final AtomicReference<String> commandInFlight = new AtomicReference<>("");

    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            captureAndPublish(true);
            scheduleCaptureBurst();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            captureAndPublish(true);
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionListener = this::refreshController;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling.compareAndSet(false, true)) {
                mainHandler.postDelayed(this, POLL_INTERVAL_MS);
                return;
            }
            pollNetwork.execute(() -> {
                try {
                    RoomCredentials room = Prefs.room(TongpinNotificationListener.this);
                    if (room.code.isEmpty()) {
                        Prefs.saveStatus(TongpinNotificationListener.this, "等待创建房间");
                    } else {
                        JSONObject json = RoomApi.getRoomSync(TongpinNotificationListener.this);
                        Prefs.saveLastSync(TongpinNotificationListener.this, System.currentTimeMillis());
                        if (commandInFlight.get().isEmpty() && !localCommandInFlight.get()) {
                            Prefs.saveStatus(TongpinNotificationListener.this, "服务器已连接 · 实时同步中");
                        }
                        JSONObject pending = json.optJSONObject("pendingCommand");
                        if (pending != null) handlePendingCommand(pending);
                        mainHandler.post(() -> captureAndPublish(false));
                    }
                } catch (Throwable error) {
                    Prefs.saveStatus(TongpinNotificationListener.this, "连接失败：" + safeMessage(error));
                } finally {
                    polling.set(false);
                    mainHandler.postDelayed(this, POLL_INTERVAL_MS);
                }
            });
        }
    };


    public static boolean isConnected() {
        return instance != null;
    }

    public static boolean requestImmediateRefresh() {
        TongpinNotificationListener service = instance;
        if (service == null) return false;
        service.mainHandler.post(() -> {
            service.refreshActiveController();
            service.captureAndPublish(true);
            service.scheduleCaptureBurst();
        });
        return true;
    }

    public static boolean requestLyricsRetry() {
        TongpinNotificationListener service = instance;
        if (service == null) return false;
        service.mainHandler.post(() -> {
            PlaybackSnapshot current = Prefs.playback(service);
            service.lyricsRepository.invalidate(current.trackKey());
            service.captureAndPublish(true);
            service.scheduleCaptureBurst();
        });
        return true;
    }

    public static boolean requestLocalCommand(String type, Long positionMs) {
        TongpinNotificationListener service = instance;
        if (service == null) return false;
        if (!service.localCommandInFlight.compareAndSet(false, true)) {
            Prefs.saveStatus(service, "上一条控制仍在处理中");
            return true;
        }
        RemoteCommand command = new RemoteCommand(
                "local-" + System.currentTimeMillis(),
                type == null ? "" : type,
                positionMs
        );
        service.mainHandler.post(() -> service.startCommandExecution(command, false));
        return true;
    }

    public static Bitmap currentArtwork() {
        TongpinNotificationListener service = instance;
        if (service == null || service.controller == null) return null;
        MediaMetadata metadata = service.controller.getMetadata();
        if (metadata == null) return null;
        Bitmap value = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (value == null) value = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (value == null) value = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return value;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        sessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        ComponentName component = new ComponentName(this, getClass());
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionListener, component);
            refreshController(sessionManager.getActiveSessions(component));
        } catch (SecurityException error) {
            Prefs.saveStatus(this, "请重新授权通知使用权");
        }
        Prefs.saveStatus(this, "通知服务已连接 · 实时同步中");
        mainHandler.removeCallbacks(pollRunnable);
        mainHandler.post(pollRunnable);
    }

    @Override
    public void onListenerDisconnected() {
        if (instance == this) instance = null;
        Prefs.saveStatus(this, "通知服务已断开，请重新授权");
        mainHandler.removeCallbacks(pollRunnable);
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        mainHandler.removeCallbacksAndMessages(null);
        if (controller != null) controller.unregisterCallback(mediaCallback);
        if (sessionManager != null) sessionManager.removeOnActiveSessionsChangedListener(sessionListener);
        pollNetwork.shutdownNow();
        publishNetwork.shutdownNow();
        commandNetwork.shutdownNow();
        lyricsRepository.shutdown();
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        if (sbn != null && (PlayerCatalog.isSupported(sbn.getPackageName()) || PlayerCatalog.isMusicLike(sbn.getPackageName()))) {
            refreshActiveController();
            captureAndPublish(true);
            scheduleCaptureBurst();
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        if (sbn != null && (PlayerCatalog.isSupported(sbn.getPackageName()) || PlayerCatalog.isMusicLike(sbn.getPackageName()))) {
            refreshActiveController();
            captureAndPublish(true);
        }
    }

    private void refreshActiveController() {
        if (sessionManager == null) return;
        try {
            refreshController(sessionManager.getActiveSessions(new ComponentName(this, getClass())));
        } catch (SecurityException ignored) {
            Prefs.saveStatus(this, "请重新授权通知使用权");
        }
    }

    private void refreshController(List<MediaController> controllers) {
        List<MediaController> list = controllers == null ? Collections.emptyList() : controllers;
        MediaController next = chooseBestController(list);

        if (next != null && controller != null && next.getSessionToken().equals(controller.getSessionToken())) {
            return;
        }

        if (controller != null) controller.unregisterCallback(mediaCallback);
        controller = next;
        if (controller != null) controller.registerCallback(mediaCallback, mainHandler);
        captureAndPublish(true);
    }

    private MediaController chooseBestController(List<MediaController> list) {
        MediaController supported = null;
        MediaController musicLike = null;
        MediaController playingMusicLike = null;

        for (MediaController candidate : list) {
            String packageName = candidate.getPackageName();
            if (PlayerCatalog.isSupported(packageName)) {
                if (isPlaying(candidate)) return candidate;
                if (supported == null) supported = candidate;
            } else if (PlayerCatalog.isMusicLike(packageName)) {
                if (isPlaying(candidate) && playingMusicLike == null) playingMusicLike = candidate;
                if (musicLike == null) musicLike = candidate;
            }
        }

        if (supported != null) return supported;
        if (playingMusicLike != null) return playingMusicLike;
        if (musicLike != null) return musicLike;
        return list.isEmpty() ? null : list.get(0);
    }

    private static boolean isPlaying(MediaController candidate) {
        if (candidate == null) return false;
        PlaybackState state = candidate.getPlaybackState();
        return state != null && state.getState() == PlaybackState.STATE_PLAYING;
    }

    private void captureAndPublish(boolean force) {
        PlaybackSnapshot snapshot = buildSnapshot();
        Prefs.savePlayback(this, snapshot);

        RoomCredentials room = Prefs.room(this);
        if (room.code.isEmpty() || room.secret.isEmpty()) return;
        if (!shouldPublish(room.code, snapshot, force)) return;

        latestToPublish.set(snapshot);
        drainPublishQueue();
    }

    private PlaybackSnapshot buildSnapshot() {
        MediaController current = controller;
        if (current == null) {
            return new PlaybackSnapshot(
                    "等待播放器",
                    PlayerCatalog.PLAYER_PROMPT,
                    "",
                    0L,
                    0L,
                    false,
                    "",
                    "",
                    System.currentTimeMillis(),
                    "",
                    "",
                    "等待同步歌词",
                    false
            );
        }

        MediaMetadata metadata = current.getMetadata();
        PlaybackState state = current.getPlaybackState();
        PlaybackSnapshot previous = Prefs.playback(this);

        String title = metadataText(metadata, MediaMetadata.METADATA_KEY_TITLE);
        if (title.isEmpty()) title = metadataText(metadata, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String artist = metadataText(metadata, MediaMetadata.METADATA_KEY_ARTIST);
        if (artist.isEmpty()) artist = metadataText(metadata, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (artist.isEmpty()) artist = metadataText(metadata, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        String album = metadataText(metadata, MediaMetadata.METADATA_KEY_ALBUM);
        long duration = metadata == null ? 0L : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);

        if (title.isEmpty() && current.getPackageName().equals(previous.packageName)) title = previous.title;
        if (artist.isEmpty() && current.getPackageName().equals(previous.packageName)) artist = previous.artist;
        if (album.isEmpty() && current.getPackageName().equals(previous.packageName)) album = previous.album;
        if (duration <= 0L && current.getPackageName().equals(previous.packageName)) duration = previous.durationMs;

        String safeTitle = title.isEmpty() ? "未知歌曲" : title;
        String safeArtist = artist.isEmpty() ? "未知歌手" : artist;
        long position = livePosition(state, duration);
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;

        PlaybackSnapshot identity = new PlaybackSnapshot(
                safeTitle,
                safeArtist,
                album,
                duration,
                position,
                playing,
                current.getPackageName(),
                "",
                System.currentTimeMillis(),
                "",
                "",
                "",
                false
        );
        String trackKey = identity.trackKey();
        lyricsRepository.ensure(
                trackKey,
                safeTitle,
                safeArtist,
                album,
                duration,
                () -> mainHandler.post(() -> captureAndPublish(true))
        );
        LyricsRepository.Snapshot libraryLyric = lyricsRepository.at(trackKey, position);
        LiveLyricsSnapshot liveLyric = Prefs.liveLyrics(this);
        boolean useLiveLyric = Prefs.qqLyricsEnabled(this)
                && PlayerCatalog.isQqMusic(current.getPackageName())
                && liveLyric.matches(trackKey)
                && liveLyric.isFresh(System.currentTimeMillis(), 12_000L)
                && liveLyric.hasText();

        String lyricCurrent = useLiveLyric ? liveLyric.current : libraryLyric.current;
        String lyricNext = useLiveLyric ? liveLyric.next : libraryLyric.next;
        String lyricSource = useLiveLyric ? liveLyric.source : libraryLyric.source;
        boolean lyricSynced = useLiveLyric || libraryLyric.synced;

        return new PlaybackSnapshot(
                safeTitle,
                safeArtist,
                album,
                duration,
                position,
                playing,
                current.getPackageName(),
                Prefs.sourceUrlForTrack(this, identity.sourceKey()),
                System.currentTimeMillis(),
                lyricCurrent,
                lyricNext,
                lyricSource,
                lyricSynced
        );
    }

    private boolean shouldPublish(String roomCode, PlaybackSnapshot next, boolean force) {
        if (force) return true;
        if (!roomCode.equals(lastPublishedRoomCode.get())) return true;
        PlaybackSnapshot previous = lastPublished.get();
        if (previous == null) return true;
        if (!Objects.equals(previous.trackKey(), next.trackKey())) return true;
        if (previous.playing != next.playing) return true;
        if (previous.durationMs != next.durationMs) return true;
        if (!Objects.equals(previous.packageName, next.packageName)) return true;
        if (!Objects.equals(previous.sourceUrl, next.sourceUrl)) return true;
        if (!Objects.equals(previous.lyric, next.lyric)) return true;
        if (!Objects.equals(previous.nextLyric, next.nextLyric)) return true;
        if (!Objects.equals(previous.lyricsSource, next.lyricsSource)) return true;
        if (previous.lyricsSynced != next.lyricsSynced) return true;
        if (Math.abs(previous.positionMs - next.positionMs) >= POSITION_PUBLISH_STEP_MS) return true;
        return next.observedAt - previous.observedAt >= HEARTBEAT_PUBLISH_MS;
    }

    private void drainPublishQueue() {
        if (!publishing.compareAndSet(false, true)) return;
        publishNetwork.execute(() -> {
            try {
                while (true) {
                    PlaybackSnapshot next = latestToPublish.getAndSet(null);
                    if (next == null) break;
                    try {
                        RoomCredentials room = Prefs.room(TongpinNotificationListener.this);
                        if (room.code.isEmpty() || room.secret.isEmpty()) continue;
                        RoomApi.publishPlaybackSync(TongpinNotificationListener.this, next);
                        lastPublished.set(next);
                        lastPublishedRoomCode.set(room.code);
                        Prefs.saveLastPlaybackPublish(TongpinNotificationListener.this, System.currentTimeMillis());
                    } catch (Throwable error) {
                        Prefs.saveStatus(TongpinNotificationListener.this, "状态上报失败：" + safeMessage(error));
                    }
                }
            } finally {
                publishing.set(false);
                if (latestToPublish.get() != null) drainPublishQueue();
            }
        });
    }

    private void handlePendingCommand(JSONObject json) {
        String id = json.optString("id", "");
        if (id.isEmpty()) return;

        String lastId = Prefs.lastCommandId(this);
        String lastStatus = Prefs.lastCommandStatus(this);
        if (id.equals(lastId) && ("executed".equals(lastStatus) || "failed".equals(lastStatus))) {
            retryFinalAcknowledgement(id, lastStatus, Prefs.lastCommandResult(this));
            return;
        }
        if (id.equals(lastId)) {
            Prefs.saveLastCommandId(this, "");
            Prefs.saveLastCommandStatus(this, "");
        }
        if (!commandInFlight.compareAndSet("", id)) return;

        Long positionMs = json.has("positionMs") && !json.isNull("positionMs")
                ? json.optLong("positionMs")
                : null;
        RemoteCommand command = new RemoteCommand(id, json.optString("type", ""), positionMs);

        commandNetwork.execute(() -> {
            try {
                RoomApi.acknowledgeCommandSync(
                        TongpinNotificationListener.this,
                        id,
                        "received",
                        "手机已收到命令，播放器处理中"
                );
            } catch (Throwable ignored) {
            }
        });
        mainHandler.post(() -> startCommandExecution(command, true));
    }

    private void startCommandExecution(RemoteCommand command, boolean remote) {
        refreshActiveController();
        PlaybackSnapshot before = buildSnapshot();
        if (isCommandSatisfied(command, before, before)) {
            finishCommand(command, remote, true, successMessage(command));
            return;
        }

        boolean sent = dispatchCommand(command, false);
        if (!sent) {
            finishCommand(command, remote, false, "未找到可控制的媒体会话");
            return;
        }
        Prefs.saveStatus(this, "播放器处理中 · " + commandLabel(command.type));
        captureAndPublish(true);
        verifyCommand(command, before, remote, 0, false);
    }

    private void verifyCommand(
            RemoteCommand command,
            PlaybackSnapshot before,
            boolean remote,
            int attempt,
            boolean retried
    ) {
        if (attempt >= VERIFY_DELAYS_MS.length) {
            finishCommand(command, remote, false, "播放器未响应，请回到当前播放器后重试");
            return;
        }
        mainHandler.postDelayed(() -> {
            refreshActiveController();
            PlaybackSnapshot current = buildSnapshot();
            Prefs.savePlayback(this, current);
            captureAndPublish(true);

            if (isCommandSatisfied(command, before, current)) {
                finishCommand(command, remote, true, successMessage(command));
                return;
            }

            boolean didRetry = retried;
            if (!retried && attempt >= 1) {
                didRetry = dispatchCommand(command, true);
                if (didRetry) Prefs.saveStatus(this, "播放器响应较慢 · 已自动重试");
            }
            verifyCommand(command, before, remote, attempt + 1, didRetry || retried);
        }, VERIFY_DELAYS_MS[attempt]);
    }

    private boolean dispatchCommand(RemoteCommand command, boolean fallbackOnly) {
        try {
            if ("seek".equals(command.type)) {
                if (command.positionMs == null || controller == null) return false;
                controller.getTransportControls().seekTo(command.positionMs);
                return true;
            }

            if (!fallbackOnly && controller != null) {
                switch (command.type) {
                    case "play":
                        controller.getTransportControls().play();
                        return true;
                    case "pause":
                        controller.getTransportControls().pause();
                        return true;
                    case "next":
                        if (supports(PlaybackState.ACTION_SKIP_TO_NEXT)) {
                            controller.getTransportControls().skipToNext();
                            return true;
                        }
                        break;
                    case "previous":
                        if (supports(PlaybackState.ACTION_SKIP_TO_PREVIOUS)) {
                            controller.getTransportControls().skipToPrevious();
                            return true;
                        }
                        break;
                    default:
                        return false;
                }
            }

            switch (command.type) {
                case "play":
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
                    return true;
                case "pause":
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
                    return true;
                case "next":
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
                    return true;
                case "previous":
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                    return true;
                default:
                    return false;
            }
        } catch (Throwable error) {
            Prefs.saveLastCommandResult(this, "执行失败：" + safeMessage(error));
            return false;
        }
    }

    private boolean isCommandSatisfied(RemoteCommand command, PlaybackSnapshot before, PlaybackSnapshot current) {
        switch (command.type) {
            case "play":
                return current.playing;
            case "pause":
                return !current.playing;
            case "next":
            case "previous":
                if (!Objects.equals(before.trackKey(), current.trackKey())) return true;
                return before.positionMs > 8_000L && current.positionMs + 4_000L < before.positionMs;
            case "seek":
                if (command.positionMs == null) return false;
                return Math.abs(current.positionMs - command.positionMs) <= 3_000L;
            default:
                return false;
        }
    }

    private void finishCommand(RemoteCommand command, boolean remote, boolean success, String message) {
        String status = success ? "executed" : "failed";
        Prefs.saveLastCommandId(this, command.id);
        Prefs.saveLastCommandStatus(this, status);
        Prefs.saveLastCommandResult(this, message);
        Prefs.saveStatus(this, success ? message : "控制失败 · " + message);

        if (remote) {
            commandNetwork.execute(() -> {
                try {
                    RoomApi.acknowledgeCommandSync(
                            TongpinNotificationListener.this,
                            command.id,
                            status,
                            message
                    );
                } catch (Throwable ignored) {
                } finally {
                    commandInFlight.compareAndSet(command.id, "");
                }
            });
        } else {
            localCommandInFlight.set(false);
        }
        captureAndPublish(true);
        scheduleCaptureBurst();
    }

    private void retryFinalAcknowledgement(String id, String status, String message) {
        commandNetwork.execute(() -> {
            try {
                RoomApi.acknowledgeCommandSync(
                        TongpinNotificationListener.this,
                        id,
                        status,
                        message
                );
            } catch (Throwable ignored) {
            }
        });
    }

    private void scheduleCaptureBurst() {
        long[] delays = {120L, 360L, 720L, 1_250L, 2_100L, 3_500L, 5_500L};
        for (long delay : delays) {
            mainHandler.postDelayed(() -> {
                refreshActiveController();
                captureAndPublish(false);
            }, delay);
        }
    }

    private boolean supports(long action) {
        if (controller == null) return false;
        PlaybackState state = controller.getPlaybackState();
        return state != null && (state.getActions() & action) != 0L;
    }

    private void dispatchMediaKey(int keyCode) {
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) throw new IllegalStateException("音频服务不可用");
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    private static long livePosition(PlaybackState state, long durationMs) {
        if (state == null) return 0L;
        long position = Math.max(0L, state.getPosition());
        if (state.getState() == PlaybackState.STATE_PLAYING && state.getLastPositionUpdateTime() > 0L) {
            long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime());
            position += Math.round(elapsed * state.getPlaybackSpeed());
        }
        if (durationMs > 0L) position = Math.min(position, durationMs);
        return Math.max(0L, position);
    }

    private static String metadataText(MediaMetadata metadata, String key) {
        if (metadata == null) return "";
        String value = metadata.getString(key);
        return value == null ? "" : value.trim();
    }

    private static String successMessage(RemoteCommand command) {
        switch (command.type) {
            case "play": return "播放器已开始播放";
            case "pause": return "播放器已暂停";
            case "next": return "已切换到下一首";
            case "previous": return "已切换到上一首";
            case "seek": return "播放进度已跳转";
            default: return "命令已执行";
        }
    }

    private static String commandLabel(String type) {
        switch (type) {
            case "play": return "播放";
            case "pause": return "暂停";
            case "next": return "下一首";
            case "previous": return "上一首";
            case "seek": return "跳转进度";
            default: return "未知控制";
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isEmpty() ? "未知错误" : message;
    }
}
